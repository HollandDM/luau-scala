package luau.api

private[api] object PumpPlatform:
  def start(drain: () => Unit): Pump = new Pump:
    private var draining = false
    private var rerun = false
    def wake(): Unit =
      if draining then rerun = true
      else
        draining = true
        try
          drain()
          while rerun do { rerun = false; drain() }
        finally draining = false
    def shutdown(): Unit = ()
