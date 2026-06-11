package luau.panama

import munit.FunSuite

class NativeLibSmokeTest extends FunSuite:

  test("lx_newstate returns non-null pointer") {
    val b = PanamaBinding.instance
    val s = b.newState()
    assert(s.address() != 0L, s"Expected non-null state pointer")
    b.closeState(s)
  }

  test("PanamaBinding newState/closeState lifecycle") {
    val b = PanamaBinding.instance
    val s = b.newState()
    assert(s.address() != 0L, s"Expected non-null state pointer")
    b.closeState(s)
  }
