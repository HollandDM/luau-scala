package luau.core.codec

import luau.core.*

trait Sink[H]:
  val binding: Binding[H]
  val state:   H

  def pushNil(): Unit

  def pushBoolean(value: Boolean): Unit

  def pushNumber(value: Double): Unit

  def pushBytes(bytes: IArray[Byte]): Unit

  def pushString(value: String): Unit =
    pushBytes(IArray.unsafeFromArray(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))

  def beginTable(): Unit

  def endTable(): Unit

  def pushKey(key: LuaValue): Unit

  def pushValue[A: LuauEncoder](value: A): Unit

  def pushArrayValue[A: LuauEncoder](n: Int, value: A): Unit

  def pushField[A: LuauEncoder](name: String, value: A): Unit =
    pushKey(LuaValue.LuaString.fromUtf8(name))
    pushValue(value)
