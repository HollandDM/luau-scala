package luau.panama

import java.lang.foreign.MemorySegment
import java.util.concurrent.atomic.AtomicBoolean
import luau.core.RefKey

final class PanamaRef(val luaRef: RefKey, state: PanamaState) extends AutoCloseable:
  private val released = new AtomicBoolean(false)

  private[panama] def isReleased: Boolean = released.get()

  override def close(): Unit =
    if released.compareAndSet(false, true) then
      state.releaseRef(luaRef)

  def push(thread: MemorySegment): Unit =
    LxHandles.lx_push_ref.invokeExact(state.L, thread, luaRef.raw): Unit
