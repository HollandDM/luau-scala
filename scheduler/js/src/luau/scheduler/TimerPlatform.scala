package luau.scheduler

import scala.scalajs.js.timers
import luau.core.Cancel

private[luau] object TimerPlatform:
  def create(): TaskTimer = new TaskTimer:
    def schedule(seconds: Double)(callback: () => Unit): Cancel =
      val handle = timers.setTimeout(seconds * 1000)(callback())
      Cancel(() => timers.clearTimeout(handle))
    def shutdown(): Unit = ()
