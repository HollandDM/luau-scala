package luau.panama

import java.lang.foreign.MemorySegment
import luau.core.*

class SuspendResumeTest extends munit.FunSuite:

  private def withState[A](f: (Binding[MemorySegment], MemorySegment) => A): A =
    val binding = PanamaBinding.instance
    val state = binding.newState()
    try f(binding, state)
    finally binding.closeState(state)

  test("native Suspend returns Yielded and parks in the registry") {
    withState { (b, s) =>
      var registerCalled = false
      b.registerNativeFn(s, (thread, nargs) =>
        NativeFnResult.Suspend { resume =>
          registerCalled = true
          Cancel.noop
        }
      )
      b.setGlobal(s, "waitForIt")
      b.compileAndLoad(
        s, IArray.unsafeFromArray("waitForIt()".getBytes), "test"
      ).left.foreach(e => fail(s"compile failed: ${e.message}"))
      val r1 = b.resume(s, 0)
      assertEquals(r1, ResumeResult.Yielded(0))

      assert(!registerCalled)
      val suspend = b.takePendingSuspend(s)
        .getOrElse(fail("expected a pending Suspend on the thread"))

      var captured: Option[Either[LuaError, LuaValue]] = None
      suspend.register(Resume(result => captured = Some(result)))
      assert(registerCalled)
      assert(b.takePendingSuspend(s).isEmpty)
    }
  }

  test("synchronous resume after Suspend delivers result") {
    withState { (b, s) =>
      var capturedResume: Option[Resume] = None
      b.registerNativeFn(s, (thread, nargs) =>
        NativeFnResult.Suspend { resume =>
          capturedResume = Some(resume)
          Cancel.noop
        }
      )
      b.setGlobal(s, "waitForValue")
      b.compileAndLoad(
        s, IArray.unsafeFromArray(
          "local v = waitForValue(); return v + 1".getBytes
        ), "test"
      ).left.foreach(e => fail(s"compile failed: ${e.message}"))
      val r1 = b.resume(s, 0)
      assertEquals(r1, ResumeResult.Yielded(0))

      val suspend = b.takePendingSuspend(s)
        .getOrElse(fail("expected a pending Suspend on the thread"))
      suspend.register(Resume(_ => ()))
      assert(capturedResume.isDefined)

      b.pushNumber(s, 42.0)
      val r2 = b.resume(s, 1)
      assertEquals(r2, ResumeResult.Returned(1))
      assertEquals(b.toNumber(s, -1), Some(43.0))
    }
  }

  test("resumeError injects the failure at the suspension point") {
    withState { (b, s) =>
      b.openLibs(s, LuauLib.Base)
      b.registerNativeFn(s, (thread, nargs) =>
        NativeFnResult.Suspend { resume =>
          Cancel.noop
        }
      )
      b.setGlobal(s, "waitForIt")
      b.compileAndLoad(
        s, IArray.unsafeFromArray(
          "local ok, err = pcall(waitForIt); return ok, err".getBytes
        ), "test"
      ).left.foreach(e => fail(s"compile failed: ${e.message}"))
      val r1 = b.resume(s, 0)
      assertEquals(r1, ResumeResult.Yielded(0))

      val r2 = b.resumeError(s, LuaError.runtime("test error"))
      assertEquals(r2, ResumeResult.Returned(2))
      assertEquals(b.toBoolean(s, -2), false)
      val errMsg = b.toBytes(s, -1).map(bs =>
        new String(IArray.genericWrapArray(bs).toArray, "UTF-8"))
      assert(errMsg.exists(_.contains("test error")), s"got: $errMsg")
    }
  }
