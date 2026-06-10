package luau.core

/** A Luau standard library that can be opened into a VM.
  *
  * Each case carries its `LX_LIB_*` bit from shim/include/lx.h explicitly.
  * The bit is wire ABI shared with the C shim, so it is pinned per case
  * rather than derived from `ordinal` — inserting or reordering cases must
  * never change the value sent to `lx_openlibs`.
  */
enum LuauLib(val bit: Int):
  case Base      extends LuauLib(1 << 0)
  case Math      extends LuauLib(1 << 1)
  case String    extends LuauLib(1 << 2)
  case Table     extends LuauLib(1 << 3)
  case Bit32     extends LuauLib(1 << 4)
  case Utf8      extends LuauLib(1 << 5)
  case Os        extends LuauLib(1 << 6)
  case Coroutine extends LuauLib(1 << 7)
  case Vector    extends LuauLib(1 << 8)
  case Buffer    extends LuauLib(1 << 9)
  case Debug     extends LuauLib(1 << 10)

object LuauLib:

  /** The sandbox-safe default set — everything except [[Debug]].
    * Mirrors `LX_LIB_STANDARD` in the shim.
    */
  val Standard: Set[LuauLib] = values.toSet - Debug

  /** Every library, [[Debug]] included. */
  val All: Set[LuauLib] = values.toSet

  /** Wire mask for `lx_openlibs`. Backend-internal; user code passes
    * `Set[LuauLib]` and never sees the raw bits.
    */
  def mask(libs: Set[LuauLib]): Int = libs.foldLeft(0)(_ | _.bit)
