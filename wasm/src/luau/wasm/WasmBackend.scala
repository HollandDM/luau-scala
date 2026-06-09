package luau.wasm

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js.JSConverters.*

object WasmBackend:

  def load(loaderPath: String = "./luau-shim.js"): Future[Unit] =
    LuauShimFactory().toFuture.map { exports =>
      WasmModule.set(exports)
      Trampoline.install()
      ()
    }

  def createBinding(): WasmBinding = WasmBinding.create()
