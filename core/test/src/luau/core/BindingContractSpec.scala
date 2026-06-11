package luau.core

import munit.FunSuite
import luau.core.fake.FakeBinding

class BindingContractSpec extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    FakeBinding.releaseStateSlot()

  test("second live state throws IllegalStateException"):
    val s1 = FakeBinding.newState()
    intercept[IllegalStateException] { FakeBinding.newState() }
    FakeBinding.closeState(s1)
    val s2 = FakeBinding.newState()
    FakeBinding.closeState(s2)

  test("reserveStateSlot blocks a second reservation and newState honors it"):
    FakeBinding.reserveStateSlot()
    intercept[IllegalStateException] { FakeBinding.reserveStateSlot() }
    val s = FakeBinding.newState()
    FakeBinding.closeState(s)
    FakeBinding.reserveStateSlot()
    FakeBinding.releaseStateSlot()

  test("takePendingSuspend is one-shot"):
    val s = FakeBinding.newState()
    val suspend: NativeFnResult.Suspend = NativeFnResult.Suspend(_ => Cancel.noop)
    FakeBinding.setPendingSuspendForTest(s, suspend)
    val r1 = FakeBinding.takePendingSuspend(s)
    assert(r1.isDefined, "first take should return the suspend")
    val r2 = FakeBinding.takePendingSuspend(s)
    assert(r2.isEmpty, "second take should be None")
    FakeBinding.closeState(s)
