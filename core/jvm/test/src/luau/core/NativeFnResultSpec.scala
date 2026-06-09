package luau.core

import luau.core.fake.*
import munit.FunSuite

class NativeFnResultSpec extends FunSuite:

  test("NativeFnResult.Return holds nResults") {
    val r = NativeFnResult.Return(3)
    assertEquals(r, NativeFnResult.Return(3))
  }

  test("NativeFnResult.Fail holds value") {
    val v = LuaValue.LuaString.fromUtf8("oops")
    val f = NativeFnResult.Fail(v)
    assertEquals(f, NativeFnResult.Fail(v))
  }

  test("NativeFnResult.Suspend register is called") {
    var registered = false
    var cancelled  = false
    val s = NativeFnResult.Suspend { resume =>
      registered = true
      resume.succeed(LuaValue.Nil)
      Cancel(() => { cancelled = true })
    }
    val register = s match { case NativeFnResult.Suspend(r) => r; case _ => ??? }
    var result: Either[LuaError, LuaValue] = Left(LuaError.runtime("not called"))
    val cancel = register(Resume(r => result = r))
    assert(registered)
    assert(result.isRight)
    cancel.cancel()
    assert(cancelled)
  }

  test("Resume.succeed produces Right(value)") {
    var got: Either[LuaError, LuaValue] = Left(LuaError.runtime("not called"))
    val resume = Resume(r => got = r)
    resume.succeed(LuaValue.Number(42.0))
    assert(got == Right(LuaValue.Number(42.0)))
  }

  test("Resume.fail produces Left(LuaError)") {
    var got: Either[LuaError, LuaValue] = Left(LuaError.runtime("not called"))
    val resume = Resume(r => got = r)
    resume.fail(LuaError.runtime("test error"))
    assert(got.isLeft)
  }
