package luau.panama

import java.lang.foreign.*
import java.nio.charset.StandardCharsets
import luau.core.*
import luau.panama.LxConstants.*
import luau.panama.LxHandles.*

final class PanamaState private (
  val L: MemorySegment,
  stateArena: Arena,
  val dispatcher: NativeFnDispatcher,
) extends Binding[MemorySegment]:

  private val LUA_GLOBALSINDEX = -10002

  val suspendRegistry: SuspendRegistry = new SuspendRegistry()
  @volatile var lastYieldToken: Long = -1L

  @volatile private var closed = false

  def isClosed: Boolean = closed

  def newState(): MemorySegment =
    throw new UnsupportedOperationException("use PanamaState.open() instead")

  def closeState(state: MemorySegment): Unit =
    LxHandles.lx_close.invokeExact(state): Unit

  def compileAndLoad(
    state: MemorySegment,
    source: IArray[Byte],
    chunkname: String,
  ): Either[LuaError, Unit] =
    checkOpen()
    withArena { arena =>
      val srcSeg = arena.allocate(source.length.toLong + 1L, 1L)
      MemorySegment.copy(source.toArray, 0, srcSeg, ValueLayout.JAVA_BYTE, 0L, source.length)
      srcSeg.set(ValueLayout.JAVA_BYTE, source.length.toLong, 0.toByte)
      val nameSeg = Marshal.toNativeString(chunkname, arena)
      val errbuf = arena.allocate(4096L, 1L)
      val rc = LxHandles.lx_compile_and_load.invokeExact(
        state, srcSeg, source.length.toLong, nameSeg,
        1, 0,
        errbuf, 4096L,
      ).asInstanceOf[Int]
      if rc == 0 then Right(())
      else
        val errMsg = Marshal.fromNativeString(errbuf, strnlen(errbuf, 4096))
        Left(LuaError.runtime(errMsg))
    }

  def resume(thread: MemorySegment, nargs: Int): ResumeResult =
    checkOpen()
    withArena { arena =>
      val nResultsSeg = arena.allocate(ValueLayout.JAVA_INT)
      val rc = LxHandles.lx_resume.invokeExact(L, thread, nargs, nResultsSeg)
        .asInstanceOf[Int]
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

  def newThread(state: MemorySegment): MemorySegment =
    checkOpen()
    LxHandles.lx_new_thread.invokeExact(state).asInstanceOf[MemorySegment]

  def pushNil(state: MemorySegment): Unit =
    LxHandles.lx_push_nil.invokeExact(L, state): Unit

  def pushBoolean(state: MemorySegment, value: Boolean): Unit =
    LxHandles.lx_push_boolean.invokeExact(L, state, if value then 1 else 0): Unit

  def pushNumber(state: MemorySegment, value: Double): Unit =
    LxHandles.lx_push_number.invokeExact(L, state, value): Unit

  def pushBytes(state: MemorySegment, bytes: IArray[Byte]): Unit =
    withArena { arena =>
      val seg = arena.allocate(bytes.length.toLong, 1L)
      MemorySegment.copy(bytes.toArray, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.length)
      LxHandles.lx_push_lstring.invokeExact(L, state, seg, bytes.length.toLong): Unit
    }

  def pushString(state: MemorySegment, value: String): Unit =
    pushBytes(state, IArray.unsafeFromArray(value.getBytes(StandardCharsets.UTF_8)))

  def pushFunction(state: MemorySegment, fnId: Int): Unit =
    withArena { arena =>
      val name = Marshal.toNativeString(s"fn_$fnId", arena)
      LxHandles.lx_register_native.invokeExact(state, fnId, name): Unit
    }

  def pushRef(state: MemorySegment, registry: Int): Unit =
    LxHandles.lx_push_ref.invokeExact(L, state, registry): Unit

  def typeAt(state: MemorySegment, idx: Int): LuaType =
    val code = LxHandles.lx_type.invokeExact(L, state, idx).asInstanceOf[Int]
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
      val n = LxHandles.lx_to_number.invokeExact(L, state, idx, flag)
        .asInstanceOf[Double]
      if flag.get(ValueLayout.JAVA_INT, 0L) != 0 then Some(n) else None
    }

  def toBoolean(state: MemorySegment, idx: Int): Boolean =
    LxHandles.lx_to_boolean.invokeExact(L, state, idx).asInstanceOf[Int] != 0

  def toBytes(state: MemorySegment, idx: Int): Option[IArray[Byte]] =
    withArena { arena =>
      val rawLen = LxHandles.lx_rawlen.invokeExact(L, state, idx)
        .asInstanceOf[Long]
      if rawLen == 0L then
        val t = typeAt(state, idx)
        if t == LuaType.String then Some(IArray.empty[Byte])
        else None
      else
        val bufSize = rawLen + 1L
        val buf = arena.allocate(bufSize, 1L)
        val lenPtr = arena.allocate(ValueLayout.JAVA_LONG)
        val ok = LxHandles.lx_to_lstring.invokeExact(
          L, state, idx, buf, bufSize, lenPtr,
        ).asInstanceOf[Int]
        if ok != 0 then
          val actualLen = lenPtr.get(ValueLayout.JAVA_LONG, 0L)
          val bytes = new Array[Byte](actualLen.toInt)
          MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0L, bytes, 0, actualLen.toInt)
          Some(IArray.unsafeFromArray(bytes))
        else None
    }

  def stackTop(state: MemorySegment): Int =
    LxHandles.lx_stack_top.invokeExact(L, state).asInstanceOf[Int]

  def setStackTop(state: MemorySegment, idx: Int): Unit =
    val top = stackTop(state)
    val newTop = if idx >= 0 then idx else top + idx + 1
    if newTop < top then
      LxHandles.lx_pop.invokeExact(L, state, top - newTop): Unit
    else if newTop > top then
      var i = top
      while i < newTop do
        LxHandles.lx_push_nil.invokeExact(L, state): Unit
        i += 1

  def newTable(state: MemorySegment): Unit =
    LxHandles.lx_newtable.invokeExact(L, state, 0, 0): Unit

  def rawGet(state: MemorySegment, tableIdx: Int): Unit =
    LxHandles.lx_rawget.invokeExact(L, state, tableIdx): Unit

  def rawSet(state: MemorySegment, tableIdx: Int): Unit =
    LxHandles.lx_rawset.invokeExact(L, state, tableIdx): Unit

  def setArray(state: MemorySegment, tableIdx: Int, n: Int): Unit =
    LxHandles.lx_rawseti.invokeExact(L, state, tableIdx, n): Unit

  def getArray(state: MemorySegment, tableIdx: Int, n: Int): Unit =
    LxHandles.lx_rawgeti.invokeExact(L, state, tableIdx, n): Unit

  def rawLen(state: MemorySegment, idx: Int): Long =
    LxHandles.lx_rawlen.invokeExact(L, state, idx).asInstanceOf[Long]

  def ref(state: MemorySegment): Ref[MemorySegment] =
    checkOpen()
    val key = LxHandles.lx_ref.invokeExact(L, state, -1).asInstanceOf[Int]
    if key == -1 then
      throw new IllegalStateException("lx_ref returned LUA_NOREF (stack empty?)")
    val origin = Ref.genOrigin()
    Ref(L, key, this, origin)

  def unref(state: MemorySegment, key: Int): Unit =
    if !closed then
      LxHandles.lx_unref.invokeExact(state, key): Unit

  def registerNativeFn(state: MemorySegment, fn: NativeFn[MemorySegment]): Unit =
    checkOpen()
    val fnId = dispatcher.register(fn)
    withArena { arena =>
      val name = Marshal.toNativeString(s"nativeFn_$fnId", arena)
      LxHandles.lx_register_native.invokeExact(state, fnId, name): Unit
    }

  def getGlobal(state: MemorySegment, name: String): Unit =
    pushString(state, name)
    LxHandles.lx_rawget.invokeExact(L, state, LUA_GLOBALSINDEX): Unit

  def setGlobal(state: MemorySegment, name: String): Unit =
    val saved = LxHandles.lx_ref.invokeExact(L, state, -1).asInstanceOf[Int]
    LxHandles.lx_pop.invokeExact(L, state, 1): Unit
    pushString(state, name)
    LxHandles.lx_push_ref.invokeExact(L, state, saved): Unit
    LxHandles.lx_rawset.invokeExact(L, state, LUA_GLOBALSINDEX): Unit
    LxHandles.lx_unref.invokeExact(L, saved): Unit

  def openLibs(state: MemorySegment, mask: Int): Unit =
    LxHandles.lx_open_libs.invokeExact(state): Unit

  def sandbox(state: MemorySegment): Unit =
    LxHandles.lx_open_libs.invokeExact(state): Unit

  def close(): Unit =
    if !closed then
      closed = true
      LxHandles.lx_close.invokeExact(L): Unit
      stateArena.close()

  def releaseRef(luaRef: Int): Unit =
    if !closed then
      LxHandles.lx_unref.invokeExact(L, luaRef): Unit

  def scoped[A](block: Scope[MemorySegment] ?=> A): A =
    val scope = Scope[MemorySegment](this, L)
    try block(using scope)
    finally scope.close()

  private def checkOpen(): Unit =
    if closed then throw new IllegalStateException("PanamaState is closed")

  private def readError(thread: MemorySegment): String =
    withArena { arena =>
      val buf = arena.allocate(4096L, 1L)
      val n = LxHandles.lx_copy_error.invokeExact(L, thread, buf, 4096L)
        .asInstanceOf[Long]
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

object PanamaState:
  def open(): PanamaState =
    val stateArena = Arena.ofShared()
    val dispatcher = new NativeFnDispatcher()
    val stub = dispatcher.allocateUpcallStub(stateArena)
    val L = LxHandles.lx_newstate.invokeExact(stub).asInstanceOf[MemorySegment]
    if L == MemorySegment.NULL then
      stateArena.close()
      throw new OutOfMemoryError("lx_newstate returned NULL")
    val ps = new PanamaState(L, stateArena, dispatcher)
    dispatcher.init(ps)
    ps

  def use[A](f: PanamaState => A): A =
    val ps = open()
    try f(ps)
    finally ps.close()
