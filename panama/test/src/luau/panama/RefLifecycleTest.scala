package luau.panama

import java.lang.foreign.MemorySegment
import luau.core.*

class RefLifecycleTest extends munit.FunSuite:

  test("lx_ref stores table and lx_push_ref retrieves it") {
    PanamaState.use { ps =>
      ps.newTable(ps.L)
      // ref() consumes the value off the stack (luaL_ref semantics).
      val ref = ps.ref(ps.L)
      assertEquals(ps.stackTop(ps.L), 0)
      ps.pushRef(ps.L, ref.registryKey)
      assertEquals(ps.stackTop(ps.L), 1)
      assertEquals(ps.typeAt(ps.L, -1), LuaType.Table)
    }
  }

  test("PanamaRef.close() is idempotent") {
    PanamaState.use { ps =>
      ps.pushNil(ps.L)
      val ref = ps.ref(ps.L)
      ref.close()
      ref.close()
    }
  }

  test("ref round-trip leaves the stack balanced") {
    PanamaState.use { ps =>
      ps.newTable(ps.L)
      val ref = ps.ref(ps.L)
      assertEquals(ps.stackTop(ps.L), 0)
      ref.push()
      assertEquals(ps.typeAt(ps.L, -1), LuaType.Table)
      ps.pop(ps.L, 1)
      ref.close()
      assertEquals(ps.stackTop(ps.L), 0)
    }
  }

  test("leaked Ref does not crash; state teardown frees it") {
    val ref = PanamaState.use { ps =>
      ps.newTable(ps.L)
      ps.ref(ps.L)
    }
  }
