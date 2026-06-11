package luau.api

import language.experimental.captureChecking

import luau.core.*
import luau.core.codec.*
import scala.util.{Failure, Success, Try}

/** A pre-encoded Lua argument. The class carries the SIP-66 `into` modifier,
  * so plain values convert at `call`/`resume` sites without any language
  * import: `fn.call[Double](21.0, "label")`. `LuaArg(...)` stays available
  * as the explicit spelling.
  */
into final class LuaArg private (private[api] val pushTo: [H] => (Binding[H], H) => Unit)

object LuaArg:
  def apply[A: LuauEncoder](value: A): LuaArg =
    new LuaArg([H] => (b: Binding[H], thread: H) => b.pushEncoded(thread, value))

  given [A: LuauEncoder] => Conversion[A, LuaArg] = apply(_)

private[api] object StackResults:
  /** Decode exactly `Tuple.Size[T]` results as `T` from the `n` results on
    * top of `thread`'s stack, leaving the stack balanced on every path.
    * Strict on extras: `n` greater than the arity is an error — silently
    * dropping results is the bug this kills. Fewer results are nil-padded
    * (Lua multiple-assignment semantics), so `Option`/`Unit` accept the
    * missing positions and strict decoders fail with their own message.
    *
    * Stays `private[api]`: the signature is inline/summonAll machinery; the
    * public surface is the count-suffixed arity methods instantiating it.
    */
  inline def decodeResultsT[H, T <: Tuple](b: Binding[H], thread: H, n: Int): Either[LuaError, T] =
    val arity    = scala.compiletime.constValue[Tuple.Size[T]]
    val decoders = scala.compiletime.summonAll[Tuple.Map[T, LuauDecoder]]
      .toList.asInstanceOf[List[LuauDecoder[Any]]]
    decodeWindow(b, thread, n, arity, decoders).map(_.asInstanceOf[T])

  private def decodeWindow[H](
    b: Binding[H], thread: H, n: Int, arity: Int, decoders: List[LuauDecoder[Any]]
  ): Either[LuaError, Tuple] =
    if n > arity then
      b.pop(thread, n)
      Left(LuaError.runtime(
        s"returned $n results but only $arity consumed — use the arity-$n accessor, results must not be dropped"
      ))
    else
      var pad = n
      while pad < arity do
        b.pushNil(thread)
        pad += 1
      val values = new Array[Any](arity)
      var err: LuaError = null
      var i = 0
      decoders.foreach { d =>
        if err == null then
          d.decode(b, thread, -arity + i) match
            case Right(v) => values(i) = v
            case Left(e)  => err = e
        i += 1
      }
      b.pop(thread, arity)
      if err != null then Left(err) else Right(Tuple.fromArray(values))

/** A pinned Lua function. Minted inside `useRef`; carries the scope in its
  * type, so it cannot outlive the pin.
  *
  * Multi-result calls are strict: `call`/`call1` … `call4` consume exactly
  * as many results as their arity and fail on extras; fewer results are
  * nil-padded.
  */
final class LuaFn[H] private[api] (
  private[api] val binding: Binding[H],
  private[api] val state:   H,
  private[api] val ref:     Ref[H],
):
  /** Call the function and decode its single result. Alias of [[call1]]:
    * a function returning more than one result fails. Runs on a fresh
    * thread, so the main stack stays untouched. A function that yields
    * fails — drive yielding functions via [[LuaState.coro]].
    */
  def call[V: LuauDecoder](args: LuaArg*): Try[V] =
    callWith(args) { (thread, n) =>
      StackResults.decodeResultsT[H, V *: EmptyTuple](binding, thread, n).map(_.head)
    }

  def call1[A: LuauDecoder](args: LuaArg*): Try[A] =
    callWith(args) { (thread, n) =>
      StackResults.decodeResultsT[H, A *: EmptyTuple](binding, thread, n).map(_.head)
    }

  def call2[A: LuauDecoder, B: LuauDecoder](args: LuaArg*): Try[(A, B)] =
    callWith(args) { (thread, n) =>
      StackResults.decodeResultsT[H, (A, B)](binding, thread, n)
    }

  def call3[A: LuauDecoder, B: LuauDecoder, C: LuauDecoder](args: LuaArg*): Try[(A, B, C)] =
    callWith(args) { (thread, n) =>
      StackResults.decodeResultsT[H, (A, B, C)](binding, thread, n)
    }

  def call4[A: LuauDecoder, B: LuauDecoder, C: LuauDecoder, D: LuauDecoder](
    args: LuaArg*
  ): Try[(A, B, C, D)] =
    callWith(args) { (thread, n) =>
      StackResults.decodeResultsT[H, (A, B, C, D)](binding, thread, n)
    }

  private def callWith[A](args: Seq[LuaArg])(
    decode: (H, Int) => Either[LuaError, A]
  ): Try[A] =
    val thread = binding.newThread(state) // anchored on the main stack during the call
    try
      binding.pushRef(thread, ref.registryKey)
      args.foreach(_.pushTo(binding, thread))
      binding.resume(thread, args.length) match
        case ResumeResult.Returned(n) =>
          decode(thread, n).fold(Failure(_), Success(_))
        case ResumeResult.Yielded(_) =>
          Failure(LuaError.runtime("function yielded — drive it as a coroutine via coro(fn)"))
        case ResumeResult.Error(e) =>
          Failure(e)
    finally binding.pop(state, 1) // drop the thread anchor

/** A pinned Lua table. Minted inside `useRef`; identity handle — reads and
  * writes go to the live table, values are copied at the boundary.
  *
  * String keys address fields, `Int` overloads address array elements:
  * `tbl.get[Double]("x")`, `tbl.get[Double](3)`, `tbl.getFn(1)`. Handles
  * minted off a table (`getFn`/`getTbl`) are pinned in whichever scope is in
  * context at the mint site — nested chains compose:
  * `st.getTbl("config").get.getTbl("callbacks").get.getFn("onTick")`.
  */
final class LuaTbl[H] private[api] (
  private[api] val binding: Binding[H],
  private[api] val state:   H,
  private[api] val ref:     Ref[H],
) extends LuaAccess[H, String]:

  protected def withValueAt[A](key: String)(f: => A): A =
    val base = binding.stackTop(state)
    ref.push()                    // table
    binding.pushString(state, key)
    binding.rawGet(state, -2)     // pops key, pushes value
    try f
    finally binding.setStackTop(state, base)

  protected def storeAt(key: String)(push: => Unit): Unit =
    val base = binding.stackTop(state)
    ref.push()                    // table
    binding.pushString(state, key)
    push                          // value
    binding.rawSet(state, -3)     // pops key + value
    binding.setStackTop(state, base)

  private val elems = LuaElems(binding, state, ref)

  // Array elements: LuaAccess[H, Int] cannot be inherited twice with a
  // different K, so the Int surface delegates to a private LuaElems.
  def get[V: LuauDecoder](idx: Int): Try[V]            = elems.get[V](idx)
  def set[A: LuauEncoder](idx: Int, value: A): Unit    = elems.set(idx, value)
  def getFn(idx: Int)(using s: RefScope[H]^): Try[LuaFn[H]^{s}]  = elems.getFn(idx)
  def getTbl(idx: Int)(using s: RefScope[H]^): Try[LuaTbl[H]^{s}] = elems.getTbl(idx)

  /** Array length (`lua_objlen`) of the pinned table. */
  def length: Long =
    ref.push()
    val n = binding.rawLen(state, -1)
    binding.pop(state, 1)
    n

  /** Copy the array part out as a Seq — a snapshot at call time. Fails on
    * reference-data elements, same rule as the value plane.
    */
  def toSeq[V: LuauDecoder]: Try[Seq[V]] =
    ref.push()
    val d = binding.decodeAt[Seq[V]](state, -1)
    binding.pop(state, 1)
    d.fold(Failure(_), Success(_))

  /** Copy the string-keyed entries out as a Map — a snapshot at call time.
    * Fails on reference-data values or non-string keys.
    */
  def toMap[V: LuauDecoder]: Try[Map[String, V]] =
    ref.push()
    val d = binding.decodeAt[Map[String, V]](state, -1)
    binding.pop(state, 1)
    d.fold(Failure(_), Success(_))

/** Array-element access for [[LuaTbl]] (`K = Int`, raw 1-based indices). */
private[api] final class LuaElems[H](
  private[api] val binding: Binding[H],
  private[api] val state:   H,
  private val tblRef:       Ref[H],
) extends LuaAccess[H, Int]:

  protected def withValueAt[A](key: Int)(f: => A): A =
    val base = binding.stackTop(state)
    tblRef.push()                 // table
    binding.pushNumber(state, key.toDouble)
    binding.rawGet(state, -2)     // pops key, pushes value
    try f
    finally binding.setStackTop(state, base)

  protected def storeAt(key: Int)(push: => Unit): Unit =
    val base = binding.stackTop(state)
    tblRef.push()                 // table
    binding.pushNumber(state, key.toDouble)
    push                          // value
    binding.rawSet(state, -3)     // pops key + value
    binding.setStackTop(state, base)

enum CoroStep[+V]:
  case Yielded(value: V)
  case Done(value: V)

/** A live coroutine over a pinned function. The backing thread is pinned by
  * the minting scope, so the coroutine cannot be collected mid-flight.
  *
  * Multi-result steps are strict: `resume`/`resume1` … `resume4` consume
  * exactly as many yielded/returned values as their arity and fail on
  * extras; fewer values are nil-padded (so `Unit`/`Option` work for
  * value-less yields).
  */
final class LuaCoro[H] private[api] (
  private[api] val binding: Binding[H],
  private[api] val thread:  H,
):
  /** Resume and decode the single yielded/returned value. Alias of
    * [[resume1]]: a step producing more than one value fails.
    */
  def resume[V: LuauDecoder](args: LuaArg*): Try[CoroStep[V]] =
    resumeWith(args) { (t, n) =>
      StackResults.decodeResultsT[H, V *: EmptyTuple](binding, t, n).map(_.head)
    }

  def resume1[A: LuauDecoder](args: LuaArg*): Try[CoroStep[A]] =
    resumeWith(args) { (t, n) =>
      StackResults.decodeResultsT[H, A *: EmptyTuple](binding, t, n).map(_.head)
    }

  def resume2[A: LuauDecoder, B: LuauDecoder](args: LuaArg*): Try[CoroStep[(A, B)]] =
    resumeWith(args) { (t, n) =>
      StackResults.decodeResultsT[H, (A, B)](binding, t, n)
    }

  def resume3[A: LuauDecoder, B: LuauDecoder, C: LuauDecoder](
    args: LuaArg*
  ): Try[CoroStep[(A, B, C)]] =
    resumeWith(args) { (t, n) =>
      StackResults.decodeResultsT[H, (A, B, C)](binding, t, n)
    }

  def resume4[A: LuauDecoder, B: LuauDecoder, C: LuauDecoder, D: LuauDecoder](
    args: LuaArg*
  ): Try[CoroStep[(A, B, C, D)]] =
    resumeWith(args) { (t, n) =>
      StackResults.decodeResultsT[H, (A, B, C, D)](binding, t, n)
    }

  private def resumeWith[A](args: Seq[LuaArg])(
    decode: (H, Int) => Either[LuaError, A]
  ): Try[CoroStep[A]] =
    args.foreach(_.pushTo(binding, thread))
    binding.resume(thread, args.length) match
      case ResumeResult.Yielded(n) =>
        decode(thread, n).fold(Failure(_), v => Success(CoroStep.Yielded(v)))
      case ResumeResult.Returned(n) =>
        decode(thread, n).fold(Failure(_), v => Success(CoroStep.Done(v)))
      case ResumeResult.Error(e) =>
        Failure(e)
