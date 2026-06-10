package luau.core

trait Binding[H]:

  // ---- State lifecycle ------------------------------------------------

  def newState(): H

  def closeState(state: H): Unit

  // ---- Compile + load -------------------------------------------------

  def compileAndLoad(
    state:     H,
    source:    IArray[Byte],
    chunkname: String,
  ): Either[LuaError, Unit]

  final def compileAndLoad(
    state:     H,
    source:    String,
    chunkname: String,
  ): Either[LuaError, Unit] =
    compileAndLoad(
      state,
      IArray.unsafeFromArray(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
      chunkname,
    )

  // ---- Resume boundary ------------------------------------------------

  def resume(thread: H, nargs: Int): ResumeResult

  // ---- Coroutine / thread lifecycle -----------------------------------

  def newThread(state: H): H

  // ---- Stack: push operations -----------------------------------------

  def pushNil(state: H): Unit

  def pushCopy(state: H, idx: Int): Unit

  def pushBoolean(state: H, value: Boolean): Unit

  def pushNumber(state: H, value: Double): Unit

  def pushBytes(state: H, bytes: IArray[Byte]): Unit

  def pushString(state: H, value: String): Unit

  def pushFunction(state: H, fnId: Int): Unit

  def pushRef(state: H, registry: RefKey): Unit

  // ---- Stack: read operations (non-raising) ---------------------------

  def typeAt(state: H, idx: Int): LuaType

  def toNumber(state: H, idx: Int): Option[Double]

  def toBoolean(state: H, idx: Int): Boolean

  def toBytes(state: H, idx: Int): Option[IArray[Byte]]

  def toStringAt(state: H, idx: Int): Option[String] =
    toBytes(state, idx).map(bytes =>
      new String(
        IArray.genericWrapArray(bytes).toArray,
        java.nio.charset.StandardCharsets.UTF_8,
      ))

  def isNil(state: H, idx: Int): Boolean = typeAt(state, idx) == LuaType.Nil

  def stackTop(state: H): Int

  def setStackTop(state: H, idx: Int): Unit

  def pop(state: H, n: Int): Unit = setStackTop(state, -n - 1)

  // ---- Table operations -----------------------------------------------

  def newTable(state: H): Unit

  def rawGet(state: H, tableIdx: Int): Unit

  def rawSet(state: H, tableIdx: Int): Unit

  def setArray(state: H, tableIdx: Int, n: Int): Unit

  def getArray(state: H, tableIdx: Int, n: Int): Unit

  def rawLen(state: H, idx: Int): Long

  /** lua_next protocol: expects the previous key on top of the stack (nil to
    * start). Pops it; on success pushes next key then value and returns true.
    * Returns false (nothing pushed) when the table at tableIdx is exhausted.
    */
  def tableNext(state: H, tableIdx: Int): Boolean

  // ---- Registry (Ref management) --------------------------------------

  def ref(state: H): Ref[H]

  def unref(state: H, key: RefKey): Unit

  // ---- Native function registration -----------------------------------

  def registerNativeFn(state: H, fn: NativeFn[H]): Unit

  // ---- Global access --------------------------------------------------

  def getGlobal(state: H, name: String): Unit

  def setGlobal(state: H, name: String): Unit

  // ---- Scope helpers --------------------------------------------------

  def openScope(state: H): Scope[H] = Scope(this, state)

  // ---- Library loading / sandbox (P07) --------------------------------

  /** Open the given standard libraries into the VM. Backends translate the
    * set to the shim's bitmask via [[LuauLib.mask]]; raw bits never appear in
    * user code.
    */
  def openLibs(state: H, libs: Set[LuauLib]): Unit

  /** Varargs convenience: `b.openLibs(state, LuauLib.Base, LuauLib.Coroutine)`. */
  final def openLibs(state: H, libs: LuauLib*): Unit =
    openLibs(state, libs.toSet)

  def sandbox(state: H): Unit
