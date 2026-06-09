package luau.scheduler

import luau.core.*

class SchedulerTests extends munit.FunSuite:

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

    sched.setPendingSuspend(async.suspend)
    val task = sched.spawn()
    sched.runAllReady()
    assertEquals(task.state, TaskState.Parked)
    assert(async.resume != null, "register() was called")

    async.resume(Right(LuaValue.Nil))
    assertEquals(task.state, TaskState.Queued)
    sched.runAllReady()
    assertEquals(task.state, TaskState.Complete)

  // ── TC-03: Resume is one-shot ─────────────────────────────────────────

  test("TC-03 double resume is a no-op (does not enqueue twice)"):
    val binding = TestBinding()
    binding.programResumes(ResumeResult.Yielded(0), ResumeResult.Returned(0))
    val sched = makeSchedulerWithBinding(binding)
    val async = ControllableAsync()

    sched.setPendingSuspend(async.suspend)
    sched.spawn()
    sched.runAllReady()

    val r = async.resume
    assert(r != null, "resume was captured")
    r(Right(LuaValue.Nil))
    r(Right(LuaValue.Nil))

    assertEquals(sched.runAllReady(), 1)

  // ── TC-04: Error status → Failed state → error policy ─────────────────

  test("TC-04 lx_resume error transitions Task to Failed and invokes error policy"):
    var capturedError: Option[String] = None
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
    assertEquals(capturedError, Some("script error"))

  // ── TC-05: Close cancels parked Tasks ─────────────────────────────────

  test("TC-05 close() fires Cancel for parked Tasks"):
    val binding = TestBinding()
    binding.programResumes(ResumeResult.Yielded(0))
    val sched = makeSchedulerWithBinding(binding)
    val async = ControllableAsync()

    sched.setPendingSuspend(async.suspend)
    val task = sched.spawn()
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

    sched.setPendingSuspend(async.suspend)
    val task = sched.spawn()
    sched.runAllReady()
    assertEquals(task.state, TaskState.Parked)

    async.resume(Right(LuaValue.Nil))
    task.setState(TaskState.Cancelled)
    sched.runAllReady()
    assertEquals(task.state, TaskState.Cancelled)
