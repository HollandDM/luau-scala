package luau.wasm

import scala.scalajs.js

object WasmBackend:

  private var loaded = false

  /** Lazy single load. The module instance is the process-wide runtime
    * singleton. No public reload.
    */
  def ensureLoaded(): Unit =
    if !loaded then
      WasmModule.set(LuauShimFactory(js.Dynamic.literal()))
      Trampoline.install()
      loaded = true

  def createBinding(): WasmBinding =
    ensureLoaded()
    WasmBinding.create()
