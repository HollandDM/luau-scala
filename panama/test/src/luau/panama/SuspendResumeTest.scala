package luau.panama

import luau.core.*

class SuspendResumeTest extends munit.FunSuite:

  test("native Suspend returns Yielded from resume()".ignore) {
    PanamaState.use { ps =>
      var capturedResume: Option[Resume] = None
      ps.registerNativeFn(ps.L, (thread, nargs) =>
        NativeFnResult.Suspend { resume =>
          capturedResume = Some(resume)
          Cancel.noop
        }
      )
      ps.setGlobal(ps.L, "waitForIt")
      ps.compileAndLoad(
        ps.L, IArray.unsafeFromArray("waitForIt()".getBytes), "test"
      ).getOrElse(fail("compile failed"))
      val r1 = ps.resume(ps.L, 0)
      assertEquals(r1, ResumeResult.Yielded(0))
      assert(capturedResume.isDefined)
    }
  }

  test("synchronous resume after Suspend delivers result".ignore) {
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
      ).getOrElse(fail("compile failed"))
      val r1 = ps.resume(ps.L, 0)
      assertEquals(r1, ResumeResult.Yielded(0))
      assert(capturedResume.isDefined)
      val token = ps.lastYieldToken
      assert(token > 0L)
      val consumed = ps.suspendRegistry.consume(token)
      assert(consumed.isDefined)
      ps.pushNumber(ps.L, 42.0)
      capturedResume.get.succeed(LuaValue.Number(42.0))
      val r2 = ps.resume(ps.L, 1)
      assertEquals(r2, ResumeResult.Returned(1))
      assertEquals(ps.toNumber(ps.L, -1), Some(43.0))
    }
  }

  test("calling Resume with Left(LuaError) propagates error".ignore) {
    PanamaState.use { ps =>
      var capturedResume: Option[Resume] = None
      ps.registerNativeFn(ps.L, (thread, nargs) =>
        NativeFnResult.Suspend { resume =>
          capturedResume = Some(resume)
          Cancel.noop
        }
      )
      ps.setGlobal(ps.L, "waitForIt")
      ps.compileAndLoad(
        ps.L, IArray.unsafeFromArray(
          "local ok, err = pcall(waitForIt); return ok".getBytes
        ), "test"
      ).getOrElse(fail("compile failed"))
      val r1 = ps.resume(ps.L, 0)
      assertEquals(r1, ResumeResult.Yielded(0))
      capturedResume.get.fail(LuaError.runtime("test error"))
      val r2 = ps.resume(ps.L, 1)
      assertEquals(r2, ResumeResult.Returned(1))
      assertEquals(ps.toBoolean(ps.L, -1), false)
    }
  }
