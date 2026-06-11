package luau.panama

import java.lang.foreign.MemorySegment
import luau.core.*

class NativeFunctionTest extends munit.FunSuite:

  private def withState[A](f: (Binding[MemorySegment], MemorySegment) => A): A =
    val binding = PanamaBinding.instance
    val state = binding.newState()
    try f(binding, state)
    finally binding.closeState(state)

  test("native function Return(1) — script receives correct value") {
    withState { (b, s) =>
      b.registerNativeFn(s, (thread, nargs) =>
        val n = b.toNumber(thread, -1).getOrElse(0.0)
        b.pushNumber(thread, n + 1)
        NativeFnResult.Return(1)
      )
      b.setGlobal(s, "addOne")
      b.compileAndLoad(
        s, IArray.unsafeFromArray("return addOne(41)".getBytes), "test"
      ).getOrElse(fail("compile failed"))
      val r = b.resume(s, 0)
      assertEquals(r, ResumeResult.Returned(1))
      assertEquals(b.toNumber(s, -1), Some(42.0))
    }
  }

  test("native function Fail — script sees error") {
    withState { (b, s) =>
      b.openLibs(s, LuauLib.Base)
      b.registerNativeFn(s, (thread, nargs) =>
        b.pushString(thread, "boom")
        NativeFnResult.Fail
      )
      b.setGlobal(s, "throwErr")
      b.compileAndLoad(
        s, IArray.unsafeFromArray(
          "local ok, err = pcall(throwErr); return ok".getBytes
        ), "test"
      ).getOrElse(fail("compile failed"))
      val r = b.resume(s, 0)
      assertEquals(r, ResumeResult.Returned(1))
      assertEquals(b.toBoolean(s, -1), false)
    }
  }

  test("multiple native functions coexist by fnId") {
    withState { (b, s) =>
      b.registerNativeFn(s, (thread, nargs) =>
        b.pushNumber(thread, 1.0)
        NativeFnResult.Return(1)
      )
      b.setGlobal(s, "fnOne")
      b.registerNativeFn(s, (thread, nargs) =>
        b.pushNumber(thread, 2.0)
        NativeFnResult.Return(1)
      )
      b.setGlobal(s, "fnTwo")
      b.compileAndLoad(
        s, IArray.unsafeFromArray(
          "return fnOne() + fnTwo()".getBytes
        ), "test"
      ).getOrElse(fail("compile failed"))
      val r = b.resume(s, 0)
      assertEquals(r, ResumeResult.Returned(1))
      assertEquals(b.toNumber(s, -1), Some(3.0))
    }
  }
