package luau.panama

import java.lang.foreign.MemorySegment
import luau.core.*

class CompileAndRunTest extends munit.FunSuite:

  test("compile valid script returns Right(())") {
    PanamaState.use { ps =>
      val result = ps.compileAndLoad(
        ps.L, IArray.unsafeFromArray("return 42".getBytes), "test"
      )
      assert(result.isRight)
    }
  }

  test("compile syntax error returns Left(LuaError)") {
    PanamaState.use { ps =>
      val result = ps.compileAndLoad(
        ps.L, IArray.unsafeFromArray("syntax error !!!".getBytes), "test"
      )
      assert(result.isLeft)
      result.left.foreach { err =>
        assert(err.message.nonEmpty)
      }
    }
  }

  test("resume returns Returned for trivial script") {
    PanamaState.use { ps =>
      ps.compileAndLoad(
        ps.L, IArray.unsafeFromArray("return 42".getBytes), "test"
      ).getOrElse(fail("compile failed"))
      val r = ps.resume(ps.L, 0)
      r match
        case ResumeResult.Returned(n) => assert(n >= 1)
        case other => fail(s"expected Returned, got $other")
    }
  }

  test("run 'return 1 + 1' yields integer 2 on stack") {
    PanamaState.use { ps =>
      ps.compileAndLoad(
        ps.L, IArray.unsafeFromArray("return 1 + 1".getBytes), "test"
      ).getOrElse(fail("compile failed"))
      val r = ps.resume(ps.L, 0)
      assertEquals(r, ResumeResult.Returned(1))
      assertEquals(ps.toNumber(ps.L, -1), Some(2.0))
    }
  }

  test("run multi-line script with local variables") {
    PanamaState.use { ps =>
      ps.compileAndLoad(
        ps.L, IArray.unsafeFromArray(
          "local a = 10; local b = 20; return a + b".getBytes
        ), "test"
      ).getOrElse(fail("compile failed"))
      val r = ps.resume(ps.L, 0)
      assertEquals(r, ResumeResult.Returned(1))
      assertEquals(ps.toNumber(ps.L, -1), Some(30.0))
    }
  }
