package luau.core.codec

import luau.core.*

final class SinkImpl[H](
  val binding: Binding[H],
  val state:   H,
) extends Sink[H]:

  private var depth: Int = 0

  def pushNil(): Unit = binding.pushNil(state)
  def pushBoolean(value: Boolean): Unit = binding.pushBoolean(state, value)
  def pushNumber(value: Double): Unit   = binding.pushNumber(state, value)
  def pushBytes(bytes: IArray[Byte]): Unit = binding.pushBytes(state, bytes)

  def beginTable(): Unit =
    binding.newTable(state)
    depth += 1

  def endTable(): Unit =
    require(depth > 0, "endTable without matching beginTable")
    depth -= 1

  def pushKey(key: LuaValue): Unit =
    key match
      case LuaValue.Nil              => binding.pushNil(state)
      case LuaValue.Bool(b)          => binding.pushBoolean(state, b)
      case LuaValue.Number(n)        => binding.pushNumber(state, n)
      case LuaValue.LuaString(bytes) => binding.pushBytes(state, bytes)
      case _: LuaValue.LuaRef        =>
        throw IllegalArgumentException("LuaRef cannot be used as a table key (ADR-0006)")

  def pushValue[A: LuauEncoder](value: A): Unit =
    summon[LuauEncoder[A]].encode(value, this)
    binding.rawSet(state, -3)

  def pushArrayValue[A: LuauEncoder](n: Int, value: A): Unit =
    summon[LuauEncoder[A]].encode(value, this)
    binding.setArray(state, -2, n)
