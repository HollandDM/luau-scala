package luau.wasm

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.{Uint8Array, Int32Array}

@js.native
@JSImport("fs", JSImport.Default)
private object fs extends js.Object:
  def readFileSync(path: String): js.Any = js.native

@js.native
@JSImport("path", JSImport.Default)
private object path extends js.Object:
  def join(a: String, b: String): String = js.native
  def dirname(a: String): String = js.native

object LuauShimFactory:

  private val WA = js.Dynamic.global.WebAssembly
  private lazy val cachedWrapMod: js.Any =
    val bytes = new Uint8Array(js.Array(
      0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
      0x01, 0x0a, 0x01, 0x60, 0x05, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x01, 0x7f,
      0x02, 0x09, 0x01, 0x03, 0x65, 0x6e, 0x76, 0x01, 0x66, 0x00, 0x00,
      0x03, 0x02, 0x01, 0x00,
      0x07, 0x05, 0x01, 0x01, 0x77, 0x00, 0x01,
      0x0a, 0x10, 0x01, 0x0e, 0x00, 0x20, 0x00, 0x20, 0x01, 0x20, 0x02, 0x20, 0x03, 0x20, 0x04, 0x10, 0x00, 0x0b,
    ).map(b => (b.asInstanceOf[Int] & 0xff).toShort))
    js.Dynamic.newInstance(WA.Module)(bytes)

  private def wrapFactory: js.Dynamic =
    js.Dynamic.newInstance(js.Dynamic.global.Function)(
      "e", "r", "n",
      "return function(){r();return e[n].apply(e,arguments)}"
    )

  def apply(options: js.Object = js.Dynamic.literal()): WasmModuleExports =
    val env = js.Dynamic.global.process.env
    val wasmPath = env.selectDynamic("LUAU_WASM_PATH")
    val path = if js.typeOf(wasmPath) != "undefined" && wasmPath.asInstanceOf[String] != "" then
      wasmPath.asInstanceOf[String]
    else
      "luau-shim.wasm"
    val buf = fs.readFileSync(path).asInstanceOf[Uint8Array]
    val wasmModule = js.Dynamic.newInstance(WA.Module)(buf)

    val wasi = js.Dynamic.literal(
      fd_write = { (_: Int, _: Int, _: Int, _: Int) => 0 }: js.Function,
      fd_close = { (_: Int) => 0 }: js.Function,
      fd_seek = { (_: Int, _: Int, _: Int, _: Int) => 0 }: js.Function,
      fd_read = { (_: Int, _: Int, _: Int, _: Int) => 0 }: js.Function,
      fd_fdstat_get = { (_: Int, _: Int) => 0 }: js.Function,
      environ_sizes_get = { (_: Int, _: Int) => 0 }: js.Function,
      environ_get = { (_: Int, _: Int) => 0 }: js.Function,
      proc_exit = { (_: Int) => 0 }: js.Function,
      clock_time_get = { (_: Int, _: Int, _: Int) => 0 }: js.Function,
      args_sizes_get = { (_: Int, _: Int) => 0 }: js.Function,
      args_get = { (_: Int, _: Int) => 0 }: js.Function,
    )

    val inst = js.Dynamic.newInstance(WA.Instance)(wasmModule, js.Dynamic.literal(
      wasi_snapshot_preview1 = wasi,
    ))
    val ex = inst.exports.asInstanceOf[js.Dynamic]
    val mem = ex.memory
    val tbl = ex.__indirect_function_table

    var HEAPU8 = new Uint8Array(mem.buffer.asInstanceOf[js.typedarray.ArrayBuffer])
    var HEAP32 = new Int32Array(mem.buffer.asInstanceOf[js.typedarray.ArrayBuffer])
    def refresh(): Unit =
      val b = mem.buffer.asInstanceOf[js.typedarray.ArrayBuffer]
      HEAPU8 = new Uint8Array(b)
      HEAP32 = new Int32Array(b)

    var nextIdx = 0
    val refreshFn: js.Function = { () => refresh() }: js.Function
    val prefix  = js.Dynamic.literal(lx_push_integer = "lx_push_number", lx_to_integer = "lx_to_number")

    val api = js.Dynamic.literal(HEAPU8 = HEAPU8, HEAP32 = HEAP32).asInstanceOf[js.Dictionary[js.Any]]
    api("_malloc")            = { (s: Int) => refresh(); ex.malloc(s) }: js.Function
    api("_free")              = { (p: Int) => refresh(); ex.free(p) }: js.Function
    api("addFunction")        = { (fn: js.Function, sig: String) =>
      val i = nextIdx; nextIdx += 1
      val w = js.Dynamic.newInstance(WA.Instance)(cachedWrapMod, js.Dynamic.literal(e = js.Dynamic.literal(f = fn)))
      tbl.set(i, w.exports.w)
      i
    }: js.Function
    api("removeFunction")     = { (i: Int) => tbl.set(i, null) }: js.Function
    api("dynCall_iiiiii")     = { (fp: Int, a0: Int, a1: Int, a2: Int, a3: Int, a4: Int) =>
      val fn = tbl.get(fp)
      if fn != null then fn(a0, a1, a2, a3, a4).asInstanceOf[Int] else 0
    }: js.Function

    val names = js.Array(
      "lx_newstate", "lx_close", "lx_main_thread", "lx_new_thread",
      "lx_thread_status", "lx_compile_and_load", "lx_resume",
      "lx_push_nil", "lx_push_boolean", "lx_push_number", "lx_push_integer",
      "lx_push_lstring", "lx_push_ref", "lx_push_copy", "lx_pop", "lx_stack_top",
      "lx_type", "lx_to_number", "lx_to_integer", "lx_to_boolean", "lx_to_lstring",
      "lx_rawlen", "lx_newtable", "lx_rawget", "lx_rawset", "lx_rawgeti", "lx_rawseti",
      "lx_setarray", "lx_ref", "lx_unref", "lx_register_native",
      "lx_set_suspend_token", "lx_get_suspend_token",
      "lx_set_global", "lx_get_global",
      "lx_openlibs", "lx_sandbox", "lx_open_libs",
      "lx_gc_step", "lx_gc_collect", "lx_copy_error",
    )

    var i = 0
    while i < names.length do
      val n = names(i).asInstanceOf[String]
      val src = prefix.selectDynamic(n)
      val wasmName = if js.typeOf(src) != "undefined" then src.asInstanceOf[String] else n
      val fn = ex.selectDynamic(wasmName)
      if js.typeOf(fn) != "undefined" then
        api("_" + n) = wrapFactory(fn, refreshFn, wasmName)
      i += 1

    api.asInstanceOf[WasmModuleExports]
