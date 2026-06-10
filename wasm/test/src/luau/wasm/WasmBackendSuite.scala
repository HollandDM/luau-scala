package luau.wasm

import luau.core.{Binding, SharedBackendSuite}

class WasmBackendSuite extends SharedBackendSuite[Int]:

  // Each test gets a brand-new wasm instance. The backend shares one wasm module
  // across all states; a state's create/teardown leaves the shared heap/registry
  // dirty enough to crash a later test (lua_rawgeti aborts). Reload per test.
  override def withBinding[A](f: Binding[Int] => A): A =
    WasmBackend.load()
    f(WasmBackend.createBinding())
