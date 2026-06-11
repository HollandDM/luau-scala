package luau.api

private[api] trait Pump:
  def wake(): Unit
  def shutdown(): Unit

private[api] object Pump:
  def start(drain: () => Unit): Pump = PumpPlatform.start(drain)
