package luau.core.fake

import luau.core.*
import scala.collection.mutable

final class FakeTable extends LuaValue:
  val map: mutable.Map[LuaValue, LuaValue] = mutable.Map.empty

  def rawGet(key: LuaValue): LuaValue = map.getOrElse(key, LuaValue.Nil)
  def rawSet(key: LuaValue, value: LuaValue): Unit =
    if value == LuaValue.Nil then map.remove(key)
    else map(key) = value
  def size: Int = map.size

object FakeTable:
  def empty: FakeTable = FakeTable()
