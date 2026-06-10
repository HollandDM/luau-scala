package luau.core

/** Key of a registry slot returned by `lua_ref`: while the slot is live the
  * VM's registry table references the pinned value, keeping it out of GC.
  * Opaque so registry keys cannot be confused with stack indices, fnIds, or
  * any other Int flowing through the binding layer. Mint only at the
  * `lx_ref` boundary via [[RefKey.fromRaw]].
  */
opaque type RefKey = Int

object RefKey:
  /** `LUA_NOREF`: nothing was pinned (e.g. `lua_ref` on an empty stack). */
  val NoRef: RefKey = -1

  def fromRaw(raw: Int): RefKey = raw

  extension (key: RefKey)
    /** The raw `lua_ref` slot number, for handing back across the ABI. */
    def raw: Int = key
    def isNoRef: Boolean = key == -1
