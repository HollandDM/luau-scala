package luau.stdlib

import luau.core.*
import luau.core.NativeFnResult.{Return, Fail, Suspend}
import luau.core.fake.*
import luau.scheduler.*

class StdlibSuite extends munit.FunSuite:

  // ── StdlibOpener: call order ──────────────────────────────────────────

  test("StdlibOpener.open calls openLibs then sandbox"):
    val binding = new CallOrderBinding()
    val state   = binding.newState()
    val sched   = Scheduler(binding, state)

    StdlibOpener.open(binding, state, sched, StdlibMask.Base)

    val order = binding.callOrder.toList
    assert(order.contains("openLibs"), "openLibs was not called")
    assert(order.contains("sandbox"), "sandbox was not called")
    val oi = order.indexOf("openLibs")
    val si = order.indexOf("sandbox")
    assert(oi >= 0 && si >= 0 && oi < si,
      s"openLibs ($oi) must be called before sandbox ($si)")

  // ── StdlibMask values ────────────────────────────────────────────────

  test("StdlibMask.Standard includes all expected libs, excludes Debug"):
    val mask = StdlibMask.Standard
    assert((mask & StdlibMask.Base) != 0)
    assert((mask & StdlibMask.Math) != 0)
    assert((mask & StdlibMask.String) != 0)
    assert((mask & StdlibMask.Table) != 0)
    assert((mask & StdlibMask.Bit32) != 0)
    assert((mask & StdlibMask.Utf8) != 0)
    assert((mask & StdlibMask.Os) != 0)
    assert((mask & StdlibMask.Coroutine) != 0)
    assert((mask & StdlibMask.Vector) != 0)
    assert((mask & StdlibMask.Buffer) != 0)
    assert((mask & StdlibMask.Debug) == 0)

  // ── Scheduler: spawnImmediate ─────────────────────────────────────────

  test("Scheduler.spawnImmediate creates Task that completes or yields"):
    val binding = TestBinding()
    binding.programResumes(ResumeResult.Returned(0))
    val state = binding.newState()
    val sched = Scheduler(binding, state)
    val fnRef = binding.ref(state)
    val handle = sched.spawnImmediate(fnRef, Nil)
    assert(handle.task.state == TaskState.Complete || handle.task.state == TaskState.Parked)

  test("Scheduler.deferTask creates Task in Queued state"):
    val binding = TestBinding()
    val state = binding.newState()
    val sched = Scheduler(binding, state)
    val fnRef = binding.ref(state)
    val handle = sched.deferTask(fnRef, Nil)
    assertEquals(handle.task.state, TaskState.Queued)

  test("Scheduler.scheduleDelayed creates Task in Parked state"):
    val binding = TestBinding()
    val state = binding.newState()
    val sched = Scheduler(binding, state)
    val fnRef = binding.ref(state)
    val handle = sched.scheduleDelayed(fnRef, Nil, 10.0)
    assertEquals(handle.task.state, TaskState.Parked)

  test("Scheduler.cancelTask removes a Parked task"):
    val binding = TestBinding()
    val state = binding.newState()
    val sched = Scheduler(binding, state)
    val fnRef = binding.ref(state)
    val handle = sched.scheduleDelayed(fnRef, Nil, 10.0)
    assertEquals(handle.task.state, TaskState.Parked)
    sched.cancelTask(handle.task)
    assertEquals(handle.task.state, TaskState.Cancelled)

  test("Scheduler.cancelTask on completed task is no-op"):
    val binding = TestBinding()
    binding.programResumes(ResumeResult.Returned(0))
    val state = binding.newState()
    val sched = Scheduler(binding, state)
    val fnRef = binding.ref(state)
    val handle = sched.spawnImmediate(fnRef, Nil)
    assertEquals(handle.task.state, TaskState.Complete)
    sched.cancelTask(handle.task)
    assertEquals(handle.task.state, TaskState.Complete)

  test("Scheduler.currentTask is None outside resume"):
    val binding = TestBinding()
    val state = binding.newState()
    val sched = Scheduler(binding, state)
    assertEquals(sched.currentTask, None)

  test("Scheduler.enqueueResume creates Queued task"):
    val binding = TestBinding()
    val state = binding.newState()
    val sched = Scheduler(binding, state)
    val task = sched.spawn()
    assertEquals(task.state, TaskState.Queued)
    sched.enqueueResume(task, Right(LuaValue.Number(42.0)))

  test("Scheduler.cancelThread cancels task by threadRef"):
    val binding = TestBinding()
    val state = binding.newState()
    val sched = Scheduler(binding, state)
    val fnRef = binding.ref(state)
    val handle = sched.scheduleDelayed(fnRef, Nil, 10.0)
    assert(handle.task.state == TaskState.Parked)
    sched.cancelThread(handle.threadRef)
    assertEquals(handle.task.state, TaskState.Cancelled)

  // ── Task library installation ─────────────────────────────────────────

  test("TaskLibrary.install creates task global table"):
    val binding = new CallOrderBinding()
    val state = binding.newState()
    val sched = Scheduler(binding, state)
    TaskLibrary.install(binding, state, sched)
    binding.getGlobal(state, "task")
    assertEquals(binding.typeAt(state, -1), LuaType.Table)

/** TestBinding that records call order for openLibs/sandbox. */
private class CallOrderBinding extends TestBinding:
  val callOrder = scala.collection.mutable.ArrayBuffer[String]()

  override def openLibs(state: FakeState, mask: Int): Unit =
    callOrder += "openLibs"
    super.openLibs(state, mask)

  override def sandbox(state: FakeState): Unit =
    callOrder += "sandbox"
    super.sandbox(state)
