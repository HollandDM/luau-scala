package luau.panama

import munit.FunSuite

class NativeLibSmokeTest extends FunSuite:

  test("lx_newstate returns non-null pointer".ignore) {
    PanamaState.use { ps =>
      assertNot(ps.L, MemorySegment.NULL)
    }
  }

  test("PanamaState.open() and close() lifecycle".ignore) {
    val ps = PanamaState.open()
    assertNot(ps.L, MemorySegment.NULL)
    ps.close()
    assert(ps.isClosed)
  }
