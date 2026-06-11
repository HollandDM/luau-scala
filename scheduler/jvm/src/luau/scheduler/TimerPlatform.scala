package luau.scheduler

import java.util.{Timer, TimerTask}
import luau.core.Cancel

private[luau] object TimerPlatform:
  def create(): TaskTimer = new TaskTimer:
    private val timer = new Timer("luau-task-timer", true)
    def schedule(seconds: Double)(callback: () => Unit): Cancel =
      val tt = new TimerTask { def run(): Unit = callback() }
      timer.schedule(tt, (seconds * 1000).toLong.max(0L))
      Cancel(() => { tt.cancel(); () })
    def shutdown(): Unit = timer.cancel()
