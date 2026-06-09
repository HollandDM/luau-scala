package luau.wasm

import scala.scalajs.js

object WasmBackend:

  def load(loaderPath: String = "./luau-shim.js"): Unit =
    val exports = LuauShimFactory(js.Dynamic.literal())
    WasmModule.set(exports)
    // Each load is a brand-new wasm instance (own table + linear memory). Reset
    // the global Trampoline so install() re-grows the new instance's table
    // rather than reusing a stale fnPtr from a prior instance.
    Trampoline.reset()
    Trampoline.install()

  def createBinding(): WasmBinding = WasmBinding.create()
