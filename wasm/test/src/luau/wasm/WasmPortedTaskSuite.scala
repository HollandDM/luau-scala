package luau.wasm

import luau.api.{LuaState, PortedTaskSuiteBase, TaskResult, TaskWorld}
import scala.scalajs.js
import scala.scalajs.js.annotation.*

@js.native
@JSImport("fs", JSImport.Default)
private object PortedFs extends js.Object:
  def readFileSync(path: String, encoding: String): String = js.native

class WasmPortedTaskSuite extends PortedTaskSuiteBase[Int]:

  private lazy val portedDir: String =
    val env = js.Dynamic.global.process.env
    val dir = env.selectDynamic("LUAU_PORTED_DIR")
    if js.typeOf(dir) == "undefined" then
      fail("LUAU_PORTED_DIR not set (wired via wasm.jsEnvConfig in build.mill)")
    dir.asInstanceOf[String]

  override protected def readPorted(name: String): String =
    PortedFs.readFileSync(s"$portedDir/$name", "utf8")

  override protected def withTasks[A](setup: TaskWorld[Int] => Unit)(
    finish: LuaState[Int] => A
  ): TaskResult[A] =
    WasmLuau.withTasks()(setup)(finish)
