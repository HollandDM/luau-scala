package luau.core.codec

import luau.core.*

extension [H](b: Binding[H])
  def pushEncoded[A: LuauEncoder](state: H, value: A): Unit =
    val sink = SinkImpl(b, state)
    summon[LuauEncoder[A]].encode(value, sink)

  def decodeAt[A: LuauDecoder](state: H, idx: Int): Either[LuaError, A] =
    summon[LuauDecoder[A]].decode(b, state, idx)
