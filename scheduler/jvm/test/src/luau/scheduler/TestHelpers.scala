package luau.scheduler

import luau.core.*
import luau.core.fake.FakeBinding
import luau.core.fake.FakeState
import luau.core.NativeFnResult.Suspend
import scala.collection.mutable.ArrayDeque

/** Programmable Binding[FakeState] for scheduler tests.
  * Delegates most ops to FakeBinding; controls resume() return values.
  */
class TestBinding extends Binding[FakeState]:

  private var resumeResults: List[ResumeResult] = Nil

  def programResumes(results: ResumeResult*): Unit =
    resumeResults = results.toList

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
  def pushRef(state: FakeState, reg: Int): Unit       = FakeBinding.pushRef(state, reg)

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

  def ref(state: FakeState): Ref[FakeState] = FakeBinding.ref(state)
  def unref(state: FakeState, key: Int): Unit = FakeBinding.unref(state, key)

  def registerNativeFn(state: FakeState, fn: NativeFn[FakeState]): Unit =
    FakeBinding.registerNativeFn(state, fn)

  def getGlobal(state: FakeState, name: String): Unit = FakeBinding.getGlobal(state, name)
  def setGlobal(state: FakeState, name: String): Unit = FakeBinding.setGlobal(state, name)

  override def openScope(state: FakeState): Scope[FakeState] =
    FakeBinding.openScope(state)

  def openLibs(state: FakeState, mask: Int): Unit = FakeBinding.openLibs(state, mask)
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
  Scheduler(binding, state, errorPolicy)

def makeSchedulerWithBinding(
  binding: TestBinding,
  errorPolicy: ErrorPolicy = ErrorPolicy.logAndDiscard,
): Scheduler[FakeState] =
  val state = binding.newState()
  Scheduler(binding, state, errorPolicy)
