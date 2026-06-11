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

  test("TC-WASM-04 one live state per runtime; sequential reuse works"):
    val b = WasmBinding.create()
    val s1 = b.newState()
    intercept[IllegalStateException] { b.newState() }
    b.pushNumber(s1, 1.0)
    assert(b.toNumber(s1, -1).contains(1.0))
    b.pop(s1, 1)
    b.closeState(s1)
    val s2 = b.newState()
    try
      b.pushNumber(s2, 2.0)
      assert(b.toNumber(s2, -1).contains(2.0))
      b.pop(s2, 1)
    finally b.closeState(s2)
