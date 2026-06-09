package luau.wasm

import munit.FunSuite

class WasmModuleSmokeTest extends FunSuite:

  override def beforeAll(): Unit =
    WasmBackend.load()

  test("WASM module loads and lx_newstate creates a state"):
    val L = WasmModule.module._lx_newstate(0)
    assert(L != 0)
    WasmModule.module._lx_close(L)
