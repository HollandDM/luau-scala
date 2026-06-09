package luau.scheduler

import luau.core.{LuaError, LuaValue}

/** A ready Task plus the resume values a completion posted.
  *
  * @param task   The Task to be resumed by the Driver.
  * @param values Resume values pushed onto the thread stack before lx_resume.
  */
final case class ReadyTask[H](task: Task[H], values: ResumeValues)

/** Values passed back to a Task when re-queued after a Suspend. */
enum ResumeValues:
  case None
  case Success(result: LuaValue)
  case Failure(error: LuaError)
