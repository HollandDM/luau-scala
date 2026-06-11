package luau.panama

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import luau.core.*

class StringMarshalTest extends munit.FunSuite:

  private def withState[A](f: (Binding[MemorySegment], MemorySegment) => A): A =
    val binding = PanamaBinding.instance
    val state = binding.newState()
    try f(binding, state)
    finally binding.closeState(state)

  test("ASCII string round-trip via push/read") {
    withState { (b, s) =>
      b.pushString(s, "hello world")
      assertEquals(b.toBytes(s, -1).map(arr => new String(IArray.genericWrapArray(arr).toArray)), Some("hello world"))
    }
  }

  test("UTF-8 multibyte string round-trip (Japanese)") {
    withState { (b, s) =>
      val testStr = "こんにちは"
      b.pushString(s, testStr)
      val result = b.toBytes(s, -1).map(arr => new String(IArray.genericWrapArray(arr).toArray, "UTF-8"))
      assertEquals(result, Some(testStr))
    }
  }

  test("toBytes returns None for non-string stack slot") {
    withState { (b, s) =>
      b.pushNumber(s, 42.0)
      assertEquals(b.toBytes(s, -1), None)
    }
  }

  test("empty string round-trip") {
    withState { (b, s) =>
      b.pushString(s, "")
      assertEquals(b.toBytes(s, -1).map(_.length), Some(0))
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
