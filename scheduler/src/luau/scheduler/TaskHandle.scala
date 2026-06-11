package luau.scheduler

import luau.core.Ref

final class TaskHandle[H](
  val threadRef: Ref[H],
  val task:      Task[H],
)
