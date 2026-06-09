package luau.wasm

import luau.core.{Binding, SharedBackendSuite}
import scala.concurrent.Await
import scala.concurrent.duration.*

class WasmBackendSuite extends SharedBackendSuite:

  private var binding: WasmBinding = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    Await.result(WasmBackend.load(), 10.seconds)
    binding = WasmBackend.createBinding()

  override def afterAll(): Unit =
    if binding != null then ()

  override def withBinding[A](f: Binding[Int] => A): A = f(binding)
