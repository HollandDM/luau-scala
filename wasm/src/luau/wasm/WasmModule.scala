package luau.wasm

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array

@js.native
trait WasmModuleExports extends js.Object:
  def HEAPU8: Uint8Array = js.native
  def HEAP32: js.typedarray.Int32Array = js.native

  def _malloc(size: Int): Int = js.native
  def _free(ptr: Int): Unit = js.native

  def addFunction(fn: js.Function, sig: String): Int = js.native
  def removeFunction(tableIdx: Int): Unit = js.native

  def dynCall_iiiiii(fnPtr: Int, arg0: Int, arg1: Int, arg2: Int, arg3: Int, arg4: Int): Int = js.native

  def _lx_newstate(upcall: Int): Int = js.native
  def _lx_close(state: Int): Unit = js.native
  def _lx_main_thread(state: Int): Int = js.native
  def _lx_new_thread(state: Int): Int = js.native
  def _lx_thread_status(state: Int, thread: Int): Int = js.native

  def _lx_compile_and_load(state: Int, srcPtr: Int, srcLen: Int, chunknamePtr: Int, optLevel: Int, debugLevel: Int, errbufPtr: Int, errbufSz: Int): Int = js.native

  def _lx_resume(state: Int, thread: Int, nArgs: Int, nResultsPtr: Int): Int = js.native
  def _lx_resume_error(state: Int, thread: Int, nResultsPtr: Int): Int = js.native

  def _lx_push_nil(state: Int, thread: Int): Unit = js.native
  def _lx_push_boolean(state: Int, thread: Int, b: Int): Unit = js.native
  def _lx_push_number(state: Int, thread: Int, n: Double): Unit = js.native
  def _lx_push_lstring(state: Int, thread: Int, ptr: Int, len: Int): Unit = js.native
  def _lx_push_ref(state: Int, thread: Int, ref: Int): Unit = js.native
  def _lx_push_copy(state: Int, thread: Int, idx: Int): Unit = js.native

  def _lx_pop(state: Int, thread: Int, n: Int): Unit = js.native
  def _lx_stack_top(state: Int, thread: Int): Int = js.native

  def _lx_type(state: Int, thread: Int, idx: Int): Int = js.native
  def _lx_to_number(state: Int, thread: Int, idx: Int, okPtr: Int): Double = js.native
  def _lx_to_boolean(state: Int, thread: Int, idx: Int): Int = js.native
  def _lx_to_lstring(state: Int, thread: Int, idx: Int, dstPtr: Int, dstLen: Int, lenPtr: Int): Int = js.native
  def _lx_rawlen(state: Int, thread: Int, idx: Int): Int = js.native

  def _lx_newtable(state: Int, thread: Int, narr: Int, nrec: Int): Unit = js.native
  def _lx_rawget(state: Int, thread: Int, tidx: Int): Unit = js.native
  def _lx_rawset(state: Int, thread: Int, tidx: Int): Unit = js.native
  def _lx_rawgeti(state: Int, thread: Int, tidx: Int, n: Int): Unit = js.native
  def _lx_rawseti(state: Int, thread: Int, tidx: Int, n: Int): Unit = js.native
  def _lx_setarray(state: Int, thread: Int, tidx: Int, startIdx: Int, count: Int): Unit = js.native

  def _lx_ref(state: Int, thread: Int, idx: Int): Int = js.native
  def _lx_unref(state: Int, ref: Int): Unit = js.native

  def _lx_register_native(state: Int, fnId: Int, debugnamePtr: Int): Unit = js.native

  def _lx_set_global(state: Int, namePtr: Int): Unit = js.native
  def _lx_get_global(state: Int, namePtr: Int): Unit = js.native

  def _lx_set_suspend_token(state: Int, thread: Int, token: Int): Unit = js.native
  def _lx_get_suspend_token(state: Int, thread: Int): Int = js.native

  def _lx_openlibs(state: Int, mask: Int): Unit = js.native
  def _lx_open_libs(state: Int): Unit = js.native
  def _lx_sandbox(state: Int): Unit = js.native
  def _lx_conformance_setup(state: Int, silencePrint: Int): Unit = js.native

  def _lx_gc_step(state: Int, stepsize: Int): Unit = js.native
  def _lx_gc_collect(state: Int): Unit = js.native

  def _lx_copy_error(state: Int, thread: Int, bufPtr: Int, bufSz: Int): Int = js.native

object WasmModule:
  private var _module: WasmModuleExports = scala.compiletime.uninitialized
  private[wasm] def set(m: WasmModuleExports): Unit = _module = m
  def module: WasmModuleExports = _module
