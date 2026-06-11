package luau.scheduler

import luau.core.LuaError

/** Invoked when a Task fails. withTasks defaults to failFast (Q6). */
trait ErrorPolicy:
  def onTaskError(task: Task[?], error: LuaError): Unit

  /** True → the Driver cancels the world and fails the TaskResult on the
    * first unhandled task error. Structural, not identity-based: wrapping
    * policies (logging decorators etc.) keep fail-fast by forwarding this.
    */
  def isFailFast: Boolean = false

object ErrorPolicy:
  val logAndDiscard: ErrorPolicy = (task, error) =>
    Console.err.println(s"[luau-scheduler] Task ${task.id} failed: ${error.message}")

  /** Fail-fast default (Q6): the Driver intercepts errors itself; this
    * policy's onTaskError body never runs.
    */
  val failFast: ErrorPolicy = new ErrorPolicy:
    override def isFailFast: Boolean = true
    def onTaskError(task: Task[?], error: LuaError): Unit = ()
