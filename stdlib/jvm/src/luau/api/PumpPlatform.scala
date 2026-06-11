package luau.api

private[api] object PumpPlatform:
  def start(drain: () => Unit): Pump = new Pump:
    private val lock = new Object
    private var signal = false
    @volatile private var alive = true
    Thread.ofVirtual().name("luau-driver").start { () =>
      while alive do
        lock.synchronized { while !signal && alive do lock.wait(); signal = false }
        if alive then drain()
    }
    def wake(): Unit = lock.synchronized { signal = true; lock.notifyAll() }
    def shutdown(): Unit = lock.synchronized { alive = false; lock.notifyAll() }
