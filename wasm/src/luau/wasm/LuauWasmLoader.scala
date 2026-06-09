package luau.wasm

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array
import scala.concurrent.{Future, ExecutionContext}

/**
 * Stub WASM loader. Verifies that the Scala.js module compiles and that
 * the emcc-generated ES module can be imported. Replace in P05 with the
 * real WASM binding backend.
 *
 * At runtime, luau-shim.js must be in the Node.js module resolution path.
 * The Mill copyWasmToResources task places it in wasm/test/resources/.
 */
object LuauWasmLoader {

  def load()(implicit ec: ExecutionContext): Future[LuauShimModule] = {
    val factory = LuauShimFactory.apply(js.Dynamic.literal())
    Future.successful(factory.asInstanceOf[LuauShimModule])
  }
}

@js.native
trait LuauShimModule extends js.Object {
  def _lx_version(): Int = js.native
}

@js.native
@JSImport("./luau-shim.js", JSImport.Default)
object LuauShimFactory extends js.Object {
  def apply(options: js.Object): js.Promise[LuauShimModule] = js.native
}
