package luau.core

/** Pins LuauLib bits to the shim's LX_LIB_* constants (shim/include/lx.h).
  * The bit is wire ABI: if this test fails, either the shim header or the
  * enum changed unilaterally — fix whichever side drifted, never this table.
  */
class LuauLibSpec extends munit.FunSuite:

  test("bits match the shim's LX_LIB_* values"):
    val expected = Map(
      LuauLib.Base      -> (1 << 0),
      LuauLib.Math      -> (1 << 1),
      LuauLib.String    -> (1 << 2),
      LuauLib.Table     -> (1 << 3),
      LuauLib.Bit32     -> (1 << 4),
      LuauLib.Utf8      -> (1 << 5),
      LuauLib.Os        -> (1 << 6),
      LuauLib.Coroutine -> (1 << 7),
      LuauLib.Vector    -> (1 << 8),
      LuauLib.Buffer    -> (1 << 9),
      LuauLib.Debug     -> (1 << 10),
    )
    assertEquals(LuauLib.values.toSet, expected.keySet)
    for lib <- LuauLib.values do
      assertEquals(lib.bit, expected(lib), s"bit drifted for $lib")

  test("Standard is All minus Debug (mirrors LX_LIB_STANDARD)"):
    assertEquals(LuauLib.Standard, LuauLib.All - LuauLib.Debug)

  test("mask folds bits"):
    assertEquals(LuauLib.mask(Set(LuauLib.Base, LuauLib.Coroutine)), 1 | (1 << 7))
    assertEquals(LuauLib.mask(LuauLib.All), (1 << 11) - 1)
    assertEquals(LuauLib.mask(Set.empty), 0)
