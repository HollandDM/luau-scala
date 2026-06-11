package luau.scheduler

import luau.core.*
import luau.core.fake.FakeBinding
import luau.core.fake.FakeState
import luau.core.NativeFnResult.Suspend

/** Programmable Binding[FakeState] for scheduler tests.
  * Delegates most ops to FakeBinding; controls resume() return values.
  * `open`: downstream suites (StdlibSuite's CallOrderBinding) extend it to
  * instrument individual ops.
  */
open class TestBinding extends Binding[FakeState]:

  private var resumeResults: List[ResumeResult] = Nil

  def programResumes(results: ResumeResult*): Unit =
    resumeResults = results.toList

  // ---- Live-state slot ---------------------------------------------------

  override def reserveStateSlot(): Unit = FakeBinding.reserveStateSlot()
  override def releaseStateSlot(): Unit = FakeBinding.releaseStateSlot()
  override def takePendingSuspend(thread: FakeState): Option[NativeFnResult.Suspend] =
    FakeBinding.takePendingSuspend(thread)

  def setPendingSuspendForTest(thread: FakeState, s: NativeFnResult.Suspend): Unit =
    FakeBinding.setPendingSuspendForTest(thread, s)

  override def resumeError(thread: FakeState, error: LuaError): ResumeResult =
    ResumeResult.Error(error)

  def newState(): FakeState =
    FakeBinding.newState()

  def closeState(state: FakeState): Unit =
    FakeBinding.closeState(state)

  def compileAndLoad(
    state: FakeState,
    source: IArray[Byte],
    chunkname: String,
  ): Either[LuaError, Unit] =
    FakeBinding.compileAndLoad(state, source, chunkname)

  def resume(thread: FakeState, nargs: Int): ResumeResult =
    resumeResults match
      case h :: t => resumeResults = t; h
      case Nil    => ResumeResult.Returned(0)

  def newThread(state: FakeState): FakeState =
    val thread = FakeBinding.newThread(state)
    state.stack.addOne(LuaValue.Nil)
    thread

  def pushCopy(state: FakeState, idx: Int): Unit =
    FakeBinding.pushCopy(state, idx)

  def pushNil(state: FakeState): Unit       = FakeBinding.pushNil(state)
  def pushBoolean(state: FakeState, v: Boolean): Unit = FakeBinding.pushBoolean(state, v)
  def pushNumber(state: FakeState, v: Double): Unit   = FakeBinding.pushNumber(state, v)
  def pushBytes(state: FakeState, b: IArray[Byte]): Unit = FakeBinding.pushBytes(state, b)
  def pushString(state: FakeState, v: String): Unit  = FakeBinding.pushString(state, v)
  def pushFunction(state: FakeState, fnId: Int): Unit = FakeBinding.pushFunction(state, fnId)
  private[luau] def pushRef(state: FakeState, registry: RefKey): Unit =
    FakeBinding.pushRef(state, registry)

  def typeAt(state: FakeState, idx: Int): LuaType    = FakeBinding.typeAt(state, idx)
  def toNumber(state: FakeState, idx: Int): Option[Double] = FakeBinding.toNumber(state, idx)
  def toBoolean(state: FakeState, idx: Int): Boolean  = FakeBinding.toBoolean(state, idx)
  def toBytes(state: FakeState, idx: Int): Option[IArray[Byte]] = FakeBinding.toBytes(state, idx)
  def stackTop(state: FakeState): Int                 = FakeBinding.stackTop(state)
  def setStackTop(state: FakeState, idx: Int): Unit   = FakeBinding.setStackTop(state, idx)

  def newTable(state: FakeState): Unit    = FakeBinding.newTable(state)
  def rawGet(state: FakeState, tIdx: Int): Unit = FakeBinding.rawGet(state, tIdx)
  def rawSet(state: FakeState, tIdx: Int): Unit = FakeBinding.rawSet(state, tIdx)
  def setArray(state: FakeState, tIdx: Int, n: Int): Unit = FakeBinding.setArray(state, tIdx, n)
  def getArray(state: FakeState, tIdx: Int, n: Int): Unit = FakeBinding.getArray(state, tIdx, n)
  def rawLen(state: FakeState, idx: Int): Long = FakeBinding.rawLen(state, idx)
  def tableNext(state: FakeState, tableIdx: Int): Boolean = FakeBinding.tableNext(state, tableIdx)

  def ref(state: FakeState): Ref[FakeState] = FakeBinding.ref(state)
  private[luau] def unref(state: FakeState, key: RefKey): Unit =
    FakeBinding.unref(state, key)

  def registerNativeFn(state: FakeState, fn: NativeFn[FakeState]): Unit =
    FakeBinding.registerNativeFn(state, fn)

  def getGlobal(state: FakeState, name: String): Unit = FakeBinding.getGlobal(state, name)
  def setGlobal(state: FakeState, name: String): Unit = FakeBinding.setGlobal(state, name)

  def openLibs(state: FakeState, libs: Set[LuauLib]): Unit = FakeBinding.openLibs(state, libs)
  def sandbox(state: FakeState): Unit              = FakeBinding.sandbox(state)

/** A controllable async op: captures the Resume so tests can fire it. */
final class ControllableAsync:
  @volatile var resume: Resume | Null    = null
  @volatile var cancelled: Boolean       = false

  val suspend: Suspend = Suspend { r =>
    resume = r
    Cancel(() => cancelled = true)
  }

/** Build a Scheduler over TestBinding with programmable resume behavior. */
def makeScheduler(
  errorPolicy: ErrorPolicy = ErrorPolicy.logAndDiscard,
): Scheduler[FakeState] =
  val binding = TestBinding()
  val state   = binding.newState()
  Scheduler(binding, state, errorPolicy = errorPolicy)

def makeSchedulerWithBinding(
  binding: TestBinding,
  errorPolicy: ErrorPolicy = ErrorPolicy.logAndDiscard,
): Scheduler[FakeState] =
  val state = binding.newState()
  Scheduler(binding, state, errorPolicy = errorPolicy)
