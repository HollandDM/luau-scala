package luau.scheduler

import luau.core.{LuaError, LuaValue}

final case class ReadyTask[H](task: Task[H], values: ResumeValues)

enum ResumeValues:
  case None
  case SuspendValue(result: LuaValue)
  case Failure(error: LuaError)
  case Pushed(nargs: Int)
