package luau.core

import luau.core.fake.*
import munit.FunSuite

class RefScopeSpec extends FunSuite:

  test("Ref.close releases registry slot") {
    val state = FakeBinding.newState()
    FakeBinding.pushNumber(state, 42.0)
    val r = FakeBinding.ref(state)
    assert(!r.isClosed)
    assert(state.registry.contains(r.registry))
    r.close()
    assert(r.isClosed)
    assert(!state.registry.contains(r.registry))
  }

  test("Ref.close is idempotent") {
    val state = FakeBinding.newState()
    FakeBinding.pushNumber(state, 1.0)
    val r = FakeBinding.ref(state)
    r.close()
    r.close()
    assert(r.isClosed)
  }

  test("Ref.push restores value to stack") {
    val state = FakeBinding.newState()
    FakeBinding.pushNumber(state, 99.0)
    val r = FakeBinding.ref(state)
    assert(FakeBinding.stackTop(state) == 0)
    r.push()
    assert(FakeBinding.stackTop(state) == 1)
    assert(FakeBinding.toNumber(state, -1).contains(99.0))
    r.close()
  }

  test("Scope closes all owned Refs in LIFO order") {
    val state = FakeBinding.newState()
    val scope = FakeBinding.openScope(state)
    FakeBinding.pushNumber(state, 1.0)
    val r1 = scope.captureTop()
    FakeBinding.pushNumber(state, 2.0)
    val r2 = scope.captureTop()
    assert(!r1.isClosed && !r2.isClosed)
    scope.close()
    assert(r1.isClosed && r2.isClosed)
  }

  test("Ref.close after state close is no-op") {
    val state = FakeBinding.newState()
    FakeBinding.pushNumber(state, 1.0)
    val r = FakeBinding.ref(state)
    FakeBinding.closeState(state)
    r.close()
    assert(r.isClosed)
  }

  test("Using.resource closes Ref on exit") {
    import scala.util.Using
    val state = FakeBinding.newState()
    FakeBinding.pushNumber(state, 7.0)
    var captured: Ref[FakeState] | Null = null
    Using.resource(FakeBinding.ref(state)) { r =>
      captured = r
      assert(!r.isClosed)
    }
    assert(captured != null && captured.isClosed)
  }
