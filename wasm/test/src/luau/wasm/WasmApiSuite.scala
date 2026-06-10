package luau.wasm

import luau.api.ApiSuite
import luau.core.Binding

class WasmApiSuite extends ApiSuite[Int]:

  // Fresh wasm instance per test — see WasmBackendSuite for why.
  override def withBinding[A](f: Binding[Int] => A): A =
    WasmBackend.load()
    f(WasmBackend.createBinding())
