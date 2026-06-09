package luau.panama

import java.lang.foreign.MemorySegment
import luau.core.*

class NativeFunctionTest extends munit.FunSuite:

  test("native function Return(1) — script receives correct value".ignore) {
    PanamaState.use { ps =>
      ps.registerNativeFn(ps.L, (thread, nargs) =>
        val n = ps.toNumber(thread, -1).getOrElse(0.0)
        ps.pushNumber(thread, n + 1)
        NativeFnResult.Return(1)
      )
      ps.setGlobal(ps.L, "addOne")
      ps.compileAndLoad(
        ps.L, IArray.unsafeFromArray("return addOne(41)".getBytes), "test"
      ).getOrElse(fail("compile failed"))
      val r = ps.resume(ps.L, 0)
      assertEquals(r, ResumeResult.Returned(1))
      assertEquals(ps.toNumber(ps.L, -1), Some(42.0))
    }
  }

  test("native function Fail — script sees error".ignore) {
    PanamaState.use { ps =>
      ps.registerNativeFn(ps.L, (thread, nargs) =>
        ps.pushString(thread, "boom")
        NativeFnResult.Fail(LuaValue.Nil)
      )
      ps.setGlobal(ps.L, "throwErr")
      ps.compileAndLoad(
        ps.L, IArray.unsafeFromArray(
          "local ok, err = pcall(throwErr); return ok".getBytes
        ), "test"
      ).getOrElse(fail("compile failed"))
      val r = ps.resume(ps.L, 0)
      assertEquals(r, ResumeResult.Returned(1))
      assertEquals(ps.toBoolean(ps.L, -1), false)
    }
  }

  test("multiple native functions coexist by fnId".ignore) {
    PanamaState.use { ps =>
      ps.registerNativeFn(ps.L, (thread, nargs) =>
        ps.pushNumber(thread, 1.0)
        NativeFnResult.Return(1)
      )
      ps.setGlobal(ps.L, "fnOne")
      ps.registerNativeFn(ps.L, (thread, nargs) =>
        ps.pushNumber(thread, 2.0)
        NativeFnResult.Return(1)
      )
      ps.setGlobal(ps.L, "fnTwo")
      ps.compileAndLoad(
        ps.L, IArray.unsafeFromArray(
          "return fnOne() + fnTwo()".getBytes
        ), "test"
      ).getOrElse(fail("compile failed"))
      val r = ps.resume(ps.L, 0)
      assertEquals(r, ResumeResult.Returned(1))
      assertEquals(ps.toNumber(ps.L, -1), Some(3.0))
    }
  }
