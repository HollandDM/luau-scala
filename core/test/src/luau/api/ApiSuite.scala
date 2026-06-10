package luau.api

import munit.FunSuite
import luau.core.*
import scala.util.{Failure, Success}

/** Facade conformance: runs against both real backends (Panama, wasm) via
  * platform subclasses, mirroring SharedBackendSuite.
  *
  * This file is NOT capture-checked: an abstract suite with an impure
  * `withBinding` member gets self type `ApiSuite^`, which munit's
  * BaseFunSuite self type rejects. Enforcement lives in the luau.api
  * sources; escape cases (returning a LuaFn^{s} out of useRef, a LuaState^
  * out of withState) are cc compile errors — verified manually, since the cc
  * phase runs after typer and munit's compileErrors cannot observe it (see
  * CcCompileSpec: typer-level negatives are asserted there, and the cc
  * blind spot is pinned). Recorded rejections (Scala 3.8.3):
  *
  *   Luau.withState(b)(st => st)
  *     Capability `st` outlives its scope: it leaks into outer capture set
  *
  *   st.useRef { leaked = Some(st.evalFn("...").get) }   // leaked: outer var
  *     Found: Some[LuaFn[H]^{s}]  Required: Option[LuaFn[H]]
  *     capability `s` cannot flow into capture set {}
  *
  * Note the second fires through Try[...].get + Some(...): enforcement
  * survives wrapping handles in stdlib containers.
  */
abstract class ApiSuite[H] extends FunSuite:

  def withBinding[A](f: Binding[H] => A): A

  private def withLuau[A](libs: Set[LuauLib] = LuauLib.Standard)(f: LuaState[H] => A): A =
    withBinding(b => Luau.withState(b, libs)(f))

  // ---- Value plane -------------------------------------------------------

  test("TC-API-01 eval decodes a number"):
    withLuau() { st =>
      assertEquals(st.eval[Double]("return 42"), Success(42.0))
    }

  test("TC-API-02 eval decodes a string"):
    withLuau() { st =>
      assertEquals(st.eval[String]("return 'héllo'"), Success("héllo"))
    }

  test("TC-API-03 eval copies a table out as Map"):
    withLuau() { st =>
      assertEquals(
        st.eval[Map[String, Double]]("return { alpha = 1, beta = 2 }"),
        Success(Map("alpha" -> 1.0, "beta" -> 2.0)),
      )
    }

  test("TC-API-04 eval copies an array table out as Seq"):
    withLuau() { st =>
      assertEquals(st.eval[Seq[Double]]("return { 10, 20, 30 }"), Success(Seq(10.0, 20.0, 30.0)))
    }

  test("TC-API-05 compile error surfaces as Failure"):
    withLuau() { st =>
      assert(st.eval[Double]("this is not luau @@@").isFailure)
    }

  test("TC-API-06 runtime error surfaces as Failure"):
    withLuau() { st =>
      assert(st.eval[Double]("error('boom')").isFailure)
    }

  test("TC-API-07 eval of reference data fails (function is not copyable)"):
    withLuau() { st =>
      assert(st.eval[Double]("return function() return 1 end").isFailure)
    }

  test("TC-API-08 setGlobal then eval reads it back"):
    withLuau() { st =>
      st.setGlobal("answer", 21.0)
      assertEquals(st.eval[Double]("return answer * 2"), Success(42.0))
    }

  test("TC-API-09 run + global round-trips a value"):
    withLuau() { st =>
      assertEquals(st.run("greeting = 'hi'"), Success(()))
      assertEquals(st.global[String]("greeting"), Success("hi"))
    }

  test("TC-API-10 absent global decodes as None"):
    withLuau() { st =>
      assertEquals(st.global[Option[Double]]("nonexistent"), Success(None))
    }

  test("TC-API-11 stack stays balanced across calls, success and failure"):
    withLuau() { st =>
      st.eval[Double]("return 1")
      st.eval[Double]("return 'not a number'")
      st.eval[Double]("error('x')")
      st.global[Double]("nope")
      st.setGlobal("k", 1.0)
      assertEquals(st.binding.stackTop(st.state), 0)
    }

  // ---- Host functions ----------------------------------------------------

  test("TC-API-12 defineGlobal arity 2 is callable from script"):
    withLuau() { st =>
      st.defineGlobal("hostAdd")((a: Double, b: Double) => a + b)
      assertEquals(st.eval[Double]("return hostAdd(10, 32)"), Success(42.0))
    }

  test("TC-API-13 defineGlobal arity 0 and 1"):
    withLuau() { st =>
      st.defineGlobal("hostConst")(() => 7.0)
      st.defineGlobal("hostDouble")((x: Double) => x * 2)
      assertEquals(st.eval[Double]("return hostDouble(hostConst())"), Success(14.0))
    }

  test("TC-API-14 host fn returning a table copies it in"):
    withLuau() { st =>
      st.defineGlobal("hostMap")(() => Map("x" -> 1.0))
      assertEquals(st.eval[Double]("return hostMap().x"), Success(1.0))
    }

  test("TC-API-15 host fn arg decode failure raises a Lua error"):
    withLuau() { st =>
      st.defineGlobal("strict")((x: Double) => x)
      val r = st.eval[Boolean]("local ok = pcall(strict, 'oops'); return ok")
      assertEquals(r, Success(false))
    }

  test("TC-API-16 host fn wrong arity raises a Lua error"):
    withLuau() { st =>
      st.defineGlobal("two")((a: Double, b: Double) => a + b)
      assertEquals(st.eval[Boolean]("local ok = pcall(two, 1); return ok"), Success(false))
    }

  // ---- Handle plane ------------------------------------------------------

  test("TC-API-17 evalFn mints a callable handle"):
    withLuau() { st =>
      st.useRef {
        val fn = st.evalFn("return function(x) return x * 2 end").get
        assertEquals(fn.call[Double](LuaArg(21.0)), Success(42.0))
      }
    }

  test("TC-API-18 globalFn + multiple calls reuse one pin"):
    withLuau() { st =>
      st.run("function inc(x) return x + 1 end").get
      st.useRef {
        val fn = st.globalFn("inc").get
        assertEquals(fn.call[Double](LuaArg(1.0)), Success(2.0))
        assertEquals(fn.call[Double](LuaArg(41.0)), Success(42.0))
      }
    }

  test("TC-API-19 globalTbl get and set hit the live table"):
    withLuau() { st =>
      st.run("config = { debug = false }").get
      st.useRef {
        val tbl = st.globalTbl("config").get
        assertEquals(tbl.get[Boolean]("debug"), Success(false))
        tbl.set("debug", true)
        tbl.set("level", 3.0)
      }
      assertEquals(st.eval[Boolean]("return config.debug"), Success(true))
      assertEquals(st.eval[Double]("return config.level"), Success(3.0))
    }

  test("TC-API-20 mint type mismatch fails"):
    withLuau() { st =>
      st.run("notAFn = 5").get
      st.useRef {
        assert(st.globalFn("notAFn").isFailure)
        assert(st.globalTbl("notAFn").isFailure)
      }
    }

  test("TC-API-21 scope exit closes every pin"):
    withLuau() { st =>
      var fnRef: Ref[H]  = null
      var tblRef: Ref[H] = null
      st.useRef {
        st.run("t = {}").get
        fnRef = st.evalFn("return function() return 1 end").get.ref
        tblRef = st.globalTbl("t").get.ref
        assert(!fnRef.isClosed && !tblRef.isClosed)
      }
      assert(fnRef.isClosed && tblRef.isClosed)
      assertEquals(st.binding.stackTop(st.state), 0)
    }

  test("TC-API-22 fn.call result decode mismatch fails"):
    withLuau() { st =>
      st.useRef {
        val fn = st.evalFn("return function() return 'str' end").get
        assert(fn.call[Double]().isFailure)
      }
    }

  test("TC-API-23 yielding function rejected by call"):
    withLuau() { st =>
      st.useRef {
        val fn = st.evalFn("return function() coroutine.yield() end").get
        assert(fn.call[Unit]().isFailure)
      }
    }

  test("TC-API-24 coro drives yield then done"):
    withLuau() { st =>
      st.useRef {
        val fn = st.evalFn(
          "return function(a) local b = coroutine.yield(a + 1) return b * 2 end"
        ).get
        val co = st.coro(fn)
        assertEquals(co.resume[Double](LuaArg(1.0)), Success(CoroStep.Yielded(2.0)))
        assertEquals(co.resume[Double](LuaArg(21.0)), Success(CoroStep.Done(42.0)))
      }
    }

  test("TC-API-25 coro with no yield value decodes Unit"):
    withLuau() { st =>
      st.useRef {
        val fn = st.evalFn("return function() coroutine.yield() return 1 end").get
        val co = st.coro(fn)
        assertEquals(co.resume[Unit](), Success(CoroStep.Yielded(())))
        assertEquals(co.resume[Double](), Success(CoroStep.Done(1.0)))
      }
    }
