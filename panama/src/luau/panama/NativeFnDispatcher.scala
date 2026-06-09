package luau.panama

import java.lang.foreign.*
import java.lang.invoke.{MethodHandles, MethodType}
import java.util.concurrent.ConcurrentHashMap
import luau.core.*
import luau.panama.LxConstants.*
import luau.panama.LxHandles.*

final class NativeFnDispatcher:
  private var ps: PanamaState = null
  private val fns = new ConcurrentHashMap[Int, NativeFn[MemorySegment]]()
  private var nextId = 1

  private[panama] def init(state: PanamaState): Unit =
    ps = state

  def register(fn: NativeFn[MemorySegment]): Int =
    val id = nextId
    nextId += 1
    fns.put(id, fn)
    id

  def unregister(id: Int): Unit = fns.remove(id)

  def dispatch(
    state:    MemorySegment,
    thread:   MemorySegment,
    fnId:     Int,
    nArgs:    Int,
    nResults: MemorySegment,
  ): Int =
    val fn = fns.get(fnId)
    if fn == null then
      pushErrorMessage(thread, s"unknown fnId: $fnId")
      return LX_FAIL

    val result =
      try fn(thread, nArgs)
      catch case t: Throwable =>
        pushErrorMessage(thread, t.getMessage.nn)
        NativeFnResult.Fail(LuaValue.Nil)

    result match
      case NativeFnResult.Return(n) =>
        nResults.set(ValueLayout.JAVA_INT, 0L, n)
        LX_RETURN

      case NativeFnResult.Fail(_) =>
        LX_FAIL

      case s @ NativeFnResult.Suspend(_) =>
        val panamaState = ps
        val token = panamaState.suspendRegistry.allocToken(s)
        panamaState.lastYieldToken = token
        lx_set_suspend_token.invokeExact(state, thread, token): Unit
        LX_SUSPEND

  def allocateUpcallStub(arena: Arena): MemorySegment =
    val mh = MethodHandles.lookup().bind(
      this,
      "dispatch",
      MethodType.methodType(
        classOf[Int],
        classOf[MemorySegment],
        classOf[MemorySegment],
        classOf[Int],
        classOf[Int],
        classOf[MemorySegment],
      )
    )
    Linker.nativeLinker().upcallStub(mh, NativeFnDispatcher.HOST_FN_DESC, arena)

  private def pushErrorMessage(thread: MemorySegment, msg: String): Unit =
    val panamaState = ps
    val a = Arena.ofConfined()
    try
      val bytes = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      val seg = a.allocate(bytes.length.toLong, 1L)
      MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.length)
      lx_push_lstring.invokeExact(panamaState.L, thread, seg, bytes.length.toLong): Unit
    finally a.close()

object NativeFnDispatcher:
  import ValueLayout.*
  val HOST_FN_DESC: FunctionDescriptor = FunctionDescriptor.of(
    JAVA_INT,
    ADDRESS,
    ADDRESS,
    JAVA_INT,
    JAVA_INT,
    ADDRESS,
  )
