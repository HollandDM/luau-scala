package luau.wasm

import scala.scalajs.js

object LuauWasmLoader:

  def load(): Unit =
    val exports = LuauShimFactory(js.Dynamic.literal())
    WasmModule.set(exports)
    Trampoline.install()
