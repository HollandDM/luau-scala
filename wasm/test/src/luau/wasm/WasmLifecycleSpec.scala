package luau.wasm

import munit.FunSuite
import luau.core.*

class WasmLifecycleSpec extends FunSuite:

  override def beforeAll(): Unit = WasmBackend.ensureLoaded()

  private def runState(b: WasmBinding, work: (WasmBinding, Int) => Unit): Unit =
    val s = b.newState()
    try
      b.openLibs(s, LuauLib.Standard)
      b.sandbox(s)
      work(b, s)
    finally b.closeState(s)

  private def basicWork(b: WasmBinding, s: Int): Unit =
    assertEquals(b.compileAndLoad(s, "local t = {1,2,3}; return t[2]", "=bisect"), Right(()))
    b.resume(s, 0) match
      case ResumeResult.Returned(1) => assertEquals(b.toNumber(s, -1), Some(2.0))
      case other                    => fail(s"unexpected: $other")
    b.pop(s, 1)

  test("BISECT-01 two plain sequential states"):
    val b = WasmBinding.create()
    runState(b, basicWork)
    runState(b, basicWork)

  test("BISECT-02 state 1 registers a host fn + takes a ref"):
    val b = WasmBinding.create()
    runState(b, (b, s) =>
      b.registerNativeFn(s, (_, _) => NativeFnResult.Return(0))
      b.pop(s, 1)
      b.pushNumber(s, 7.0)
      val r = b.ref(s)
      r.close()
      basicWork(b, s))
    runState(b, basicWork)

  test("LC-W-01 N sequential states leave Trampoline bookkeeping empty"):
    val b = WasmBinding.create()
    (1 to 5).foreach { _ =>
      runState(b, (b, s) =>
        b.registerNativeFn(s, (_, _) => NativeFnResult.Return(0))
        b.pop(s, 1))
    }
    assertEquals(Trampoline.registeredCount, 0)
    assertEquals(Trampoline.suspendRegistry.size, 0)

  test("BISECT-03 state 1 grows memory (1MB string churn)"):
    val b = WasmBinding.create()
    runState(b, (b, s) =>
      assertEquals(
        b.compileAndLoad(s, "local x = string.rep('a', 1024*1024); return #x", "=grow"),
        Right(()))
      b.resume(s, 0) match
        case ResumeResult.Returned(1) => assertEquals(b.toNumber(s, -1), Some(1048576.0))
        case other                    => fail(s"unexpected: $other"))
    runState(b, basicWork)
