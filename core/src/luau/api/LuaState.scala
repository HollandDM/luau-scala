package luau.api

import language.experimental.captureChecking

import luau.core.*
import luau.core.codec.*
import scala.util.{Failure, Success, Try}

/** User-facing facade over one Luau VM.
  *
  * Two planes:
  *   - value plane: `eval` / `run` / `get` / `set` / `defineGlobal`.
  *     Values are copied across the boundary via codecs; every op that
  *     interprets a Lua value returns `Try` and fails on reference data
  *     (functions, coroutines, userdata) where a copyable value is expected.
  *   - handle plane: `useRef` opens a [[RefScope]]; `evalFn` / `getFn` /
  *     `getTbl` / `coro` mint identity handles pinned for the scope's
  *     lifetime. Handles carry `^{s}` so escaping the block is a compile
  *     error; pins are released LIFO at block exit.
  *
  * Globals are accessed through the inherited [[LuaAccess]] surface
  * (`get` / `set` / `getFn` / `getTbl`) — the same accessors work on table
  * fields and array elements via [[LuaTbl]].
  *
  * Result counts are exact-match: `eval0` … `eval4` (and `call*`/`resume*`
  * on handles) fail unless the chunk produces exactly the requested arity —
  * Lua's own adjust semantics (drop extras, nil-pad missing) hide contract
  * mismatches at the host boundary, so both directions fail instead. `run`
  * is the explicit discard-everything spelling.
  * No raw stack index appears in any public signature; the stack
  * is balanced after every call, on success and failure alike. Every chunk
  * executes on a fresh thread (see withFreshThread), so a failing script
  * cannot corrupt the VM for later calls.
  */
final class LuaState[H] private[api] (
  private[api] val binding: Binding[H],
  private[api] val state:   H,
) extends LuaAccess[H, String]:
  import LuaState.MaxResumes

  // ---- Value plane ------------------------------------------------------

  /** Compile and run a chunk to completion, decoding its single result.
    * Alias of [[eval1]]: a chunk returning more than one result fails.
    */
  def eval[V: LuauDecoder](source: String, chunkname: String = "=eval"): Try[V] =
    evalWith(source, chunkname) { (thread, n) =>
      StackResults.decodeResultsT[H, V *: EmptyTuple](binding, thread, n).map(_.head)
    }

  /** Compile and run a chunk to completion, discarding any results. */
  def run(source: String, chunkname: String = "=run"): Try[Unit] =
    withFreshThread { thread =>
      runChunk(thread, source, chunkname) match
        case Left(e)  => Failure(e)
        case Right(n) => binding.pop(thread, n); Success(())
    }

  // ---- Multi-result eval (exact-match, count-suffixed) -------------------

  /** Run a chunk that must produce no results. Unlike [[run]], which
    * discards whatever the chunk returns, eval0 fails if it returns
    * anything.
    */
  def eval0(source: String, chunkname: String = "=eval0"): Try[Unit] =
    evalWith(source, chunkname) { (thread, n) =>
      StackResults.decodeResultsT[H, EmptyTuple](binding, thread, n).map(_ => ())
    }

  def eval1[A: LuauDecoder](source: String, chunkname: String = "=eval1"): Try[A] =
    evalWith(source, chunkname) { (thread, n) =>
      StackResults.decodeResultsT[H, A *: EmptyTuple](binding, thread, n).map(_.head)
    }

  def eval2[A: LuauDecoder, B: LuauDecoder](
    source: String, chunkname: String = "=eval2"
  ): Try[(A, B)] =
    evalWith(source, chunkname) { (thread, n) =>
      StackResults.decodeResultsT[H, (A, B)](binding, thread, n)
    }

  def eval3[A: LuauDecoder, B: LuauDecoder, C: LuauDecoder](
    source: String, chunkname: String = "=eval3"
  ): Try[(A, B, C)] =
    evalWith(source, chunkname) { (thread, n) =>
      StackResults.decodeResultsT[H, (A, B, C)](binding, thread, n)
    }

  def eval4[A: LuauDecoder, B: LuauDecoder, C: LuauDecoder, D: LuauDecoder](
    source: String, chunkname: String = "=eval4"
  ): Try[(A, B, C, D)] =
    evalWith(source, chunkname) { (thread, n) =>
      StackResults.decodeResultsT[H, (A, B, C, D)](binding, thread, n)
    }

  // ---- LuaAccess primitives (globals) ------------------------------------

  protected def withValueAt[A](key: String)(f: => A): A =
    val base = binding.stackTop(state)
    binding.getGlobal(state, key)
    try f
    finally binding.setStackTop(state, base)

  protected def storeAt(key: String)(push: => Unit): Unit =
    push
    binding.setGlobal(state, key)

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

  /** Open a handle scope. Handles minted inside (`evalFn`, `getFn`,
    * `getTbl`, `coro`) are pinned in the Lua registry for the duration of
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

  /** Run a chunk on a fresh thread, then decode its `n` results. */
  private def evalWith[A](source: String, chunkname: String)(
    decode: (H, Int) => Either[LuaError, A]
  ): Try[A] =
    withFreshThread { thread =>
      runChunk(thread, source, chunkname) match
        case Left(e)  => Failure(e)
        case Right(n) => decode(thread, n).fold(Failure(_), Success(_))
    }

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
