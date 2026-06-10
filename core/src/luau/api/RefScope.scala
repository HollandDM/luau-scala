package luau.api

import language.experimental.captureChecking

import luau.core.Ref
import scala.collection.mutable

/** Tracks every registry pin minted inside one `useRef` block and releases
  * them (LIFO) when the block exits. Handles minted against a scope carry
  * `^{s}` in their type, so letting one escape the block is a compile error —
  * a handle can never outlive the pin that keeps its Lua value alive.
  *
  * Constructed only by [[LuaState.useRef]]; user code receives it as a
  * context parameter and never calls anything on it directly.
  */
final class RefScope[H] private[api] ():

  private val pins = mutable.ArrayDeque.empty[Ref[H]]

  private[api] def own(r: Ref[H]): r.type =
    pins.append(r)
    r

  private[api] def drain(): Unit =
    while pins.nonEmpty do pins.removeLast().close()
