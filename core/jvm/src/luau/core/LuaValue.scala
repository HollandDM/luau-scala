package luau.core

trait LuaValue

object LuaValue:
  case object Nil extends LuaValue

  sealed abstract class Bool(val value: Boolean) extends LuaValue
  case object True extends Bool(true)
  case object False extends Bool(false)

  object Bool:
    def apply(b: Boolean): Bool = if b then True else False
    def unapply(b: Bool): Some[Boolean] = Some(b.value)

  final case class Number(value: Double) extends LuaValue

  final case class LuaString(bytes: IArray[Byte]) extends LuaValue

  object LuaString:
    def fromUtf8(s: String): LuaString =
      LuaString(IArray.unsafeFromArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)))

  final class LuaRef(val ref: Ref[?]) extends LuaValue

  def isTruthy(v: LuaValue): Boolean = v match
    case Nil | False => false
    case _           => true
