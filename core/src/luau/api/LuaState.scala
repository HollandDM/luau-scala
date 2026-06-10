package luau.api

import language.experimental.captureChecking

import luau.core.*
import luau.core.codec.*
import scala.util.{Failure, Success, Try}

/** User-facing facade over one Luau VM.
  *
  * Two planes:
  *   - value plane: `eval` / `run` / `global` / `setGlobal` / `defineGlobal`.
  *     Values are copied across the boundary via codecs; every op that
  *     interprets a Lua value returns `Try` and fails on reference data
  *     (functions, coroutines, userdata) where a copyable value is expected.
  *   - handle plane: `useRef` opens a [[RefScope]]; `evalFn` / `globalFn` /
  *     `globalTbl` / `coro` mint identity handles pinned for the scope's
  *     lifetime. Handles carry `^{s}` so escaping the block is a compile
  *     error; pins are released LIFO at block exit.
  *
  * No raw stack index appears in any public signature; the stack is balanced
  * after every call, on success and failure alike. Scripts producing several
  * results: the first result is decoded, the rest are dropped. Every chunk
  * executes on a fresh thread (see withFreshThread), so a failing script
  * cannot corrupt the VM for later calls.
  */
final class LuaState[H] private[api] (
  private[api] val binding: Binding[H],
  private[api] val state:   H,
):
  import LuaState.MaxResumes

  // ---- Value plane ------------------------------------------------------

  /** Compile and run a chunk to completion, decoding its first result. */
  def eval[V: LuauDecoder](source: String, chunkname: String = "=eval"): Try[V] =
    withFreshThread { thread =>
      runChunk(thread, source, chunkname) match
        case Left(e)  => Failure(e)
        case Right(n) => StackResults.decodeFirst[H, V](binding, thread, n).fold(Failure(_), Success(_))
    }

  /** Compile and run a chunk to completion, discarding any results. */
  def run(source: String, chunkname: String = "=run"): Try[Unit] =
    withFreshThread { thread =>
      runChunk(thread, source, chunkname) match
        case Left(e)  => Failure(e)
        case Right(n) => binding.pop(thread, n); Success(())
    }

  /** Read a global, copying it out. Absent globals decode as nil. */
  def global[V: LuauDecoder](name: String): Try[V] =
    binding.getGlobal(state, name)
    val d = binding.decodeAt[V](state, -1)
    binding.pop(state, 1)
    d.fold(Failure(_), Success(_))

  /** Write a global, copying the value in. */
  def setGlobal[A: LuauEncoder](name: String, value: A): Unit =
    binding.pushEncoded(state, value)
    binding.setGlobal(state, name)

  // ---- Host functions ----------------------------------------------------

  /** Expose a Scala function as a Lua global. Arguments are decoded off the
    * calling thread's stack, the result is encoded back; a decode failure or
    * a thrown exception surfaces as a Lua error in the script. Arity is
    * strict. Overloads cover 0–4 arguments; annotate lambda parameter types.
    */
  def defineGlobal[R: LuauEncoder](name: String)(f: () => R): Unit =
    install(name, hostFn(0)(_ => Right(f())))

  def defineGlobal[A: LuauDecoder, R: LuauEncoder](name: String)(f: A => R): Unit =
    install(name, hostFn(1)(t => binding.decodeAt[A](t, 1).map(f)))

  def defineGlobal[A: LuauDecoder, B: LuauDecoder, R: LuauEncoder](
    name: String
  )(f: (A, B) => R): Unit =
    install(name, hostFn(2) { t =>
      for
        a <- binding.decodeAt[A](t, 1)
        b <- binding.decodeAt[B](t, 2)
      yield f(a, b)
    })

  def defineGlobal[A: LuauDecoder, B: LuauDecoder, C: LuauDecoder, R: LuauEncoder](
    name: String
  )(f: (A, B, C) => R): Unit =
    install(name, hostFn(3) { t =>
      for
        a <- binding.decodeAt[A](t, 1)
        b <- binding.decodeAt[B](t, 2)
        c <- binding.decodeAt[C](t, 3)
      yield f(a, b, c)
    })

  def defineGlobal[A: LuauDecoder, B: LuauDecoder, C: LuauDecoder, D: LuauDecoder, R: LuauEncoder](
    name: String
  )(f: (A, B, C, D) => R): Unit =
    install(name, hostFn(4) { t =>
      for
        a <- binding.decodeAt[A](t, 1)
        b <- binding.decodeAt[B](t, 2)
        c <- binding.decodeAt[C](t, 3)
        d <- binding.decodeAt[D](t, 4)
      yield f(a, b, c, d)
    })

  // ---- Handle plane ------------------------------------------------------

  /** Open a handle scope. Handles minted inside (`evalFn`, `globalFn`,
    * `globalTbl`, `coro`) are pinned in the Lua registry for the duration of
    * the block and released (LIFO) when it exits. The `^{s}` in each handle
    * type makes escape a compile error.
    */
  def useRef[A](f: (s: RefScope[H]^) ?=> A): A =
    val scope = RefScope[H]()
    try f(using scope)
    finally scope.drain()

  /** Run a chunk expected to produce a function; pin it as a handle. */
  def evalFn(source: String, chunkname: String = "=evalFn")(using s: RefScope[H]^): Try[LuaFn[H]^{s}] =
    withFreshThread { thread =>
      runChunk(thread, source, chunkname) match
        case Left(e)  => Failure(e)
        case Right(0) => Failure(LuaError.runtime(s"$chunkname returned no value"))
        case Right(n) =>
          binding.pop(thread, n - 1) // keep the first result
          pinTop(thread, LuaType.Function).fold(Failure(_), r => Success(LuaFn(binding, state, r)))
    }

  /** Pin the function stored in a global as a handle. */
  def globalFn(name: String)(using s: RefScope[H]^): Try[LuaFn[H]^{s}] =
    binding.getGlobal(state, name)
    pinTop(state, LuaType.Function).fold(Failure(_), r => Success(LuaFn(binding, state, r)))

  /** Pin the table stored in a global as a handle. */
  def globalTbl(name: String)(using s: RefScope[H]^): Try[LuaTbl[H]^{s}] =
    binding.getGlobal(state, name)
    pinTop(state, LuaType.Table).fold(Failure(_), r => Success(LuaTbl(binding, state, r)))

  /** Create a coroutine over a pinned function. The backing thread is pinned
    * by the scope; drive it with [[LuaCoro.resume]].
    */
  def coro(fn: LuaFn[H]^)(using s: RefScope[H]^): LuaCoro[H]^{s} =
    val thread = binding.newThread(state) // thread object lands on the main stack
    s.own(binding.ref(state))             // pin it (consumes the stack slot)
    binding.pushRef(thread, fn.ref.registryKey)
    LuaCoro(binding, thread)

  // ---- Internals ---------------------------------------------------------

  /** Every chunk runs on a fresh thread: an erroring script leaves that
    * thread dead and unwound, and touching a dead thread's stack afterwards
    * is undefined behaviour (Luau asserts — SIGILL in release builds). The
    * main thread only ever sees balanced, non-erroring operations, so one
    * failed `eval` can never poison the VM. The thread object is anchored on
    * the main stack for the duration and popped after.
    */
  private def withFreshThread[A](f: H => A): A =
    val thread = binding.newThread(state)
    try f(thread)
    finally binding.pop(state, 1)

  /** Compile onto `thread`, then resume until the chunk completes. Returns
    * the number of results left on the thread's stack.
    */
  private def runChunk(thread: H, source: String, chunkname: String): Either[LuaError, Int] =
    binding.compileAndLoad(thread, source, chunkname) match
      case Left(e) => Left(e)
      case Right(()) =>
        var result  = binding.resume(thread, 0)
        var resumes = 0
        while result.isInstanceOf[ResumeResult.Yielded] && resumes < MaxResumes do
          result = binding.resume(thread, 0)
          resumes += 1
        result match
          case ResumeResult.Returned(n) => Right(n)
          case ResumeResult.Error(e)    => Left(e)
          case ResumeResult.Yielded(_) =>
            Left(LuaError.runtime(s"$chunkname still yielding after $MaxResumes resumes"))

  /** Pin the value on top of `src`'s stack if it has the expected type.
    * Consumes the top either way. The registry is VM-wide, but the returned
    * Ref is bound to the main-thread handle — `src` may be a chunk thread
    * that dies right after, and unref must target a live lua_State.
    */
  private def pinTop(src: H, expected: LuaType)(using s: RefScope[H]^): Either[LuaError, Ref[H]] =
    val t = binding.typeAt(src, -1)
    if t != expected then
      binding.pop(src, 1)
      Left(LuaError.runtime(s"expected $expected, got $t"))
    else
      val pinned = binding.ref(src) // pins into the VM-wide registry, pops src
      Right(s.own(Ref(state, pinned.registryKey, binding, "luau.api.pinTop")))

  private def install(name: String, fn: NativeFn[H]): Unit =
    binding.registerNativeFn(state, fn) // leaves the fn on the stack
    binding.setGlobal(state, name)      // pops it

  private def hostFn[R: LuauEncoder](arity: Int)(body: H => Either[LuaError, R]): NativeFn[H] =
    (thread, nargs) =>
      val result =
        if nargs < arity then Left(LuaError.runtime(s"expected $arity arguments, got $nargs"))
        else body(thread)
      result match
        case Right(r) =>
          binding.pushEncoded(thread, r)
          NativeFnResult.Return(1)
        case Left(e) =>
          binding.pushString(thread, e.message) // dispatchers raise the stack top as the error
          NativeFnResult.Fail

object LuaState:
  /** Livelock guard for top-level chunks that yield to the host. */
  private val MaxResumes = 10_000
