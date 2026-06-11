package luau.wasm

import luau.core.{Binding, SharedBackendSuite}

class WasmBackendSuite extends SharedBackendSuite[Int]:

  override def withBinding[A](f: Binding[Int] => A): A =
    f(WasmBackend.createBinding())
