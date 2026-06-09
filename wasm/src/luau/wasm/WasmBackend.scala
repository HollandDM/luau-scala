package luau.wasm

import scala.scalajs.js

object WasmBackend:

  def load(loaderPath: String = "./luau-shim.js"): Unit =
    val exports = LuauShimFactory(js.Dynamic.literal())
    WasmModule.set(exports)
    Trampoline.install()

  def createBinding(): WasmBinding = WasmBinding.create()
