package luau.panama

import munit.FunSuite
import luau.core.LuauLib

class PanamaLifecycleSpec extends FunSuite:

  test("LC-01 sequential states leave dispatcher bookkeeping empty"):
    val b = PanamaBinding.instance
    (1 to 5).foreach { i =>
      val s = b.newState()
      try
        b.openLibs(s, LuauLib.Standard)
        b.sandbox(s)
        b.registerNativeFn(s, (_, _) => luau.core.NativeFnResult.Return(0))
        b.pop(s, 1)
      finally b.closeState(s)
    }
    assertEquals(PanamaRuntime.registeredFnCount, 0)
    assertEquals(PanamaRuntime.inFlightSuspendCount, 0)

  test("LC-02 exactly one lx_newstate per newState"):
    val before = PanamaRuntime.statesOpened
    val b = PanamaBinding.instance
    val s = b.newState()
    b.closeState(s)
    assertEquals(PanamaRuntime.statesOpened - before, 1L)

  test("LC-03 second live state throws; sequential reuse fine"):
    val b = PanamaBinding.instance
    val s1 = b.newState()
    intercept[IllegalStateException] { b.newState() }
    b.closeState(s1)
    val s2 = b.newState()
    b.closeState(s2)
