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
  private val nextId = new java.util.concurrent.atomic.AtomicInteger(1)

  private[panama] def init(state: PanamaState): Unit =
    ps = state

  def register(fn: NativeFn[MemorySegment]): Int =
    val id = nextId.getAndIncrement()
    fns.put(id, fn)
    id

  def unregister(id: Int): Unit = fns.remove(id)

  // No exception may escape this method: it runs inside a Panama upcall
  // frame and an escaping throwable terminates the JVM.
  def dispatch(
    state:    MemorySegment,
    thread:   MemorySegment,
    fnId:     Int,
    nArgs:    Int,
    nResults: MemorySegment,
  ): Int =
    try
      val fn = fns.get(fnId)
      if fn == null then
        pushErrorMessage(thread, s"unknown fnId: $fnId")
        return LX_FAIL

      val result =
        try fn(thread, nArgs)
        catch case t: Throwable =>
          val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
          pushErrorMessage(thread, msg)
          NativeFnResult.Fail

      result match
        case NativeFnResult.Return(n) =>
          // Pointer args cross the upcall boundary as zero-length segments;
          // resize before dereferencing.
          nResults.reinterpret(ValueLayout.JAVA_INT.byteSize())
            .set(ValueLayout.JAVA_INT, 0L, n)
          LX_RETURN

        case NativeFnResult.Fail =>
          LX_FAIL

        case s @ NativeFnResult.Suspend(_) =>
          val panamaState = ps
          val token = panamaState.suspendRegistry.allocToken(s)
          panamaState.lastYieldToken = token
          lx_set_suspend_token.invokeExact(thread, token): Unit
          LX_SUSPEND
    catch case t: Throwable =>
      try pushErrorMessage(thread, s"luau-scala: dispatch failed: ${t.getClass.getSimpleName}")
      catch case _: Throwable => ()
      LX_FAIL

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
    val a = Arena.ofConfined()
    try
      val bytes = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      val seg = a.allocate(bytes.length.toLong, 1L)
      MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.length)
      lx_push_lstring.invokeExact(thread, seg, bytes.length.toLong): Unit
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
