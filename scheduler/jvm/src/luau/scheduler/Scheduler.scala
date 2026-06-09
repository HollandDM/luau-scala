package luau.scheduler

import luau.core.*
import luau.core.NativeFnResult.Suspend
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable

/** Single-threaded Scheduler for one Luau state.
  *
  * Thread-safety contract:
  *   - runAllReady() and spawn() called on Driver thread only.
  *   - The Resume callback (from Suspend wiring) may be called from any thread;
  *     it only enqueues and returns immediately.
  *   - close() called on Driver thread after all async ops complete.
  *
  * @param binding     Platform binding for this state.
  * @param state       The Luau state handle.
  * @param errorPolicy Called when a Task fails.
  */
final class Scheduler[H](
  val binding: Binding[H],
  val state: H,
  val errorPolicy: ErrorPolicy = ErrorPolicy.logAndDiscard,
):

  private val runQueue  = PlatformQueue[ReadyTask[H]]()
  private val idCounter = AtomicLong(0L)
  private val liveTasks = mutable.HashMap[Long, Task[H]]()

  // ── Pending Suspend slot ──────────────────────────────────────────────

  private var pendingSuspend: Option[NativeFnResult.Suspend] = None

  /** Called by Native function dispatcher before returning Suspend to Shim. */
  private[scheduler] def setPendingSuspend(s: NativeFnResult.Suspend): Unit =
    pendingSuspend = Some(s)

  /** Read and clear pending Suspend. Called after lx_resume returns Yielded. */
  private[scheduler] def takePendingSuspend(): Option[NativeFnResult.Suspend] =
    val s = pendingSuspend
    pendingSuspend = None
    s

  // ── Spawn ─────────────────────────────────────────────────────────────

  /** Spawn a new Task backed by a fresh lua_newthread coroutine. */
  def spawn(parent: Option[Task[H]] = None): Task[H] =
    val rawThread = binding.newThread(state)
    val threadRef = binding.ref(state)
    val id = idCounter.incrementAndGet()
    val task = Task[H](threadRef, rawThread, parent, id)
    task.setState(TaskState.Queued)
    liveTasks.put(id, task)
    runQueue.enqueue(ReadyTask[H](task, ResumeValues.None))
    task

  // ── Driver loop ───────────────────────────────────────────────────────

  /** Drain the Run queue until empty. Returns number of Tasks resumed. */
  def runAllReady(): Int =
    var count = 0
    while
      runQueue.dequeueOption() match
        case Some(rt) => resumeTask(rt); count += 1; true
        case None     => false
    do ()
    count

  // ── Internal resume ───────────────────────────────────────────────────

  private def resumeTask(rt: ReadyTask[H]): Unit =
    val task = rt.task
    if task.state == TaskState.Cancelled then return

    task.setState(TaskState.Running)
    pushResumeValues(task, rt.values)
    val nargs = valueCount(rt.values)
    val result = binding.resume(task.thread, nargs)

    result match
      case ResumeResult.Returned(_) =>
        task.setState(TaskState.Complete)
        liveTasks.remove(task.id)
        task.releaseThread()

      case ResumeResult.Yielded(_) =>
        takePendingSuspend() match
          case Some(Suspend(register)) =>
            task.setState(TaskState.Parked)
            wireSuspend(task, register)
          case None =>
            task.setState(TaskState.Parked)

      case ResumeResult.Error(err) =>
        task.setState(TaskState.Failed(err.message))
        liveTasks.remove(task.id)
        task.releaseThread()
        errorPolicy.onTaskError(task, err.message)

  // ── Suspend wiring ────────────────────────────────────────────────────

  private def wireSuspend(task: Task[H], register: Resume => Cancel): Unit =
    @volatile var fired = false

    val resume: Resume = either =>
      if !fired then
        fired = true
        task.clearCancel()
        task.setState(TaskState.Queued)
        val rv = either match
          case Right(value) => ResumeValues.Success(value)
          case Left(err)    => ResumeValues.Failure(err)
        runQueue.enqueue(ReadyTask[H](task, rv))

    val cancel: Cancel = register(resume)
    task.installCancel(cancel)
    if task.state == TaskState.Queued then
      task.clearCancel()

  // ── Resume value marshaling ───────────────────────────────────────────

  private def pushResumeValues(task: Task[H], rv: ResumeValues): Unit =
    rv match
      case ResumeValues.None            => ()
      case ResumeValues.Success(result) =>
        binding.pushBoolean(task.thread, true)
        pushValue(task.thread, result)
      case ResumeValues.Failure(err)    =>
        binding.pushBoolean(task.thread, false)
        binding.pushString(task.thread, err.message)

  private def pushValue(thread: H, value: LuaValue): Unit =
    value match
      case LuaValue.Nil              => binding.pushNil(thread)
      case LuaValue.True             => binding.pushBoolean(thread, true)
      case LuaValue.False            => binding.pushBoolean(thread, false)
      case LuaValue.Number(n)        => binding.pushNumber(thread, n)
      case LuaValue.LuaString(b)     => binding.pushBytes(thread, b)
      case _: LuaValue.LuaRef[?]     => binding.pushNil(thread)

  private def valueCount(rv: ResumeValues): Int = rv match
    case ResumeValues.None          => 0
    case ResumeValues.Success(_)    => 2
    case ResumeValues.Failure(_)    => 2

  // ── Teardown ──────────────────────────────────────────────────────────

  def close(): Unit =
    while runQueue.dequeueOption().isDefined do ()
    liveTasks.values.foreach { task =>
      task.setState(TaskState.Cancelled)
      task.fireCancel()
      task.releaseThread()
    }
    liveTasks.clear()
    binding.closeState(state)
