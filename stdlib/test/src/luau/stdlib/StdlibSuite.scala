package luau.stdlib

import luau.core.*
import luau.core.fake.*
import luau.scheduler.*

class StdlibSuite extends munit.FunSuite:

  /** Every test gets a fresh state + scheduler and MUST tear both down:
    * the scheduler owns a default TaskTimer (close() shuts it down) and
    * FakeBinding's live-state slot is process-global — a leaked state fails
    * the next test's newState with IllegalStateException.
    */
  private def withSched[B <: Binding[FakeState]](binding: B)(
    f: (B, FakeState, Scheduler[FakeState]) => Unit
  ): Unit =
    val state = binding.newState()
    val sched = Scheduler(binding, state)
    try f(binding, state, sched)
    finally
      sched.close()
      binding.closeState(state)

  private def pinNil(binding: Binding[FakeState], state: FakeState): Ref[FakeState] =
    binding.pushNil(state)
    binding.ref(state)

  // ── StdlibOpener: call order ──────────────────────────────────────────

  test("StdlibOpener.open calls openLibs then sandbox"):
    withSched(new CallOrderBinding()) { (binding, state, sched) =>
      StdlibOpener.open(binding, state, sched, Set(LuauLib.Base))

      val order = binding.callOrder.toList
      assert(order.contains("openLibs"), "openLibs was not called")
      assert(order.contains("sandbox"), "sandbox was not called")
      val oi = order.indexOf("openLibs")
      val si = order.indexOf("sandbox")
      assert(oi >= 0 && si >= 0 && oi < si,
        s"openLibs ($oi) must be called before sandbox ($si)")
    }

  // ── Scheduler: spawnImmediate ─────────────────────────────────────────

  test("Scheduler.spawnImmediate creates Task that completes or yields"):
    val binding = TestBinding()
    binding.programResumes(ResumeResult.Returned(0))
    withSched(binding) { (binding, state, sched) =>
      val handle = sched.spawnImmediate(pinNil(binding, state), Nil)
      assert(handle.task.state == TaskState.Complete || handle.task.state == TaskState.Parked)
      // terminal-immediate tasks leave the threadRef to the caller (TaskLibrary contract)
      if handle.isDone then handle.threadRef.close()
    }

  test("Scheduler.deferTask creates Task in Queued state"):
    withSched(TestBinding()) { (binding, state, sched) =>
      val handle = sched.deferTask(pinNil(binding, state), Nil)
      assertEquals(handle.task.state, TaskState.Queued)
    }

  test("Scheduler.scheduleDelayed creates Task in Parked state"):
    withSched(TestBinding()) { (binding, state, sched) =>
      val handle = sched.scheduleDelayed(pinNil(binding, state), Nil, 10.0)
      assertEquals(handle.task.state, TaskState.Parked)
    }

  test("Scheduler.cancelTask removes a Parked task"):
    withSched(TestBinding()) { (binding, state, sched) =>
      val handle = sched.scheduleDelayed(pinNil(binding, state), Nil, 10.0)
      assertEquals(handle.task.state, TaskState.Parked)
      sched.cancelTask(handle.task)
      assertEquals(handle.task.state, TaskState.Cancelled)
    }

  test("Scheduler.cancelTask on completed task is no-op"):
    val binding = TestBinding()
    binding.programResumes(ResumeResult.Returned(0))
    withSched(binding) { (binding, state, sched) =>
      val handle = sched.spawnImmediate(pinNil(binding, state), Nil)
      assertEquals(handle.task.state, TaskState.Complete)
      sched.cancelTask(handle.task)
      assertEquals(handle.task.state, TaskState.Complete)
      handle.threadRef.close()
    }

  test("Scheduler.currentTask is None outside resume"):
    withSched(TestBinding()) { (_, _, sched) =>
      assertEquals(sched.currentTask, None)
    }

  test("Scheduler.enqueueResume creates Queued task"):
    withSched(TestBinding()) { (_, _, sched) =>
      val task = sched.spawn()
      assertEquals(task.state, TaskState.Queued)
      sched.enqueueResume(task, Right(LuaValue.Number(42.0)))
    }

  test("Scheduler.cancelThreadHandle cancels task by thread handle"):
    withSched(TestBinding()) { (binding, state, sched) =>
      val handle = sched.scheduleDelayed(pinNil(binding, state), Nil, 10.0)
      assert(handle.task.state == TaskState.Parked)
      sched.cancelThreadHandle(handle.task.thread)
      assertEquals(handle.task.state, TaskState.Cancelled)
    }

  // ── Task library installation ─────────────────────────────────────────

  test("TaskLibrary.install creates task global table"):
    withSched(new CallOrderBinding()) { (binding, state, sched) =>
      TaskLibrary.install(binding, state, sched)
      binding.getGlobal(state, "task")
      assertEquals(binding.typeAt(state, -1), LuaType.Table)
    }

/** TestBinding that records call order for openLibs/sandbox. */
private class CallOrderBinding extends TestBinding:
  val callOrder = scala.collection.mutable.ArrayBuffer[String]()

  override def openLibs(state: FakeState, libs: Set[LuauLib]): Unit =
    callOrder += "openLibs"
    super.openLibs(state, libs)

  override def sandbox(state: FakeState): Unit =
    callOrder += "sandbox"
    super.sandbox(state)
