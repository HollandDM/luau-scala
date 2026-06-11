package luau.panama

import java.lang.foreign.*
import java.nio.charset.StandardCharsets
import luau.core.*
import luau.panama.LxConstants.*
import luau.panama.LxHandles.*

/** Stateless Binding[MemorySegment] over PanamaRuntime — mirror of
  * WasmBinding over WasmModule/Trampoline. No VM identity, no per-VM fields;
  * every method takes its target state (§2.1, Q1).
  */
final class PanamaBinding private () extends Binding[MemorySegment]:

  private val LUA_GLOBALSINDEX = -10002

  // ---- Live-state slot --------------------------------------------------

  def reserveStateSlot(): Unit = PanamaRuntime.reserve()
  def releaseStateSlot(): Unit = PanamaRuntime.release()

  def takePendingSuspend(thread: MemorySegment): Option[NativeFnResult.Suspend] =
    val token: Long = LxHandles.lx_get_suspend_token.invokeExact(thread)
    if token == 0L then None
    else
      LxHandles.lx_set_suspend_token.invokeExact(thread, 0L): Unit
      PanamaRuntime.liveData.flatMap(_.suspendRegistry.consume(token))

  def resumeError(thread: MemorySegment, error: LuaError): ResumeResult =
    pushString(thread, error.message)
    withArena { arena =>
      val nResultsSeg = arena.allocate(ValueLayout.JAVA_INT)
      val rc: Int = LxHandles.lx_resume_error.invokeExact(thread, nResultsSeg)
      val nResults = nResultsSeg.get(ValueLayout.JAVA_INT, 0L)
      rc match
        case LX_RESUME_OK    => ResumeResult.Returned(nResults)
        case LX_RESUME_YIELD => ResumeResult.Yielded(nResults)
        case LX_RESUME_ERR   => ResumeResult.Error(LuaError.runtime(readError(thread)))
        case LX_RESUME_MEMERR =>
          ResumeResult.Error(LuaError.memory("lx_resume_error: memory allocation failed"))
        case _ =>
          ResumeResult.Error(LuaError.runtime(s"unexpected lx_resume_error status: $rc"))
    }

  // ---- State lifecycle --------------------------------------------------

  def newState(): MemorySegment =
    val s: MemorySegment = LxHandles.lx_newstate.invokeExact(PanamaRuntime.upcallStub)
    if s.address() == 0L then throw new OutOfMemoryError("lx_newstate returned NULL")
    // mount throws while another state is live — close the fresh native VM
    // on that path or it leaks (nothing else holds the segment).
    try PanamaRuntime.mount(s)
    catch
      case t: Throwable =>
        LxHandles.lx_close.invokeExact(s): Unit
        throw t
    PanamaRuntime.countOpen()
    s

  def closeState(state: MemorySegment): Unit =
    PanamaRuntime.unmount(state)
    LxHandles.lx_close.invokeExact(state): Unit

  // ---- Script loading ---------------------------------------------------

  def compileAndLoad(
    state: MemorySegment,
    source: IArray[Byte],
    chunkname: String,
  ): Either[LuaError, Unit] =
    withArena { arena =>
      val srcSeg = arena.allocate(source.length.toLong + 1L, 1L)
      MemorySegment.copy(IArray.genericWrapArray(source).toArray, 0, srcSeg, ValueLayout.JAVA_BYTE, 0L, source.length)
      srcSeg.set(ValueLayout.JAVA_BYTE, source.length.toLong, 0.toByte)
      val nameSeg = Marshal.toNativeString(chunkname, arena)
      val errbuf = arena.allocate(4096L, 1L)
      val rc: Int = LxHandles.lx_compile_and_load.invokeExact(
        state, srcSeg, source.length.toLong, nameSeg,
        1, 1,
        errbuf, 4096L,
      )
      if rc == 0 then Right(())
      else
        val errMsg = Marshal.fromNativeString(errbuf, strnlen(errbuf, 4096))
        Left(LuaError.runtime(errMsg))
    }

  // ---- Resume boundary --------------------------------------------------

  def resume(thread: MemorySegment, nargs: Int): ResumeResult =
    withArena { arena =>
      val nResultsSeg = arena.allocate(ValueLayout.JAVA_INT)
      val rc: Int = LxHandles.lx_resume.invokeExact(thread, nargs, nResultsSeg)
      val nResults = nResultsSeg.get(ValueLayout.JAVA_INT, 0L)
      rc match
        case LX_RESUME_OK    => ResumeResult.Returned(nResults)
        case LX_RESUME_YIELD => ResumeResult.Yielded(nResults)
        case LX_RESUME_ERR   =>
          val msg = readError(thread)
          ResumeResult.Error(LuaError.runtime(msg))
        case LX_RESUME_MEMERR =>
          ResumeResult.Error(LuaError.memory("lx_resume: memory allocation failed"))
        case _ =>
          ResumeResult.Error(LuaError.runtime(s"unexpected lx_resume status: $rc"))
    }

  // ---- Coroutine / thread lifecycle -------------------------------------

  def newThread(state: MemorySegment): MemorySegment =
    LxHandles.lx_new_thread.invokeExact(state): MemorySegment

  def toThreadAt(state: MemorySegment, idx: Int): Option[MemorySegment] =
    val t = LxHandles.lx_to_thread.invokeExact(state, idx): MemorySegment
    if t.address == 0L then None else Some(t)

  def resetThread(thread: MemorySegment): Unit =
    LxHandles.lx_reset_thread.invokeExact(thread): Unit

  /** Downcalls mint a fresh zero-length MemorySegment per return, so object
    * equality is useless — the lua_State* address is the identity.
    */
  override def sameThread(a: MemorySegment, b: MemorySegment): Boolean =
    a.address == b.address

  // ---- Stack: push operations -------------------------------------------

  def pushNil(state: MemorySegment): Unit =
    LxHandles.lx_push_nil.invokeExact(state): Unit

  def pushBoolean(state: MemorySegment, value: Boolean): Unit =
    LxHandles.lx_push_boolean.invokeExact(state, if value then 1 else 0): Unit

  def pushNumber(state: MemorySegment, value: Double): Unit =
    LxHandles.lx_push_number.invokeExact(state, value): Unit

  def pushBytes(state: MemorySegment, bytes: IArray[Byte]): Unit =
    withArena { arena =>
      val seg = arena.allocate(bytes.length.toLong, 1L)
      MemorySegment.copy(IArray.genericWrapArray(bytes).toArray, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.length)
      LxHandles.lx_push_lstring.invokeExact(state, seg, bytes.length.toLong): Unit
    }

  def pushString(state: MemorySegment, value: String): Unit =
    pushBytes(state, IArray.unsafeFromArray(value.getBytes(StandardCharsets.UTF_8)))

  def pushFunction(state: MemorySegment, fnId: Int): Unit =
    withArena { arena =>
      val name = Marshal.toNativeString(s"fn_$fnId", arena)
      LxHandles.lx_register_native.invokeExact(state, fnId, name): Unit
    }

  private[luau] def pushRef(state: MemorySegment, registry: RefKey): Unit =
    LxHandles.lx_push_ref.invokeExact(state, registry.raw): Unit

  // ---- Stack: read operations -------------------------------------------

  def typeAt(state: MemorySegment, idx: Int): LuaType =
    val code: Int = LxHandles.lx_type.invokeExact(state, idx)
    code match
      case LX_TNONE     => LuaType.None
      case LX_TNIL      => LuaType.Nil
      case LX_TBOOLEAN  => LuaType.Boolean
      case LX_TNUMBER   => LuaType.Number
      case LX_TSTRING   => LuaType.String
      case LX_TTABLE    => LuaType.Table
      case LX_TFUNCTION => LuaType.Function
      case LX_TUSERDATA => LuaType.Userdata
      case LX_TTHREAD   => LuaType.Thread
      case _            => LuaType.fromCode(code)

  def toNumber(state: MemorySegment, idx: Int): Option[Double] =
    withArena { arena =>
      val flag = arena.allocate(ValueLayout.JAVA_INT)
      val n: Double = LxHandles.lx_to_number.invokeExact(state, idx, flag)
      if flag.get(ValueLayout.JAVA_INT, 0L) != 0 then Some(n) else None
    }

  def toBoolean(state: MemorySegment, idx: Int): Boolean =
    val result: Int = LxHandles.lx_to_boolean.invokeExact(state, idx)
    result != 0

  def toBytes(state: MemorySegment, idx: Int): Option[IArray[Byte]] =
    withArena { arena =>
      val rawLen: Long = LxHandles.lx_rawlen.invokeExact(state, idx)
      if rawLen == 0L then
        val t = typeAt(state, idx)
        if t == LuaType.String then Some(IArray.empty[Byte])
        else None
      else
        val bufSize = rawLen + 1L
        val buf = arena.allocate(bufSize, 1L)
        val lenPtr = arena.allocate(ValueLayout.JAVA_LONG)
        val ok: Int = LxHandles.lx_to_lstring.invokeExact(
          state, idx, buf, bufSize, lenPtr,
        )
        if ok != 0 then
          val actualLen = lenPtr.get(ValueLayout.JAVA_LONG, 0L)
          val bytes = new Array[Byte](actualLen.toInt)
          MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0L, bytes, 0, actualLen.toInt)
          Some(IArray.unsafeFromArray(bytes))
        else None
    }

  def stackTop(state: MemorySegment): Int =
    LxHandles.lx_stack_top.invokeExact(state): Int

  def setStackTop(state: MemorySegment, idx: Int): Unit =
    val top = stackTop(state)
    val newTop = if idx >= 0 then idx else top + idx + 1
    if newTop < top then
      LxHandles.lx_pop.invokeExact(state, top - newTop): Unit
    else if newTop > top then
      var i = top
      while i < newTop do
        LxHandles.lx_push_nil.invokeExact(state): Unit
        i += 1

  // ---- Table operations -------------------------------------------------

  def newTable(state: MemorySegment): Unit =
    LxHandles.lx_newtable.invokeExact(state, 0, 0): Unit

  def rawGet(state: MemorySegment, tableIdx: Int): Unit =
    LxHandles.lx_rawget.invokeExact(state, tableIdx): Unit

  def rawSet(state: MemorySegment, tableIdx: Int): Unit =
    LxHandles.lx_rawset.invokeExact(state, tableIdx): Unit

  def setArray(state: MemorySegment, tableIdx: Int, n: Int): Unit =
    LxHandles.lx_rawseti.invokeExact(state, tableIdx, n): Unit

  def getArray(state: MemorySegment, tableIdx: Int, n: Int): Unit =
    LxHandles.lx_rawgeti.invokeExact(state, tableIdx, n): Unit

  def rawLen(state: MemorySegment, idx: Int): Long =
    LxHandles.lx_rawlen.invokeExact(state, idx): Long

  def tableNext(state: MemorySegment, tableIdx: Int): Boolean =
    val rc: Int = LxHandles.lx_table_next.invokeExact(state, tableIdx)
    rc != 0

  // ---- Registry ---------------------------------------------------------

  private[luau] def ref(state: MemorySegment): Ref[MemorySegment] =
    val rawKey: Int = LxHandles.lx_ref.invokeExact(state, -1)
    val key = RefKey.fromRaw(rawKey)
    if key.isNoRef then
      throw new IllegalStateException("lx_ref returned LUA_NOREF (stack empty?)")
    LxHandles.lx_pop.invokeExact(state, 1): Unit
    val origin = Ref.genOrigin()
    Ref(state, key, this, origin)

  private[luau] def unref(state: MemorySegment, key: RefKey): Unit =
    LxHandles.lx_unref.invokeExact(state, key.raw): Unit

  // ---- Native functions -------------------------------------------------

  def registerNativeFn(state: MemorySegment, fn: NativeFn[MemorySegment]): Unit =
    val fnId = PanamaRuntime.dispatcher.register(fn)
    PanamaRuntime.liveData.foreach(_.fnIds.add(fnId))
    withArena { arena =>
      val name = Marshal.toNativeString(s"nativeFn_$fnId", arena)
      LxHandles.lx_register_native.invokeExact(state, fnId, name): Unit
    }

  // ---- Global access ----------------------------------------------------

  def getGlobal(state: MemorySegment, name: String): Unit =
    withArena { arena =>
      val nameSeg = Marshal.toNativeString(name, arena)
      LxHandles.lx_get_global.invokeExact(state, nameSeg): Unit
    }

  def setGlobal(state: MemorySegment, name: String): Unit =
    withArena { arena =>
      val nameSeg = Marshal.toNativeString(name, arena)
      LxHandles.lx_set_global.invokeExact(state, nameSeg): Unit
    }

  def pushCopy(state: MemorySegment, idx: Int): Unit =
    LxHandles.lx_push_copy.invokeExact(state, idx): Unit

  def openLibs(state: MemorySegment, libs: Set[LuauLib]): Unit =
    val _: Int = LxHandles.lx_openlibs.invokeExact(state, LuauLib.mask(libs))

  def sandbox(state: MemorySegment): Unit =
    LxHandles.lx_sandbox.invokeExact(state): Unit

  // ---- Internal helpers -------------------------------------------------

  private def readError(thread: MemorySegment): String =
    withArena { arena =>
      val buf = arena.allocate(4096L, 1L)
      val n: Long = LxHandles.lx_copy_error.invokeExact(thread, buf, 4096L)
      Marshal.fromNativeString(buf, n)
    }

  private def strnlen(seg: MemorySegment, max: Long): Long =
    var i = 0L
    while i < max && seg.get(ValueLayout.JAVA_BYTE, i) != 0.toByte do i += 1
    i

  private def withArena[A](f: Arena => A): A =
    val a = Arena.ofConfined()
    try f(a)
    finally a.close()

object PanamaBinding:
  val instance: PanamaBinding = new PanamaBinding()
