package luau.core

final case class LuaError(message: String, level: LuaError.Level) extends Throwable(message, null, true, false)

object LuaError:
  enum Level:
    case Runtime
    case Memory
    case Handler

  def runtime(msg: String): LuaError = LuaError(msg, Level.Runtime)
  def memory(msg: String): LuaError  = LuaError(msg, Level.Memory)
