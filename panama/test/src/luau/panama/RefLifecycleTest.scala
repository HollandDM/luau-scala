package luau.panama

import java.lang.foreign.MemorySegment
import luau.core.*

class RefLifecycleTest extends munit.FunSuite:

  test("lx_ref stores table and lx_push_ref retrieves it".ignore) {
    PanamaState.use { ps =>
      ps.newTable(ps.L)
      val ref = ps.ref(ps.L)
      assertEquals(ps.stackTop(ps.L), 1)
      ps.pop(ps.L, 1)
      assertEquals(ps.stackTop(ps.L), 0)
      ps.pushRef(ps.L, ref.registryKey)
      assertEquals(ps.stackTop(ps.L), 1)
      assertEquals(ps.typeAt(ps.L, -1), LuaType.Table)
    }
  }

  test("PanamaRef.close() is idempotent".ignore) {
    PanamaState.use { ps =>
      ps.pushNil(ps.L)
      val ref = ps.ref(ps.L)
      ref.close()
      ref.close()
    }
  }

  test("scoped block releases Refs on exit".ignore) {
    PanamaState.use { ps =>
      ps.scoped { scope ?=>
        ps.newTable(ps.L)
        val ref = scope.captureTop()
        ps.pop(ps.L, 1)
        assertEquals(ps.stackTop(ps.L), 0)
        ref.push()
        assertEquals(ps.typeAt(ps.L, -1), LuaType.Table)
        ps.pop(ps.L, 1)
      }
      assertEquals(ps.stackTop(ps.L), 0)
    }
  }

  test("leaked Ref does not crash; state teardown frees it".ignore) {
    val ref = PanamaState.use { ps =>
      ps.newTable(ps.L)
      ps.ref(ps.L)
    }
  }
