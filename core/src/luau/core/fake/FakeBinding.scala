package luau.core.fake

import luau.core.*
import luau.core.codec.*

object FakeBinding extends Binding[FakeState]:

  // ---- Live-state slot ---------------------------------------------------
  private val slot = new StateSlot[FakeState]
  private val pendingByState = scala.collection.mutable.HashMap[FakeState, NativeFnResult.Suspend]()

  def reserveStateSlot(): Unit = slot.reserve()

  def releaseStateSlot(): Unit =
    slot.release()
    pendingByState.clear()

  def takePendingSuspend(thread: FakeState): Option[NativeFnResult.Suspend] =
    pendingByState.remove(thread)

  /** Test hook: plant a pending Suspend the way a dispatcher would. */
  def setPendingSuspendForTest(thread: FakeState, s: NativeFnResult.Suspend): Unit =
    pendingByState.update(thread, s)

  def resumeError(thread: FakeState, error: LuaError): ResumeResult =
    ResumeResult.Error(error)

  def newState(): FakeState =
    val s = FakeState()
    slot.mount(s)
    s

  def closeState(state: FakeState): Unit =
    state.markClosed()
    state.registry.clear()
    if slot.unmountIf(_ == state).isDefined then pendingByState.clear()

  def compileAndLoad(
    state:     FakeState,
    source:    IArray[Byte],
    chunkname: String,
  ): Either[LuaError, Unit] =
    state.stack.addOne(LuaValue.Nil)
    Right(())

  def resume(thread: FakeState, nargs: Int): ResumeResult =
    ResumeResult.Returned(0)

  def newThread(state: FakeState): FakeState = FakeState()

  // The fake value model has no thread values on its stack, so extraction
  // never matches and reset has nothing to unwind.
  def toThreadAt(state: FakeState, idx: Int): Option[FakeState] = None
  def resetThread(thread: FakeState): Unit = ()

  // ---- Push -----------------------------------------------------------

  def pushCopy(state: FakeState, idx: Int): Unit =
    state.stack.addOne(state.valueAt(idx))

  def pushNil(state: FakeState): Unit     = state.stack.addOne(LuaValue.Nil)
  def pushBoolean(state: FakeState, v: Boolean): Unit =
    state.stack.addOne(LuaValue.Bool(v))
  def pushNumber(state: FakeState, v: Double): Unit  =
    state.stack.addOne(LuaValue.Number(v))
  def pushBytes(state: FakeState, bytes: IArray[Byte]): Unit =
    state.stack.addOne(LuaValue.LuaString(bytes))
  def pushString(state: FakeState, v: String): Unit  =
    pushBytes(state, IArray.unsafeFromArray(v.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
  def pushFunction(state: FakeState, fnId: Int): Unit =
    state.stack.addOne(LuaValue.Nil)

  private[luau] def pushRef(state: FakeState, registry: RefKey): Unit =
    val v = state.registry.getOrElse(registry.raw, LuaValue.Nil)
    state.stack.addOne(v)

  // ---- Read -----------------------------------------------------------

  def typeAt(state: FakeState, idx: Int): LuaType =
    state.valueAt(idx) match
      case LuaValue.Nil           => LuaType.Nil
      case _: LuaValue.Bool       => LuaType.Boolean
      case _: LuaValue.Number     => LuaType.Number
      case _: LuaValue.LuaString  => LuaType.String
      case _: FakeTable           => LuaType.Table
      case _: LuaValue.LuaRef     => LuaType.Table
      case _                      => LuaType.Nil

  def toNumber(state: FakeState, idx: Int): Option[Double] =
    state.valueAt(idx) match
      case LuaValue.Number(n) => Some(n)
      case _                  => None

  def toBoolean(state: FakeState, idx: Int): Boolean =
    LuaValue.isTruthy(state.valueAt(idx))

  def toBytes(state: FakeState, idx: Int): Option[IArray[Byte]] =
    state.valueAt(idx) match
      case LuaValue.LuaString(b) => Some(b)
      case _                     => None

  def stackTop(state: FakeState): Int = state.stack.size

  def setStackTop(state: FakeState, idx: Int): Unit =
    val newSize = if idx >= 0 then idx else state.stack.size + idx + 1
    while state.stack.size > newSize do state.stack.removeLast()
    while state.stack.size < newSize do state.stack.addOne(LuaValue.Nil)

  // ---- Table ----------------------------------------------------------

  def newTable(state: FakeState): Unit =
    state.stack.addOne(FakeTable.empty)

  def rawGet(state: FakeState, tableIdx: Int): Unit =
    // C API semantics: the index resolves at call time, while the key still
    // sits on the stack — rawGet(-2) with [T, K] must find T.
    val table = state.valueAt(tableIdx)
    val key   = state.stack.removeLast()
    val result = table match
      case t: FakeTable => t.rawGet(key)
      case _            => LuaValue.Nil
    state.stack.addOne(result)

  def rawSet(state: FakeState, tableIdx: Int): Unit =
    // C API semantics: rawSet(-3) with [T, K, V] must find T (see rawGet).
    val table = state.valueAt(tableIdx)
    val value = state.stack.removeLast()
    val key   = state.stack.removeLast()
    table match
      case t: FakeTable => t.rawSet(key, value)
      case _            => ()

  def setArray(state: FakeState, tableIdx: Int, n: Int): Unit =
    val value = state.stack.removeLast()
    state.valueAt(tableIdx) match
      case t: FakeTable => t.rawSet(LuaValue.Number(n.toDouble), value)
      case _            => ()

  def getArray(state: FakeState, tableIdx: Int, n: Int): Unit =
    val result = state.valueAt(tableIdx) match
      case t: FakeTable => t.rawGet(LuaValue.Number(n.toDouble))
      case _            => LuaValue.Nil
    state.stack.addOne(result)

  def rawLen(state: FakeState, idx: Int): Long =
    state.valueAt(idx) match
      case t: FakeTable  => t.size.toLong
      case LuaValue.LuaString(b) => b.length.toLong
      case _             => 0L

  def tableNext(state: FakeState, tableIdx: Int): Boolean =
    // Mirror lua_next: caller pushed the previous key (nil to start). The key
    // is popped BEFORE resolving tableIdx, so callers must pass an absolute
    // index — same constraint as the real C API.
    val prevKey = state.stack.removeLast()
    state.valueAt(tableIdx) match
      case t: FakeTable =>
        val entries = t.map.toSeq
        val next = prevKey match
          case LuaValue.Nil => entries.headOption
          case k =>
            val i = entries.indexWhere(_._1 == k)
            if i >= 0 && i + 1 < entries.size then Some(entries(i + 1)) else None
        next match
          case Some((k, v)) =>
            state.stack.addOne(k)
            state.stack.addOne(v)
            true
          case None => false
      case _ => false

  // ---- Registry -------------------------------------------------------

  private[luau] def ref(state: FakeState): Ref[FakeState] =
    val value = state.stack.removeLast()
    val key   = state.allocRegKey()
    state.registry(key) = value
    new Ref[FakeState](state, RefKey.fromRaw(key), this, "")

  private[luau] def unref(state: FakeState, key: RefKey): Unit =
    if !state.isClosed then state.registry.remove(key.raw)

  // ---- Native functions -----------------------------------------------

  def registerNativeFn(state: FakeState, fn: NativeFn[FakeState]): Unit =
    val id = state.allocFnId()
    state.nativeFns(id) = fn
    pushFunction(state, id)

  // ---- Globals --------------------------------------------------------

  def getGlobal(state: FakeState, name: String): Unit =
    state.stack.addOne(state.globals.getOrElse(name, LuaValue.Nil))

  def setGlobal(state: FakeState, name: String): Unit =
    state.globals(name) = state.stack.removeLast()

  // ---- Library loading / sandbox (stub) -------------------------------

  def openLibs(state: FakeState, libs: Set[LuauLib]): Unit = ()

  def sandbox(state: FakeState): Unit = ()
