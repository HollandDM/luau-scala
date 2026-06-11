package luau.api

import language.experimental.captureChecking

import luau.core.*
import luau.core.codec.*
import scala.util.{Failure, Success, Try}

/** A keyed place holding Lua values. Concrete cases: globals (`K = String`,
  * on [[LuaState]]), table fields (`K = String`, on [[LuaTbl]]) and array
  * elements (`K = Int`, the `Int` overloads on [[LuaTbl]]). All typed access
  * — copy-out, copy-in, handle minting — is implemented here once, against
  * two abstract stack primitives.
  */
abstract class LuaAccess[H, K]:
  private[api] def binding: Binding[H]

  /** Main-thread handle: decode source and pin-rebind target. */
  private[api] def state: H

  /** Push the value at `key` onto `state`'s stack and run `f` with it at -1.
    * Implementations snapshot stackTop on entry and restore it after `f` via
    * setStackTop, not a fixed pop count — `f` may legally consume the value
    * (e.g. when pinning).
    */
  protected def withValueAt[A](key: K)(f: => A): A

  /** Run `push` (which must push exactly one value), then consume it into
    * the slot at `key`.
    */
  protected def storeAt(key: K)(push: => Unit): Unit

  /** Read the value at `key`, copying it out. Absent slots decode as nil. */
  final def get[V: LuauDecoder](key: K): Try[V] =
    withValueAt(key):
      binding.decodeAt[V](state, -1).fold(Failure(_), Success(_))

  /** Write the value at `key`, copying it in. */
  final def set[A: LuauEncoder](key: K, value: A): Unit =
    storeAt(key)(binding.pushEncoded(state, value))

  /** Pin the function stored at `key` as a handle. */
  final def getFn(key: K)(using s: RefScope[H]^): Try[LuaFn[H]^{s}] =
    withValueAt(key):
      pinTop(state, LuaType.Function).fold(Failure(_), r => Success(LuaFn(binding, state, r)))

  /** Pin the table stored at `key` as a handle. */
  final def getTbl(key: K)(using s: RefScope[H]^): Try[LuaTbl[H]^{s}] =
    withValueAt(key):
      pinTop(state, LuaType.Table).fold(Failure(_), r => Success(LuaTbl(binding, state, r)))

  /** Pin the value on top of `src`'s stack if it has the expected type.
    * Consumes the top either way. The registry is VM-wide, but the returned
    * Ref is bound to the main-thread handle — `src` may be a chunk thread
    * that dies right after, and unref must target a live lua_State.
    */
  private[api] def pinTop(src: H, expected: LuaType)(using s: RefScope[H]^): Either[LuaError, Ref[H]] =
    val t = binding.typeAt(src, -1)
    if t != expected then
      binding.pop(src, 1)
      Left(LuaError.runtime(s"expected $expected, got $t"))
    else
      val pinned = binding.ref(src) // pins into the VM-wide registry, pops src
      Right(s.own(Ref(state, pinned.registryKey, binding, "luau.api.pinTop")))
