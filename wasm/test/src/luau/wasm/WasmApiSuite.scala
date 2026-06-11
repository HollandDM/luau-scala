package luau.wasm

import luau.api.ApiSuite
import luau.core.Binding

class WasmApiSuite extends ApiSuite[Int]:

  override def withBinding[A](f: Binding[Int] => A): A =
    f(WasmBackend.createBinding())
