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

  def apply(options: js.Object = js.Dynamic.literal()): WasmModuleExports =
    val env = js.Dynamic.global.process.env
    val wasmPath = env.selectDynamic("LUAU_WASM_PATH")
    val path = if js.typeOf(wasmPath) != "undefined" then
      wasmPath.asInstanceOf[String]
    else
      "luau-shim.wasm"
    val buf = fs.readFileSync(path).asInstanceOf[Uint8Array]
    val wasmModule = js.Dynamic.newInstance(WA.Module)(buf)

    // WASI snapshot_preview1 import stubs, typed precisely against each call's
    // wasm signature: i32 → Int, i64 → js.BigInt, errno result → 0, void → ().
    // i64 args arrive as JS BigInt; typing them Int would make Scala.js unbox
    // the BigInt → ClassCastException. fd_seek/clock_time_get carry the i64s.
    // Stubs that take result pointers MUST write them — returning errno 0
    // while leaving the buffer untouched hands the libc garbage (os.time()
    // read uninitialized memory until clock_time_get wrote its result).
    var wasiMemory: js.Dynamic = null
    def memDataView(): js.Dynamic =
      js.Dynamic.newInstance(js.Dynamic.global.DataView)(wasiMemory.buffer)
    val wasi = js.Dynamic.literal(
      fd_write = { (_: Int, _: Int, _: Int, nwrittenPtr: Int) =>
        memDataView().setUint32(nwrittenPtr, 0, true)
        0
      }: js.Function,
      fd_close = { (_: Int) => 0 }: js.Function,
      fd_seek = { (_: Int, _: js.BigInt, _: Int, _: Int) => 0 }: js.Function,
      fd_read = { (_: Int, _: Int, _: Int, nreadPtr: Int) =>
        memDataView().setUint32(nreadPtr, 0, true)
        0
      }: js.Function,
      fd_fdstat_get = { (_: Int, _: Int) => 0 }: js.Function,
      environ_sizes_get = { (countPtr: Int, bufSizePtr: Int) =>
        val dv = memDataView()
        dv.setUint32(countPtr, 0, true)
        dv.setUint32(bufSizePtr, 0, true)
        0
      }: js.Function,
      environ_get = { (_: Int, _: Int) => 0 }: js.Function,
      proc_exit = { (_: Int) => () }: js.Function,
      clock_time_get = { (_: Int, _: js.BigInt, resultPtr: Int) =>
        val ns = js.BigInt(js.Date.now()) * js.BigInt(1000000)
        memDataView().setBigUint64(resultPtr, ns, true)
        0
      }: js.Function,
      args_sizes_get = { (countPtr: Int, bufSizePtr: Int) =>
        val dv = memDataView()
        dv.setUint32(countPtr, 0, true)
        dv.setUint32(bufSizePtr, 0, true)
        0
      }: js.Function,
      args_get = { (_: Int, _: Int) => 0 }: js.Function,
    )

    val inst = js.Dynamic.newInstance(WA.Instance)(wasmModule, js.Dynamic.literal(
      wasi_snapshot_preview1 = wasi,
    ))
    val ex = inst.exports.asInstanceOf[js.Dynamic]
    // Reactor model: run C++ static constructors once. Exports are NOT wrapped
    // with per-call ctors/dtors (that's the WASI *command* model, which tears
    // global state down after every call and corrupts the embedded Runtime).
    val mem = ex.memory
    wasiMemory = mem
    if js.typeOf(ex._initialize) != "undefined" then ex._initialize()
    val tbl = ex.__indirect_function_table

    // HEAPU8/HEAP32 are getters returning a *fresh* typed-array view of current
    // linear memory. wasm memory growth detaches the backing ArrayBuffer, so a
    // cached view goes stale ("detached ArrayBuffer"); rebuild on every access.
    val prefix = js.Dynamic.literal()
    val api = js.Dynamic.literal().asInstanceOf[js.Dictionary[js.Any]]
    js.Dynamic.global.Object.defineProperty(api, "HEAPU8", js.Dynamic.literal(
      configurable = true, enumerable = true,
      get = { () => new Uint8Array(mem.buffer.asInstanceOf[js.typedarray.ArrayBuffer]) }: js.Function,
    ))
    js.Dynamic.global.Object.defineProperty(api, "HEAP32", js.Dynamic.literal(
      configurable = true, enumerable = true,
      get = { () => new Int32Array(mem.buffer.asInstanceOf[js.typedarray.ArrayBuffer]) }: js.Function,
    ))
    api("_malloc")            = { (s: Int) => ex.malloc(s) }: js.Function
    api("_free")              = { (p: Int) => ex.free(p) }: js.Function
    api("addFunction")        = { (fn: js.Function, sig: String) =>
      // Grow the indirect function table and use the fresh slot. The table is
      // built with `--growable-table`; never overwrite the wasm's own in-use
      // entries (indices 0..N), or its call_indirect targets get clobbered.
      val i = tbl.grow(1).asInstanceOf[Int]
      val w = js.Dynamic.newInstance(WA.Instance)(cachedWrapMod, js.Dynamic.literal(env = js.Dynamic.literal(f = fn)))
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
      "lx_thread_status", "lx_compile_and_load", "lx_resume", "lx_resume_error",
      "lx_push_nil", "lx_push_boolean", "lx_push_number", "lx_push_integer",
      "lx_push_lstring", "lx_push_ref", "lx_push_copy", "lx_pop", "lx_stack_top",
      "lx_type", "lx_to_number", "lx_to_integer", "lx_to_boolean", "lx_to_lstring",
      "lx_rawlen", "lx_newtable", "lx_rawget", "lx_rawset", "lx_rawgeti", "lx_rawseti",
      "lx_setarray", "lx_table_next", "lx_ref", "lx_unref", "lx_register_native",
      "lx_set_suspend_token", "lx_get_suspend_token",
      "lx_set_global", "lx_get_global",
      "lx_openlibs", "lx_sandbox", "lx_open_libs", "lx_conformance_setup",
      "lx_gc_step", "lx_gc_collect", "lx_copy_error",
    )

    var i = 0
    while i < names.length do
      val n = names(i).asInstanceOf[String]
      val src = prefix.selectDynamic(n)
      val wasmName = if js.typeOf(src) != "undefined" then src.asInstanceOf[String] else n
      val fn = ex.selectDynamic(wasmName)
      if js.typeOf(fn) != "undefined" then
        api("_" + n) = fn
      i += 1

    api.asInstanceOf[WasmModuleExports]
