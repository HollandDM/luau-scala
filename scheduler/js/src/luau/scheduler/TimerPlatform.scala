package luau.scheduler

import scala.scalajs.js.timers
import luau.core.Cancel

private[luau] object TimerPlatform:
  def create(): TaskTimer = new TaskTimer:
    def schedule(seconds: Double)(callback: () => Unit): Cancel =
      // Ceil + 1ms: setTimeout truncates fractional delays to whole ms, so an
      // exact-ms delay can fire early. task.wait(t) promises at-least-t.
      // NaN/negative fire on the next tick; oversized delays cap at 2^31-1 ms
      // — setTimeout wraps longer delays to 0.
      val raw = Math.ceil(seconds * 1000) + 1
      val ms  = if raw.isNaN then 0.0 else raw.max(0.0).min(2147483647.0)
      val handle = timers.setTimeout(ms)(callback())
      Cancel(() => timers.clearTimeout(handle))
    def shutdown(): Unit = ()
