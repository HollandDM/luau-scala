package luau.core.fake

import luau.core.*
import scala.collection.mutable

final class FakeState:
  val stack:     mutable.ArrayDeque[LuaValue]               = mutable.ArrayDeque.empty
  val registry:  mutable.Map[Int, LuaValue]                 = mutable.Map.empty
  val globals:   mutable.Map[String, LuaValue]              = mutable.Map.empty
  val nativeFns: mutable.Map[Int, NativeFn[FakeState]]      = mutable.Map.empty

  private var nextRegKey: Int  = 1
  private var nextFnId:   Int  = 1
  private var closed:     Boolean = false

  def allocRegKey(): Int   = { val k = nextRegKey; nextRegKey += 1; k }
  def allocFnId():   Int   = { val k = nextFnId;   nextFnId   += 1; k }
  def isClosed:      Boolean = closed
  def markClosed():  Unit    = closed = true

  def stackIdx(idx: Int): Int =
    if idx > 0 then idx - 1
    else stack.size + idx

  def valueAt(idx: Int): LuaValue =
    val i = stackIdx(idx)
    if i < 0 || i >= stack.size then LuaValue.Nil
    else stack(i)
