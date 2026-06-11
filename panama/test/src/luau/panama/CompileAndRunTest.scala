package luau.panama

import java.lang.foreign.MemorySegment
import luau.core.*

class CompileAndRunTest extends munit.FunSuite:

  private def withState[A](f: (Binding[MemorySegment], MemorySegment) => A): A =
    val binding = PanamaBinding.instance
    val state = binding.newState()
    try f(binding, state)
    finally binding.closeState(state)

  test("compile valid script returns Right(())") {
    withState { (b, s) =>
      val result = b.compileAndLoad(
        s, IArray.unsafeFromArray("return 42".getBytes), "test"
      )
      assert(result.isRight)
    }
  }

  test("compile syntax error returns Left(LuaError)") {
    withState { (b, s) =>
      val result = b.compileAndLoad(
        s, IArray.unsafeFromArray("syntax error !!!".getBytes), "test"
      )
      assert(result.isLeft)
      result.left.foreach { err =>
        assert(err.message.nonEmpty)
      }
    }
  }

  test("resume returns Returned for trivial script") {
    withState { (b, s) =>
      b.compileAndLoad(
        s, IArray.unsafeFromArray("return 42".getBytes), "test"
      ).getOrElse(fail("compile failed"))
      val r = b.resume(s, 0)
      r match
        case ResumeResult.Returned(n) => assert(n >= 1)
        case other => fail(s"expected Returned, got $other")
    }
  }

  test("run 'return 1 + 1' yields integer 2 on stack") {
    withState { (b, s) =>
      b.compileAndLoad(
        s, IArray.unsafeFromArray("return 1 + 1".getBytes), "test"
      ).getOrElse(fail("compile failed"))
      val r = b.resume(s, 0)
      assertEquals(r, ResumeResult.Returned(1))
      assertEquals(b.toNumber(s, -1), Some(2.0))
    }
  }

  test("run multi-line script with local variables") {
    withState { (b, s) =>
      b.compileAndLoad(
        s, IArray.unsafeFromArray(
          "local a = 10; local b = 20; return a + b".getBytes
        ), "test"
      ).getOrElse(fail("compile failed"))
      val r = b.resume(s, 0)
      assertEquals(r, ResumeResult.Returned(1))
      assertEquals(b.toNumber(s, -1), Some(30.0))
    }
  }
