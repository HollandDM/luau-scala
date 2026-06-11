package luau.scheduler

import luau.core.LuaError

/** Invoked when a Task fails. withTasks defaults to failFast (Q6). */
trait ErrorPolicy:
  def onTaskError(task: Task[?], error: LuaError): Unit

object ErrorPolicy:
  val logAndDiscard: ErrorPolicy = (task, error) =>
    Console.err.println(s"[luau-scheduler] Task ${task.id} failed: ${error.message}")

  /** Sentinel recognized by the Driver: first unhandled task error cancels
    * the world and fails the TaskResult. The function body is never called.
    */
  val failFast: ErrorPolicy = (_, _) => ()
