package luau.panama

import java.lang.foreign.*
import java.nio.charset.StandardCharsets

object Marshal:
  import ValueLayout.*

  def toNativeString(s: String, arena: Arena): MemorySegment =
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    val seg = arena.allocate(bytes.length.toLong + 1L, 1L)
    MemorySegment.copy(bytes, 0, seg, JAVA_BYTE, 0L, bytes.length)
    seg.set(JAVA_BYTE, bytes.length.toLong, 0.toByte)
    seg

  def fromNativeBytes(ptr: MemorySegment, len: Long): Array[Byte] =
    val sized = ptr.reinterpret(len)
    val out = new Array[Byte](len.toInt)
    MemorySegment.copy(sized, JAVA_BYTE, 0L, out, 0, len.toInt)
    out

  def fromNativeString(ptr: MemorySegment, len: Long): String =
    new String(fromNativeBytes(ptr, len), StandardCharsets.UTF_8)

  def scratch(size: Long, align: Long, arena: Arena): MemorySegment =
    arena.allocate(size, align)
