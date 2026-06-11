package luau.api

private[api] object PumpPlatform:
  def start(drain: () => Unit): Pump = new Pump:
    private val lock = new Object
    private var signal = false
    @volatile private var alive = true
    val t = new Thread(() =>
      try
        while alive do
          lock.synchronized { while !signal && alive do lock.wait(); signal = false }
          if alive then drain()
      catch case e: Throwable =>
        Console.err.println(s"[luau-driver] pump thread crashed: $e")
        e.printStackTrace()
    , "luau-driver")
    t.setDaemon(true)
    t.start()
    def wake(): Unit = lock.synchronized { signal = true; lock.notifyAll() }
    def shutdown(): Unit = lock.synchronized { alive = false; lock.notifyAll() }
