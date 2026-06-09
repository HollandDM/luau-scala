package luau.wasm

import luau.core.*
import luau.core.codec.Sink

final class WasmSink(val binding: WasmBinding, val state: Int) extends Sink[Int]:

  def pushNil(): Unit                    = binding.pushNil(state)
  def pushBoolean(value: Boolean): Unit  = binding.pushBoolean(state, value)
  def pushNumber(value: Double): Unit    = binding.pushNumber(state, value)
  def pushBytes(bytes: IArray[Byte]): Unit = binding.pushBytes(state, bytes)

  def beginTable(): Unit =
    binding.newTable(state)

  def endTable(): Unit = ()

  def pushKey(key: LuaValue): Unit =
    key match
      case LuaValue.Nil              => pushNil()
      case LuaValue.Bool(b)          => pushBoolean(b)
      case LuaValue.Number(n)        => pushNumber(n)
      case LuaValue.LuaString(bytes) => pushBytes(bytes)
      case _: LuaValue.LuaRef        =>
        throw IllegalArgumentException("LuaRef cannot be used as a table key (ADR-0006)")

  def pushValue[A: LuauEncoder](value: A): Unit =
    summon[LuauEncoder[A]].encode(value, this)
    binding.rawSet(state, -3)

  def pushArrayValue[A: LuauEncoder](n: Int, value: A): Unit =
    summon[LuauEncoder[A]].encode(value, this)
    binding.setArray(state, -2, n)
