package luau.panama

import java.lang.foreign.{Arena, MemorySegment}
import scala.collection.mutable.ArrayBuffer

final class PanamaScope(arena: Arena, state: PanamaState) extends AutoCloseable:
  private val refs = new ArrayBuffer[PanamaRef]()

  def trackRef(ref: PanamaRef): Unit = refs += ref

  def closeRefs(): Unit =
    refs.foreach { r =>
      if !r.isReleased then r.close()
    }
    refs.clear()

  override def close(): Unit =
    closeRefs()
    arena.close()

  def allocate(size: Long, align: Long): MemorySegment =
    arena.allocate(size, align)
