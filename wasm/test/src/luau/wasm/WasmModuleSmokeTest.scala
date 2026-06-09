package luau.wasm

import munit.FunSuite
import scala.concurrent.Await
import scala.concurrent.duration.*

class WasmModuleSmokeTest extends FunSuite:

  test("WASM module loads and lx_newstate creates a state".ignore):
    Await.result(WasmBackend.load(), 10.seconds)
    val L = WasmModule.module._lx_newstate(0)
    assert(L != 0)
    WasmModule.module._lx_close(L)
