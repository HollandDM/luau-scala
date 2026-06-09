package luau.scheduler

import luau.core.*
import luau.core.NativeFnResult.Suspend
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import java.util.Timer
import java.util.TimerTask

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

  // ── Current task ──────────────────────────────────────────────────────

  private var _currentTask: Option[Task[H]] = None

  def currentTask: Option[Task[H]] = _currentTask

  private[scheduler] def setCurrentTask(task: Option[Task[H]]): Unit =
    _currentTask = task

  // ── Pending Suspend slot ──────────────────────────────────────────────

  private var pendingSuspend: Option[NativeFnResult.Suspend] = None

  private[scheduler] def setPendingSuspend(s: NativeFnResult.Suspend): Unit =
    pendingSuspend = Some(s)

  private[scheduler] def takePendingSuspend(): Option[NativeFnResult.Suspend] =
    val s = pendingSuspend
    pendingSuspend = None
    s

  // ── Spawn ─────────────────────────────────────────────────────────────

  def spawn(parent: Option[Task[H]] = None): Task[H] =
    val rawThread = binding.newThread(state)
    val threadRef = binding.ref(state)
    val id = idCounter.incrementAndGet()
    val task = Task[H](threadRef, rawThread, parent, id)
    task.setState(TaskState.Queued)
    liveTasks.put(id, task)
    runQueue.enqueue(ReadyTask[H](task, ResumeValues.None))
    task

  // ── Spawn immediate ───────────────────────────────────────────────────

  def spawnImmediate(fnRef: Ref[H], extraArgs: List[Ref[H]]): TaskHandle[H] =
    val rawThread = binding.newThread(state)
    val threadRef = binding.ref(state)
    val id = idCounter.incrementAndGet()
    val task = Task[H](threadRef, rawThread, None, id)
    task.setState(TaskState.Running)
    liveTasks.put(id, task)

    binding.pushRef(rawThread, fnRef.registryKey)
    extraArgs.foreach { ref => binding.pushRef(rawThread, ref.registryKey) }

    fnRef.close()
    extraArgs.foreach(_.close())

    val nargs = 1 + extraArgs.size
    val result = binding.resume(rawThread, nargs)

    result match
      case ResumeResult.Returned(_) =>
        task.setState(TaskState.Complete)
        liveTasks.remove(task.id)
        task.releaseThread()
      case ResumeResult.Yielded(_) =>
        task.setState(TaskState.Parked)
      case ResumeResult.Error(err) =>
        task.setState(TaskState.Failed(err.message))
        liveTasks.remove(task.id)
        task.releaseThread()
        errorPolicy.onTaskError(task, err.message)

    TaskHandle(threadRef, task)

  // ── Defer task ────────────────────────────────────────────────────────

  def deferTask(fnRef: Ref[H], extraArgs: List[Ref[H]]): TaskHandle[H] =
    val rawThread = binding.newThread(state)
    val threadRef = binding.ref(state)
    val id = idCounter.incrementAndGet()
    val task = Task[H](threadRef, rawThread, None, id)
    task.setState(TaskState.Queued)
    liveTasks.put(id, task)

    binding.pushRef(rawThread, fnRef.registryKey)
    extraArgs.foreach { ref => binding.pushRef(rawThread, ref.registryKey) }

    fnRef.close()
    extraArgs.foreach(_.close())

    runQueue.enqueue(ReadyTask[H](task, ResumeValues.None))
    TaskHandle(threadRef, task)

  // ── Schedule delayed ──────────────────────────────────────────────────

  def scheduleDelayed(fnRef: Ref[H], extraArgs: List[Ref[H]], seconds: Double): TaskHandle[H] =
    val rawThread = binding.newThread(state)
    val threadRef = binding.ref(state)
    val id = idCounter.incrementAndGet()
    val task = Task[H](threadRef, rawThread, None, id)
    task.setState(TaskState.Parked)
    liveTasks.put(id, task)

    binding.pushRef(rawThread, fnRef.registryKey)
    extraArgs.foreach { ref => binding.pushRef(rawThread, ref.registryKey) }

    fnRef.close()
    extraArgs.foreach(_.close())

    scheduleTimer(seconds) {
      if task.state == TaskState.Parked then
        task.setState(TaskState.Queued)
        runQueue.enqueue(ReadyTask[H](task, ResumeValues.None))
    }

    TaskHandle(threadRef, task)

  // ── Timer ─────────────────────────────────────────────────────────────

  private val timer = new Timer("luau-scheduler-timer", true)

  def scheduleTimer(seconds: Double)(callback: => Unit): Cancel =
    val ms = (seconds * 1000).toLong
    val timerTask = new TimerTask:
      def run(): Unit = callback
    timer.schedule(timerTask, ms)
    () => timerTask.cancel()

  // ── Cancel task ───────────────────────────────────────────────────────

  def cancelTask(task: Task[H]): Unit =
    val prev = task.state
    if prev == TaskState.Parked || prev == TaskState.Queued then
      task.setState(TaskState.Cancelled)
      task.fireCancel()
      liveTasks.remove(task.id)
      task.releaseThread()

  def cancelThread(threadRef: Ref[H]): Unit =
    liveTasks.values.find { t =>
      !t.threadRef.isClosed && t.threadRef.registryKey == threadRef.registryKey
    }.foreach(cancelTask)

  // ── Enqueue resume ────────────────────────────────────────────────────

  def enqueueResume(task: Task[H], result: Either[LuaError, LuaValue]): Unit =
    task.setState(TaskState.Queued)
    val rv = result match
      case Right(value) => ResumeValues.SuspendValue(value)
      case Left(err)    => ResumeValues.Failure(err)
    runQueue.enqueue(ReadyTask[H](task, rv))

  // ── Driver loop ───────────────────────────────────────────────────────

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
    _currentTask = Some(task)
    pushResumeValues(task, rt.values)
    val nargs = valueCount(rt.values)
    val result = binding.resume(task.thread, nargs)
    _currentTask = None

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
          case Right(value) => ResumeValues.SuspendValue(value)
          case Left(err)    => ResumeValues.Failure(err)
        runQueue.enqueue(ReadyTask[H](task, rv))

    val cancel: Cancel = register(resume)
    task.installCancel(cancel)
    if task.state == TaskState.Queued then
      task.clearCancel()

  // ── Resume value marshaling ───────────────────────────────────────────

  private def pushResumeValues(task: Task[H], rv: ResumeValues): Unit =
    rv match
      case ResumeValues.None                  => ()
      case ResumeValues.SuspendValue(result)  => pushValue(task.thread, result)
      case ResumeValues.Success(result) =>
        binding.pushBoolean(task.thread, true)
        pushValue(task.thread, result)
      case ResumeValues.Failure(err) =>
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
    case ResumeValues.None                  => 0
    case ResumeValues.SuspendValue(_)       => 1
    case ResumeValues.Success(_)            => 2
    case ResumeValues.Failure(_)            => 2

  // ── Teardown ──────────────────────────────────────────────────────────

  def close(): Unit =
    while runQueue.dequeueOption().isDefined do ()
    liveTasks.values.foreach { task =>
      task.setState(TaskState.Cancelled)
      task.fireCancel()
      task.releaseThread()
    }
    liveTasks.clear()
    timer.cancel()
    binding.closeState(state)
