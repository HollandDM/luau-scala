package luau.scheduler

import luau.core.Cancel

/** Platform timer seam. Callbacks fire on the timer thread (JVM) / event loop
  * (JS) and must only enqueue + wake — never touch the VM (ADR-0007).
  */
trait TaskTimer:
  def schedule(seconds: Double)(callback: () => Unit): Cancel
  def shutdown(): Unit

object TaskTimer:
  def create(): TaskTimer = TimerPlatform.create()
