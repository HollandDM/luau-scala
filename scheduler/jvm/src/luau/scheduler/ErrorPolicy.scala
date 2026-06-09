package luau.scheduler

/** Error policy invoked when a Task fails. */
trait ErrorPolicy:
  def onTaskError(task: Task[?], error: String): Unit

object ErrorPolicy:
  val logAndDiscard: ErrorPolicy = (task, error) =>
    Console.err.println(s"[luau-scheduler] Task ${task.id} failed: $error")
