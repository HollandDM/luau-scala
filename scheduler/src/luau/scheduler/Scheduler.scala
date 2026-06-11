package luau.scheduler

import luau.core.*
import luau.core.NativeFnResult.Suspend
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable

final class Scheduler[H](
  val binding: Binding[H],
  val state: H,
  timer: TaskTimer = TaskTimer.create(),
  wake: () => Unit = () => (),
  val errorPolicy: ErrorPolicy = ErrorPolicy.logAndDiscard,
):

  private val runQueue  = PlatformQueue[ReadyTask[H]]()
  private val idCounter = AtomicLong(0L)
  val liveTasks = mutable.HashMap[Long, Task[H]]()

  // ── Current task ──────────────────────────────────────────────────────

  private var _currentTask: Option[Task[H]] = None

  def currentTask: Option[Task[H]] = _currentTask

  private[scheduler] def setCurrentTask(task: Option[Task[H]]): Unit =
    _currentTask = task

  // ── Post (enqueue + wake) ─────────────────────────────────────────────

  private def post(rt: ReadyTask[H]): Unit =
    runQueue.enqueue(rt); wake()

  // ── Spawn ─────────────────────────────────────────────────────────────

  def spawn(parent: Option[Task[H]] = None): Task[H] =
    val rawThread = binding.newThread(state)
    val threadRef = binding.ref(state)
    val id = idCounter.incrementAndGet()
    val task = Task[H](threadRef, rawThread, parent, id)
    task.setState(TaskState.Queued)
    liveTasks.put(id, task)
    post(ReadyTask[H](task, ResumeValues.Pushed(0)))
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

    handleResumeResult(task, result)
    TaskHandle(threadRef, task, this)

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

    post(ReadyTask[H](task, ResumeValues.Pushed(extraArgs.size + 1)))
    TaskHandle(threadRef, task, this)

  // ── Schedule delayed ──────────────────────────────────────────────────

  def scheduleTimer(seconds: Double)(callback: => Unit): Cancel =
    timer.schedule(seconds)(() => callback)

  def scheduleDelayed(fnRef: Ref[H], extraArgs: List[Ref[H]], seconds: Double): TaskHandle[H] =
    val rawThread = binding.newThread(state)
    val threadRef = binding.ref(state)
    val id = idCounter.incrementAndGet()
    val task = Task[H](threadRef, rawThread, None, id)
    task.setState(TaskState.Parked)
    task.setPendingCompletion(true)
    liveTasks.put(id, task)

    binding.pushRef(rawThread, fnRef.registryKey)
    extraArgs.foreach { ref => binding.pushRef(rawThread, ref.registryKey) }

    fnRef.close()
    extraArgs.foreach(_.close())

    timer.schedule(seconds) { () =>
      if task.state == TaskState.Parked then
        task.setPendingCompletion(false)
        task.setState(TaskState.Queued)
        post(ReadyTask[H](task, ResumeValues.Pushed(extraArgs.size + 1)))
    }

    TaskHandle(threadRef, task, this)

  // ── Cancel task ───────────────────────────────────────────────────────

  def cancelTask(task: Task[H]): Unit =
    val prev = task.state
    if prev == TaskState.Parked || prev == TaskState.Queued then
      task.setPendingCompletion(false)
      task.setState(TaskState.Cancelled)
      task.fireCancel()
      liveTasks.remove(task.id)
      task.releaseThread()

  def cancelThread(threadRef: Ref[H]): Unit =
    liveTasks.values.find { t =>
      !t.threadRef.isClosed && t.threadRef.registryKey == threadRef.registryKey
    }.foreach(cancelTask)

  // ── Enqueue resume (off-Driver) ───────────────────────────────────────

  def enqueueResume(task: Task[H], result: Either[LuaError, LuaValue]): Unit =
    task.setPendingCompletion(false)
    task.setState(TaskState.Queued)
    val rv = result match
      case Right(value) => ResumeValues.SuspendValue(value)
      case Left(err)    => ResumeValues.Failure(err)
    post(ReadyTask[H](task, rv))

  // ── Facade spawn surface (consumed by Task 8's TaskWorld) ─────────────

  def spawnChunk(source: String, chunkname: String): Either[LuaError, TaskHandle[H]] =
    val thread = binding.newThread(state)
    val threadRef = binding.ref(state)
    binding.compileAndLoad(thread, source, chunkname) match
      case Left(e) =>
        threadRef.close()
        Left(e)
      case Right(()) =>
        val id = idCounter.incrementAndGet()
        val task = Task[H](threadRef, thread, None, id)
        task.setState(TaskState.Queued)
        liveTasks.put(id, task)
        post(ReadyTask(task, ResumeValues.Pushed(0)))
        Right(TaskHandle(threadRef, task, this))

  def spawnReady(threadRef: Ref[H], thread: H, nargs: Int): TaskHandle[H] =
    val id = idCounter.incrementAndGet()
    val task = Task[H](threadRef, thread, None, id)
    task.setState(TaskState.Queued)
    liveTasks.put(id, task)
    post(ReadyTask(task, ResumeValues.Pushed(nargs)))
    TaskHandle(threadRef, task, this)

  // ── Driver loop ───────────────────────────────────────────────────────

  def runOneReady(): Boolean =
    runQueue.dequeueOption() match
      case Some(rt) => resumeTask(rt); true
      case None     => false

  def runAllReady(): Int =
    var n = 0
    while runOneReady() do n += 1
    n

  // ── Internal resume ───────────────────────────────────────────────────

  private def resumeTask(rt: ReadyTask[H]): Unit =
    val task = rt.task
    if task.state == TaskState.Cancelled then return

    task.setState(TaskState.Running)
    _currentTask = Some(task)
    val result = doResume(task.thread, rt.values)
    _currentTask = None

    handleResumeResult(task, result)

  private def doResume(thread: H, values: ResumeValues): ResumeResult =
    values match
      case ResumeValues.Failure(err)    => binding.resumeError(thread, err)
      case ResumeValues.None            => binding.resume(thread, 0)
      case ResumeValues.Pushed(n)       => binding.resume(thread, n)
      case ResumeValues.SuspendValue(v) =>
        pushValue(thread, v)
        binding.resume(thread, 1)

  private def handleResumeResult(task: Task[H], result: ResumeResult): Unit =
    result match
      case ResumeResult.Returned(_) =>
        task.setState(TaskState.Complete)
        liveTasks.remove(task.id)
        task.releaseThread()

      case ResumeResult.Yielded(_) =>
        binding.takePendingSuspend(task.thread) match
          case Some(Suspend(register)) =>
            task.setState(TaskState.Parked)
            wireSuspend(task, register)
          case None =>
            task.setState(TaskState.Parked)

      case ResumeResult.Error(err) =>
        task.setState(TaskState.Failed(err.message))
        liveTasks.remove(task.id)
        task.releaseThread()
        errorPolicy.onTaskError(task, err)

  // ── Suspend wiring ────────────────────────────────────────────────────

  private def wireSuspend(task: Task[H], register: Resume => Cancel): Unit =
    task.setPendingCompletion(true)
    val resume: Resume = Resume { (either: Either[LuaError, LuaValue]) =>
      if task.pendingCompletion then
        task.clearCancel()
        task.setPendingCompletion(false)
        enqueueResume(task, either)
    }

    val cancel: Cancel = register(resume)
    task.installCancel(cancel)
    if task.state == TaskState.Queued then
      task.clearCancel()

  // ── Quiescence + reaping ──────────────────────────────────────────────

  def isQuiescent: Boolean =
    runQueue.isEmpty && _currentTask.isEmpty &&
      liveTasks.values.forall(t => !t.pendingCompletion)

  def cancelAbandoned(): Int =
    val abandoned = liveTasks.values
      .filter(t => t.state == TaskState.Parked && !t.pendingCompletion).toList
    abandoned.foreach(cancelTask)
    abandoned.size

  def cancelAll(): Unit =
    while runQueue.dequeueOption().isDefined do ()
    liveTasks.values.toList.foreach { t =>
      t.setState(TaskState.Cancelled)
      t.fireCancel()
      t.setPendingCompletion(false)
      t.releaseThread()
    }
    liveTasks.clear()

  // ── Resume value marshaling ───────────────────────────────────────────

  private def pushValue(thread: H, value: LuaValue): Unit =
    value match
      case LuaValue.Nil              => binding.pushNil(thread)
      case LuaValue.True             => binding.pushBoolean(thread, true)
      case LuaValue.False            => binding.pushBoolean(thread, false)
      case LuaValue.Number(n)        => binding.pushNumber(thread, n)
      case LuaValue.LuaString(b)     => binding.pushBytes(thread, b)
      case r: LuaValue.LuaRef        => binding.pushRef(thread, r.ref.registryKey)

  // ── Teardown ──────────────────────────────────────────────────────────

  def close(): Unit = cancelAll()
