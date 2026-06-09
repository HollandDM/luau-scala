package luau.wasm

import luau.core.{Binding, SharedBackendSuite}

class WasmBackendSuite extends SharedBackendSuite:

  private var binding: WasmBinding = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    WasmBackend.load()
    binding = WasmBackend.createBinding()

  override def afterAll(): Unit =
    if binding != null then ()

  override def withBinding[A](f: Binding[Int] => A): A = f(binding)
