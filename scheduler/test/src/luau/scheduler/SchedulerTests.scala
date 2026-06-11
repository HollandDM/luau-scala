package luau.scheduler

import luau.core.*

class SchedulerTests extends munit.FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    luau.core.fake.FakeBinding.releaseStateSlot()

  // ── TC-01: Spawn and immediate complete ───────────────────────────────

  test("TC-01 spawned Task transitions Queued → Running → Complete"):
    val binding = TestBinding()
    val sched   = makeSchedulerWithBinding(binding)
    val task    = sched.spawn()
    assertEquals(task.state, TaskState.Queued)
    sched.runAllReady()
    assertEquals(task.state, TaskState.Complete)

  // ── TC-02: Suspend → enqueue → resume ─────────────────────────────────

  test("TC-02 Suspend parks Task; completion re-enqueues; second runAllReady resumes"):
    val binding = TestBinding()
    binding.programResumes(ResumeResult.Yielded(0), ResumeResult.Returned(0))
    val sched = makeSchedulerWithBinding(binding)
    val async = ControllableAsync()

    val task = sched.spawn()
    binding.setPendingSuspendForTest(task.thread, async.suspend)
    sched.runAllReady()
    assertEquals(task.state, TaskState.Parked)
    assert(async.resume != null, "register() was called")

    val r0 = async.resume
    if r0 != null then r0.asInstanceOf[Resume].complete(Right(LuaValue.Nil))
    assertEquals(task.state, TaskState.Queued)
    sched.runAllReady()
    assertEquals(task.state, TaskState.Complete)

  // ── TC-03: Resume is one-shot ─────────────────────────────────────────

  test("TC-03 double resume is a no-op (does not enqueue twice)"):
    val binding = TestBinding()
    binding.programResumes(ResumeResult.Yielded(0), ResumeResult.Returned(0))
    val sched = makeSchedulerWithBinding(binding)
    val async = ControllableAsync()

    val task = sched.spawn()
    binding.setPendingSuspendForTest(task.thread, async.suspend)
    sched.runAllReady()

    val r = async.resume
    assert(r != null, "resume was captured")
    if r != null then r.asInstanceOf[Resume].complete(Right(LuaValue.Nil))
    if r != null then r.asInstanceOf[Resume].complete(Right(LuaValue.Nil))

    assertEquals(sched.runAllReady(), 1)

  // ── TC-04: Error status → Failed state → error policy ─────────────────

  test("TC-04 lx_resume error transitions Task to Failed and invokes error policy"):
    var capturedError: Option[LuaError] = None
    val policy: ErrorPolicy = (_, err) => capturedError = Some(err)
    val binding = TestBinding()
    binding.programResumes(ResumeResult.Error(LuaError.runtime("script error")))
    val sched = makeSchedulerWithBinding(binding, policy)

    val task = sched.spawn()
    sched.runAllReady()

    task.state match
      case TaskState.Failed(msg) =>
        assertEquals(msg, "script error")
      case other =>
        fail(s"Expected Failed, got $other")
    assertEquals(capturedError.map(_.message), Some("script error"))

  // ── TC-05: Close cancels parked Tasks ─────────────────────────────────

  test("TC-05 close() fires Cancel for parked Tasks"):
    val binding = TestBinding()
    binding.programResumes(ResumeResult.Yielded(0))
    val sched = makeSchedulerWithBinding(binding)
    val async = ControllableAsync()

    val task = sched.spawn()
    binding.setPendingSuspendForTest(task.thread, async.suspend)
    sched.runAllReady()
    assertEquals(task.state, TaskState.Parked)
    assert(!async.cancelled)

    sched.close()
    assert(async.cancelled, "Cancel was fired")
    assertEquals(task.state, TaskState.Cancelled)

  // ── TC-06: Bare yield parks Task permanently ──────────────────────────

  test("TC-06 bare coroutine.yield (no Suspend registered) parks Task permanently"):
    val binding = TestBinding()
    binding.programResumes(ResumeResult.Yielded(0))
    val sched = makeSchedulerWithBinding(binding)

    val task = sched.spawn()
    sched.runAllReady()
    assertEquals(task.state, TaskState.Parked)
    assertEquals(sched.runAllReady(), 0)

  // ── TC-07: Multiple Tasks interleave ──────────────────────────────────

  test("TC-07 two Tasks both spawn, first completes then second completes"):
    val binding = TestBinding()
    binding.programResumes(
      ResumeResult.Returned(0),
      ResumeResult.Returned(0),
    )
    val sched = makeSchedulerWithBinding(binding)
    val t1 = sched.spawn()
    val t2 = sched.spawn()
    assertEquals(t1.state, TaskState.Queued)
    assertEquals(t2.state, TaskState.Queued)

    sched.runAllReady()
    assertEquals(t1.state, TaskState.Complete)
    assertEquals(t2.state, TaskState.Complete)

  // ── TC-08: Cancelled Task in queue is skipped ─────────────────────────

  test("TC-08 Task cancelled between enqueue and dequeue is skipped by Driver"):
    val binding = TestBinding()
    binding.programResumes(ResumeResult.Yielded(0), ResumeResult.Returned(0))
    val sched = makeSchedulerWithBinding(binding)
    val async = ControllableAsync()

    val task = sched.spawn()
    binding.setPendingSuspendForTest(task.thread, async.suspend)
    sched.runAllReady()
    assertEquals(task.state, TaskState.Parked)

    val r1 = async.resume
    if r1 != null then r1.asInstanceOf[Resume].complete(Right(LuaValue.Nil))
    task.setState(TaskState.Cancelled)
    sched.runAllReady()
    assertEquals(task.state, TaskState.Cancelled)

  // ── TC-09: Suspend comes from Binding, not private slot ────────────────

  test("TC-09 takePendingSuspend comes from the Binding, not a private slot"):
    val b = new TestBinding
    val state = b.newState()
    val sched = Scheduler(b, state, wake = () => ())
    b.programResumes(ResumeResult.Yielded(0))
    val task = sched.spawn()
    var registerRan = false
    b.setPendingSuspendForTest(task.thread, NativeFnResult.Suspend { r =>
      registerRan = true
      r.succeed(LuaValue.Number(1.0))
      Cancel.noop
    })
    sched.runAllReady()
    assert(registerRan)
    assertEquals(task.state, TaskState.Complete)
    b.closeState(state)

  // ── TC-10: Quiescence ──────────────────────────────────────────────────

  test("TC-10 quiescence: empty queue + no pending completions"):
    val b = new TestBinding
    val state = b.newState()
    val sched = Scheduler(b, state, wake = () => ())
    assert(sched.isQuiescent)
    b.programResumes(ResumeResult.Yielded(0))
    val task = sched.spawn()
    assert(!sched.isQuiescent)
    b.setPendingSuspendForTest(task.thread, NativeFnResult.Suspend(_ => Cancel.noop))
    sched.runAllReady()
    assert(sched.isQuiescent == false)
    b.closeState(state)

  // ── TC-11: Bare yield cancel ──────────────────────────────────────────

  test("TC-11 bare-yield park is abandoned: cancelAbandoned reaps it"):
    val b = new TestBinding
    val state = b.newState()
    val sched = Scheduler(b, state, wake = () => ())
    b.programResumes(ResumeResult.Yielded(0))
    val task = sched.spawn()
    sched.runAllReady()
    assertEquals(task.state, TaskState.Parked)
    assert(sched.isQuiescent)
    assertEquals(sched.cancelAbandoned(), 1)
    assertEquals(task.state, TaskState.Cancelled)
    b.closeState(state)

  // ── TC-12: close() does NOT close the state ──────────────────────────

  test("TC-12 close() does NOT close the state (facade owns it)"):
    val b = new TestBinding
    val state = b.newState()
    val sched = Scheduler(b, state, wake = () => ())
    sched.close()
    b.pushNumber(state, 1.0)
    assertEquals(b.toNumber(state, -1), Some(1.0))
    b.closeState(state)

  // ── TC-13: enqueue calls wake ─────────────────────────────────────────

  test("TC-13 enqueue calls wake"):
    val b = new TestBinding
    val state = b.newState()
    var wakes = 0
    val sched = Scheduler(b, state, wake = () => wakes += 1)
    sched.spawn()
    assert(wakes >= 1)
    b.closeState(state)
