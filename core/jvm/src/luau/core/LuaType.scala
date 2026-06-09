package luau.core

enum LuaType(val luaCode: Int):
  case None     extends LuaType(-1)
  case Nil      extends LuaType(0)
  case Boolean  extends LuaType(1)
  case Number   extends LuaType(3)
  case String   extends LuaType(4)
  case Table    extends LuaType(5)
  case Function extends LuaType(6)
  case Userdata extends LuaType(7)
  case Thread   extends LuaType(8)

object LuaType:
  def fromCode(code: Int): LuaType =
    values.find(_.luaCode == code).getOrElse(
      throw IllegalArgumentException(s"Unknown Luau type code: $code")
    )
