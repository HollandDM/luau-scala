package luau.wasm

import munit.FunSuite

class WasmSpecificSuite extends FunSuite:

  override def beforeAll(): Unit =
    WasmBackend.load()

  test("TC-WASM-01 WasmBackend.load() resolves"):
    assert(WasmModule.module != null)

  test("TC-WASM-02 _malloc and _free do not crash"):
    val m = WasmModule.module
    val ptr = m._malloc(128)
    assert(ptr != 0)
    m.HEAPU8(ptr) = 0xAB.toShort
    assert(m.HEAPU8(ptr) == 0xAB.toShort)
    m._free(ptr)

  test("TC-WASM-03 addFunction registers and dynCall works"):
    val m = WasmModule.module
    var wasCalled = false
    val fn: scala.scalajs.js.Function5[Int, Int, Int, Int, Int, Int] =
      (_, _, _, _, _) => wasCalled = true; 0
    val idx = m.addFunction(fn, "iiiiii")
    assert(idx > 0)
    val result = m.dynCall_iiiiii(idx, 0, 0, 0, 0, 0)
    assert(wasCalled)
    assertEquals(result, 0)
    m.removeFunction(idx)

  test("TC-WASM-04 two WasmBindings have independent states"):
    val b1 = WasmBinding.create()
    val b2 = WasmBinding.create()
    val s1 = b1.newState()
    val s2 = b2.newState()
    try
      b1.pushNumber(s1, 1.0)
      b2.pushNumber(s2, 2.0)
      assert(b1.toNumber(s1, -1).contains(1.0))
      assert(b2.toNumber(s2, -1).contains(2.0))
      b1.pop(s1, 1)
      b2.pop(s2, 1)
    finally
      b1.closeState(s1)
      b2.closeState(s2)
