package luau.panama

import munit.FunSuite

class NativeLibSmokeTest extends FunSuite:

  test("lx_newstate returns non-null pointer") {
    PanamaState.use { ps =>
      assert(ps.L.address() != 0L, s"Expected non-null state pointer")
    }
  }

  test("PanamaState.open() and close() lifecycle") {
    val ps = PanamaState.open()
    assert(ps.L.address() != 0L, s"Expected non-null state pointer")
    ps.close()
    assert(ps.isClosed)
  }
