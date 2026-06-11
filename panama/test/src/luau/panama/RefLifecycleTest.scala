package luau.panama

import java.lang.foreign.MemorySegment
import luau.core.*

class RefLifecycleTest extends munit.FunSuite:

  private def withState[A](f: (Binding[MemorySegment], MemorySegment) => A): A =
    val binding = PanamaBinding.instance
    val state = binding.newState()
    try f(binding, state)
    finally binding.closeState(state)

  test("lx_ref stores table and lx_push_ref retrieves it") {
    withState { (b, s) =>
      b.newTable(s)
      val ref = b.ref(s)
      assertEquals(b.stackTop(s), 0)
      b.pushRef(s, ref.registryKey)
      assertEquals(b.stackTop(s), 1)
      assertEquals(b.typeAt(s, -1), LuaType.Table)
    }
  }

  test("Ref.close() is idempotent") {
    withState { (b, s) =>
      b.pushNil(s)
      val ref = b.ref(s)
      ref.close()
      ref.close()
    }
  }

  test("ref round-trip leaves the stack balanced") {
    withState { (b, s) =>
      b.newTable(s)
      val ref = b.ref(s)
      assertEquals(b.stackTop(s), 0)
      ref.push()
      assertEquals(b.typeAt(s, -1), LuaType.Table)
      b.pop(s, 1)
      ref.close()
      assertEquals(b.stackTop(s), 0)
    }
  }

  test("leaked Ref does not crash; state teardown frees it") {
    val b = PanamaBinding.instance
    val s = b.newState()
    b.newTable(s)
    b.ref(s) // leaked — state close will clean up
    b.closeState(s)
  }
