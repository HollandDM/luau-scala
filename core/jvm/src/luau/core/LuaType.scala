package luau.core

/** Lua value tags as reported by the Shim's lx_type.
  *
  * luaCode values mirror the vendored Luau VM's lua_Type enum exactly
  * (shim/luau/VM/include/lua.h, re-exported as LX_T* in shim/include/lx.h):
  * lx_type passes raw lua_type results through, so these must stay in sync.
  */
enum LuaType(val luaCode: Int):
  case None          extends LuaType(-1)
  case Nil           extends LuaType(0)
  case Boolean       extends LuaType(1)
  case LightUserdata extends LuaType(2)
  case Number        extends LuaType(3)
  case Integer       extends LuaType(4)
  case Vector        extends LuaType(5)
  case String        extends LuaType(6)
  case Table         extends LuaType(7)
  case Function      extends LuaType(8)
  case Userdata      extends LuaType(9)
  case Thread        extends LuaType(10)
  case Buffer        extends LuaType(11)

object LuaType:
  def fromCode(code: Int): LuaType =
    values.find(_.luaCode == code).getOrElse(
      throw IllegalArgumentException(s"Unknown Luau type code: $code")
    )
