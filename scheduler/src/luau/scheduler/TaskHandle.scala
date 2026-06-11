package luau.scheduler

import luau.core.Ref

final class TaskHandle[H] private[scheduler] (
  private[luau] val threadRef: Ref[H],
  private[luau] val task: Task[H],
  scheduler: Scheduler[H],
):
  def cancel(): Unit = scheduler.cancelTask(task)
  def isDone: Boolean = task.state match
    case TaskState.Complete | TaskState.Cancelled | TaskState.Failed(_) => true
    case _ => false
