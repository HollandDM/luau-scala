package luau.api

import language.experimental.captureChecking

import luau.core.*
import luau.core.codec.*
import scala.util.{Failure, Success, Try}

/** A pre-encoded Lua argument. Lets `call`/`resume` accept mixed argument
  * types without an overload per arity: wrap each value with `LuaArg(...)`,
  * or enable `scala.language.implicitConversions` and pass values directly.
  */
final class LuaArg private (private[api] val pushTo: [H] => (Binding[H], H) => Unit)

object LuaArg:
  def apply[A: LuauEncoder](value: A): LuaArg =
    new LuaArg([H] => (b: Binding[H], thread: H) => b.pushEncoded(thread, value))

  given [A: LuauEncoder] => Conversion[A, LuaArg] = apply(_)

private[api] object StackResults:
  /** Decode the first of `n` results from the top of `thread`'s stack and
    * leave the stack balanced. Zero results decode as nil, so `Unit` and
    * `Option[_]` work for value-less returns.
    */
  def decodeFirst[H, V: LuauDecoder](b: Binding[H], thread: H, n: Int): Either[LuaError, V] =
    if n == 0 then
      b.pushNil(thread)
      val d = b.decodeAt[V](thread, -1)
      b.pop(thread, 1)
      d
    else
      val d = b.decodeAt[V](thread, -n)
      b.pop(thread, n)
      d

/** A pinned Lua function. Minted inside `useRef`; carries the scope in its
  * type, so it cannot outlive the pin.
  */
final class LuaFn[H] private[api] (
  private[api] val binding: Binding[H],
  private[api] val state:   H,
  private[api] val ref:     Ref[H],
):
  /** Call the function with the given arguments and decode its first result.
    * Runs on a fresh thread, so the main stack stays untouched. A function
    * that yields fails — drive yielding functions via [[LuaState.coro]].
    */
  def call[V: LuauDecoder](args: LuaArg*): Try[V] =
    val thread = binding.newThread(state) // anchored on the main stack during the call
    try
      binding.pushRef(thread, ref.registryKey)
      args.foreach(_.pushTo(binding, thread))
      binding.resume(thread, args.length) match
        case ResumeResult.Returned(n) =>
          StackResults.decodeFirst[H, V](binding, thread, n).fold(Failure(_), Success(_))
        case ResumeResult.Yielded(_) =>
          Failure(LuaError.runtime("function yielded — drive it as a coroutine via coro(fn)"))
        case ResumeResult.Error(e) =>
          Failure(e)
    finally binding.pop(state, 1) // drop the thread anchor

/** A pinned Lua table. Minted inside `useRef`; identity handle — reads and
  * writes go to the live table, values are copied at the boundary.
  */
final class LuaTbl[H] private[api] (
  private[api] val binding: Binding[H],
  private[api] val state:   H,
  private[api] val ref:     Ref[H],
):
  def get[V: LuauDecoder](key: String): Try[V] =
    ref.push() // table
    binding.pushString(state, key)
    binding.rawGet(state, -2) // pops key, pushes value
    val d = binding.decodeAt[V](state, -1)
    binding.pop(state, 2) // value + table
    d.fold(Failure(_), Success(_))

  def set[A: LuauEncoder](key: String, value: A): Unit =
    ref.push() // table
    binding.pushString(state, key)
    binding.pushEncoded(state, value)
    binding.rawSet(state, -3) // pops key + value
    binding.pop(state, 1) // table

enum CoroStep[+V]:
  case Yielded(value: V)
  case Done(value: V)

/** A live coroutine over a pinned function. The backing thread is pinned by
  * the minting scope, so the coroutine cannot be collected mid-flight.
  */
final class LuaCoro[H] private[api] (
  private[api] val binding: Binding[H],
  private[api] val thread:  H,
):
  /** Resume with the given arguments. Decodes the first yielded/returned
    * value as V (nil when there is none, so `Unit`/`Option` work).
    */
  def resume[V: LuauDecoder](args: LuaArg*): Try[CoroStep[V]] =
    args.foreach(_.pushTo(binding, thread))
    binding.resume(thread, args.length) match
      case ResumeResult.Yielded(n) =>
        StackResults.decodeFirst[H, V](binding, thread, n)
          .fold(Failure(_), v => Success(CoroStep.Yielded(v)))
      case ResumeResult.Returned(n) =>
        StackResults.decodeFirst[H, V](binding, thread, n)
          .fold(Failure(_), v => Success(CoroStep.Done(v)))
      case ResumeResult.Error(e) =>
        Failure(e)
