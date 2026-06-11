package luau.wasm

import luau.core.Binding
import luau.core.conformance.ConformanceSuiteBase
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.{Int8Array, Uint8Array}

@js.native
@JSImport("fs", JSImport.Default)
private object ConformanceFs extends js.Object:
  def readFileSync(path: String): Uint8Array = js.native

class WasmConformanceSuite extends ConformanceSuiteBase[Int]:

  private lazy val conformanceDir: String =
    val env = js.Dynamic.global.process.env
    val dir = env.selectDynamic("LUAU_CONFORMANCE_DIR")
    if js.typeOf(dir) == "undefined" then
      fail("LUAU_CONFORMANCE_DIR not set (wired via wasm.jsEnvConfig in build.mill)")
    dir.asInstanceOf[String]

  override def readTestFile(name: String): IArray[Byte] =
    val buf = ConformanceFs.readFileSync(s"$conformanceDir/$name")
    val view = new Int8Array(buf.buffer, buf.byteOffset, buf.length)
    val arr = new Array[Byte](buf.length)
    var i = 0
    while i < arr.length do
      arr(i) = view(i)
      i += 1
    IArray.unsafeFromArray(arr)

  override def withBinding[A](f: Binding[Int] => A): A =
    f(WasmBackend.createBinding())

  override def conformanceSetup(b: Binding[Int], state: Int): Unit =
    WasmModule.module._lx_conformance_setup(state, 1)
