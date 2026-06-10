package luau.panama

import luau.core.*

/** Exercises the Suspend contract end to end, host-driven (no Scheduler):
  * the dispatcher parks the Suspend in the SuspendRegistry under a token;
  * the Host consumes the token after Yielded, wires `register` against its
  * own Resume callback, then completes the Suspension by either pushing the
  * result and resuming, or injecting the error via resumeError.
  */
class SuspendResumeTest extends munit.FunSuite:

  test("native Suspend returns Yielded and parks in the registry") {
    PanamaState.use { ps =>
      var registerCalled = false
      ps.registerNativeFn(ps.L, (thread, nargs) =>
        NativeFnResult.Suspend { resume =>
          registerCalled = true
          Cancel.noop
        }
      )
      ps.setGlobal(ps.L, "waitForIt")
      ps.compileAndLoad(
        ps.L, IArray.unsafeFromArray("waitForIt()".getBytes), "test"
      ).left.foreach(e => fail(s"compile failed: ${e.message}"))
      val r1 = ps.resume(ps.L, 0)
      assertEquals(r1, ResumeResult.Yielded(0))

      // The dispatcher must not run register itself — wiring belongs to the
      // Host (Scheduler in production). It parks the Suspend under a token.
      assert(!registerCalled)
      val token = ps.lastYieldToken
      assert(token > 0L)
      val suspended = ps.suspendRegistry.consume(token)
      assert(suspended.isDefined)

      // Host wires the async op: register receives the Host's Resume.
      var captured: Option[Either[LuaError, LuaValue]] = None
      suspended.get.register(Resume(result => captured = Some(result)))
      assert(registerCalled)

      // A consumed token is gone — no double-wiring.
      assert(ps.suspendRegistry.consume(token).isEmpty)
    }
  }

  test("synchronous resume after Suspend delivers result") {
    PanamaState.use { ps =>
      var capturedResume: Option[Resume] = None
      ps.registerNativeFn(ps.L, (thread, nargs) =>
        NativeFnResult.Suspend { resume =>
          capturedResume = Some(resume)
          Cancel.noop
        }
      )
      ps.setGlobal(ps.L, "waitForValue")
      ps.compileAndLoad(
        ps.L, IArray.unsafeFromArray(
          "local v = waitForValue(); return v + 1".getBytes
        ), "test"
      ).left.foreach(e => fail(s"compile failed: ${e.message}"))
      val r1 = ps.resume(ps.L, 0)
      assertEquals(r1, ResumeResult.Yielded(0))

      val token = ps.lastYieldToken
      assert(token > 0L)
      val suspended = ps.suspendRegistry.consume(token)
      assert(suspended.isDefined)
      suspended.get.register(Resume(_ => ()))
      assert(capturedResume.isDefined)

      // Host completes: push the value onto the thread, resume with 1 arg.
      ps.pushNumber(ps.L, 42.0)
      val r2 = ps.resume(ps.L, 1)
      assertEquals(r2, ResumeResult.Returned(1))
      assertEquals(ps.toNumber(ps.L, -1), Some(43.0))
    }
  }

  test("resumeError injects the failure at the suspension point") {
    PanamaState.use { ps =>
      ps.openLibs(ps.L, 1) // LX_LIB_BASE — the script needs pcall
      ps.registerNativeFn(ps.L, (thread, nargs) =>
        NativeFnResult.Suspend { resume =>
          Cancel.noop
        }
      )
      ps.setGlobal(ps.L, "waitForIt")
      ps.compileAndLoad(
        ps.L, IArray.unsafeFromArray(
          "local ok, err = pcall(waitForIt); return ok, err".getBytes
        ), "test"
      ).left.foreach(e => fail(s"compile failed: ${e.message}"))
      val r1 = ps.resume(ps.L, 0)
      assertEquals(r1, ResumeResult.Yielded(0))

      // Host fails the Suspension: the script's pcall observes the error.
      val r2 = ps.resumeError(ps.L, LuaError.runtime("test error"))
      assertEquals(r2, ResumeResult.Returned(2))
      assertEquals(ps.toBoolean(ps.L, -2), false)
      val errMsg = ps.toBytes(ps.L, -1).map(bs =>
        new String(IArray.genericWrapArray(bs).toArray, "UTF-8"))
      assert(errMsg.exists(_.contains("test error")), s"got: $errMsg")
    }
  }
