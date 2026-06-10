package luau.wasm

import luau.core.*
import scala.scalajs.js

/** Binding over the wasm shim. `H = Int` is a pointer to a `lua_State` in
  * linear memory — a THREAD handle. Stack ops act on the handle's own stack
  * (same semantics as the Panama backend); no main-thread redirection.
  */
final class WasmBinding private () extends Binding[Int]:

  private val module = WasmModule.module

  // ── State lifecycle ────────────────────────────────────────────────────

  override def newState(): Int =
    module._lx_newstate(Trampoline.install())

  override def closeState(state: Int): Unit =
    module._lx_close(state)

  // ── Script loading ─────────────────────────────────────────────────────

  override def compileAndLoad(
    state:     Int,
    source:    IArray[Byte],
    chunkname: String,
  ): Either[LuaError, Unit] =
    val errbufSz = 512
    val errbufPtr = module._malloc(errbufSz)
    require(errbufPtr != 0, "_malloc for errbuf returned null")
    try
      WasmMarshal.withIArrayBytes(source) { (srcPtr, srcLen) =>
        WasmMarshal.withString(chunkname) { (cnPtr, _) =>
          val status = module._lx_compile_and_load(
            state, srcPtr, srcLen, cnPtr, 1, 1, errbufPtr, errbufSz
          )
          if status == 0 then Right(())
          else
            val msg = readCString(errbufPtr)
            Left(LuaError.runtime(msg))
        }
      }
    finally
      module._free(errbufPtr)

  // ── Resume boundary ────────────────────────────────────────────────────

  override def resume(thread: Int, nargs: Int): ResumeResult =
    val (nresultsPtr, readNResults) = WasmMarshal.allocOutInt()
    try
      val status = module._lx_resume(thread, nargs, nresultsPtr)
      status match
        case LxStatus.Ok =>
          ResumeResult.Returned(readNResults())
        case LxStatus.Yield =>
          ResumeResult.Yielded(readNResults())
        case _ =>
          val errMsg = readError(thread)
          if errMsg.nonEmpty then
            module._lx_pop(thread, 1)
          ResumeResult.Error(LuaError.runtime(errMsg))
    finally
      module._free(nresultsPtr)

  // ── Coroutine / thread lifecycle ───────────────────────────────────────

  override def newThread(state: Int): Int =
    module._lx_new_thread(state)

  // ── Stack: push operations ─────────────────────────────────────────────

  override def pushNil(state: Int): Unit =
    module._lx_push_nil(state)

  override def pushBoolean(state: Int, value: Boolean): Unit =
    module._lx_push_boolean(state, if value then 1 else 0)

  override def pushNumber(state: Int, value: Double): Unit =
    module._lx_push_number(state, value)

  override def pushBytes(state: Int, bytes: IArray[Byte]): Unit =
    WasmMarshal.withIArrayBytes(bytes) { (ptr, len) =>
      module._lx_push_lstring(state, ptr, len)
    }

  override def pushString(state: Int, value: String): Unit =
    WasmMarshal.withString(value) { (ptr, len) =>
      module._lx_push_lstring(state, ptr, len)
    }

  override def pushFunction(state: Int, fnId: Int): Unit =
    module._lx_register_native(state, fnId, 0)

  override def pushCopy(state: Int, idx: Int): Unit =
    module._lx_push_copy(state, idx)

  private[luau] override def pushRef(state: Int, registry: RefKey): Unit =
    module._lx_push_ref(state, registry.raw)

  // ── Stack: read operations (non-raising) ───────────────────────────────

  override def typeAt(state: Int, idx: Int): LuaType =
    LuaType.fromCode(module._lx_type(state, idx))

  override def toNumber(state: Int, idx: Int): Option[Double] =
    val (okPtr, readOk) = WasmMarshal.allocOutInt()
    try
      val result = module._lx_to_number(state, idx, okPtr)
      if readOk() != 0 then Some(result) else None
    finally
      module._free(okPtr)

  override def toBoolean(state: Int, idx: Int): Boolean =
    module._lx_to_boolean(state, idx) != 0

  override def toBytes(state: Int, idx: Int): Option[IArray[Byte]] =
    val rawLen = module._lx_rawlen(state, idx)
    if rawLen <= 0 then
      if module._lx_type(state, idx) == LuaType.String.luaCode then
        Some(IArray.empty[Byte])
      else None
    else
      val bufPtr = module._malloc(rawLen + 1)
      val (lenPtr, readLen) = WasmMarshal.allocOutInt()
      try
        val success = module._lx_to_lstring(state, idx, bufPtr, rawLen + 1, lenPtr)
        val actualLen = readLen()
        if success != 0 && actualLen > 0 then
          val heap = WasmModule.module.HEAPU8
          val arr = new Array[Byte](actualLen)
          var i = 0
          while i < actualLen do
            arr(i) = heap(bufPtr + i).toByte
            i += 1
          Some(IArray.unsafeFromArray(arr))
        else None
      finally
        module._free(lenPtr)
        module._free(bufPtr)

  override def stackTop(state: Int): Int =
    module._lx_stack_top(state)

  override def setStackTop(state: Int, idx: Int): Unit =
    val top = module._lx_stack_top(state)
    val newTop = if idx >= 0 then idx else top + idx + 1
    if newTop > top then
      var i = top
      while i < newTop do
        module._lx_push_nil(state)
        i += 1
    else if newTop < top then
      module._lx_pop(state, top - newTop)

  // ── Table operations ───────────────────────────────────────────────────

  override def newTable(state: Int): Unit =
    module._lx_newtable(state, 0, 0)

  override def rawGet(state: Int, tableIdx: Int): Unit =
    module._lx_rawget(state, tableIdx)

  override def rawSet(state: Int, tableIdx: Int): Unit =
    module._lx_rawset(state, tableIdx)

  override def setArray(state: Int, tableIdx: Int, n: Int): Unit =
    module._lx_rawseti(state, tableIdx, n)

  override def getArray(state: Int, tableIdx: Int, n: Int): Unit =
    module._lx_rawgeti(state, tableIdx, n)

  override def rawLen(state: Int, idx: Int): Long =
    module._lx_rawlen(state, idx).toLong

  override def tableNext(state: Int, tableIdx: Int): Boolean =
    module._lx_table_next(state, tableIdx) != 0

  // ── Registry (Ref management) ─────────────────────────────────────────

  private[luau] override def ref(state: Int): Ref[Int] =
    val refId = RefKey.fromRaw(module._lx_ref(state, -1))
    if refId.isNoRef then
      throw IllegalStateException("lx_ref returned LUA_NOREF (stack empty?)")
    // lx_ref pins by index without popping (see lx.h); the Ref now owns the
    // value, so consume it off the stack to match luaL_ref semantics.
    module._lx_pop(state, 1)
    Ref[Int](state, refId, this, "wasm")

  private[luau] override def unref(state: Int, key: RefKey): Unit =
    module._lx_unref(state, key.raw)

  // ── Native function registration ──────────────────────────────────────

  override def registerNativeFn(state: Int, fn: NativeFn[Int]): Unit =
    val fnId = Trampoline.register(fn)
    module._lx_register_native(state, fnId, 0)

  // ── Global access ──────────────────────────────────────────────────────

  override def getGlobal(state: Int, name: String): Unit =
    WasmMarshal.withString(name) { (namePtr, nameLen) =>
      module._lx_get_global(state, namePtr)
    }

  override def setGlobal(state: Int, name: String): Unit =
    WasmMarshal.withString(name) { (namePtr, nameLen) =>
      module._lx_set_global(state, namePtr)
    }

  // ── Library loading / sandbox ──────────────────────────────────────────

  override def openLibs(state: Int, libs: Set[LuauLib]): Unit =
    module._lx_openlibs(state, LuauLib.mask(libs))

  override def sandbox(state: Int): Unit =
    module._lx_sandbox(state)

  // ── Internal helpers ───────────────────────────────────────────────────

  private def readCString(ptr: Int): String =
    if ptr == 0 then ""
    else
      val heap = WasmModule.module.HEAPU8
      var len = 0
      while heap(ptr + len) != 0 do len += 1
      WasmMarshal.readString(ptr, len)

  private def readError(thread: Int): String =
    val bufSz = 512
    val bufPtr = module._malloc(bufSz)
    try
      val written = module._lx_copy_error(thread, bufPtr, bufSz)
      if written > 0 then readCString(bufPtr) else "unknown error"
    finally
      module._free(bufPtr)

object WasmBinding:
  def create(): WasmBinding = new WasmBinding()
