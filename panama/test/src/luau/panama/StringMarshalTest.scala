package luau.panama

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import luau.core.*

class StringMarshalTest extends munit.FunSuite:

  test("ASCII string round-trip via push/read") {
    PanamaState.use { ps =>
      ps.pushString(ps.L, "hello world")
      assertEquals(ps.toBytes(ps.L, -1).map(b => new String(b.toArray)), Some("hello world"))
    }
  }

  test("UTF-8 multibyte string round-trip (Japanese)") {
    PanamaState.use { ps =>
      val s = "こんにちは"
      ps.pushString(ps.L, s)
      val result = ps.toBytes(ps.L, -1).map(b => new String(b.toArray, "UTF-8"))
      assertEquals(result, Some(s))
    }
  }

  test("toBytes returns None for non-string stack slot") {
    PanamaState.use { ps =>
      ps.pushNumber(ps.L, 42.0)
      assertEquals(ps.toBytes(ps.L, -1), None)
    }
  }

  test("empty string round-trip") {
    PanamaState.use { ps =>
      ps.pushString(ps.L, "")
      assertEquals(ps.toBytes(ps.L, -1).map(_.length), Some(0))
    }
  }

  test("toNativeString allocates null-terminated C string") {
    val arena = Arena.ofAuto()
    val seg = Marshal.toNativeString("abc", arena)
    assertEquals(seg.get(ValueLayout.JAVA_BYTE, 0L), 97.toByte)
    assertEquals(seg.get(ValueLayout.JAVA_BYTE, 1L), 98.toByte)
    assertEquals(seg.get(ValueLayout.JAVA_BYTE, 2L), 99.toByte)
    assertEquals(seg.get(ValueLayout.JAVA_BYTE, 3L), 0.toByte)
  }
