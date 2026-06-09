package luau.wasm

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.concurrent.{Future, ExecutionContext}

object LuauWasmLoader {

  def load()(using ec: ExecutionContext): Future[WasmModuleExports] = {
    val factory = LuauShimFactory.apply(js.Dynamic.literal())
    factory.toFuture
  }
}
