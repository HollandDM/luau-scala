package luau.scheduler

import java.util.{Timer, TimerTask}
import luau.core.Cancel

private[luau] object TimerPlatform:
  def create(): TaskTimer = new TaskTimer:
    private val timer = new Timer("luau-task-timer", true)
    def schedule(seconds: Double)(callback: () => Unit): Cancel =
      val tt = new TimerTask { def run(): Unit = callback() }
      // Ceil + 1ms: java.util.Timer computes its deadline from a truncated
      // currentTimeMillis, so an exact-ms delay can fire a fraction early.
      // task.wait(t) promises at-least-t — never resume before t elapsed.
      // NaN/negative fire on the next tick; oversized delays cap at
      // Int.MaxValue ms (~24 days) — Timer overflows past now + delay.
      val ms = Math.ceil(seconds * 1000) + 1
      val delay = if ms.isNaN then 0L else ms.toLong.max(0L).min(Int.MaxValue.toLong)
      timer.schedule(tt, delay)
      Cancel(() => { tt.cancel(); () })
    def shutdown(): Unit = timer.cancel()
