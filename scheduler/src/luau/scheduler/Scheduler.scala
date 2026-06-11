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

  /** Guards the pending→queued handoff against isQuiescent. Off-Driver
    * threads (timer callbacks, user Resume completions) clear
    * pendingCompletion and enqueue in two steps; the Driver's quiescence
    * check reads both. Without mutual exclusion the Driver can sample the
    * window between the clear and the enqueue, see an empty queue with no
    * pending completions, and finish a live world — reaping a parked chunk
    * whose resume is in flight. (JVM only in practice; on JS everything runs
    * on one thread and the monitor is free.)
    */
  private val transitionLock = new Object

  // ── Current task ──────────────────────────────────────────────────────

  private var _currentTask: Option[Task[H]] = None

  def currentTask: Option[Task[H]] = _currentTask

  private[scheduler] def setCurrentTask(task: Option[Task[H]]): Unit =
    _currentTask = task

  // ── Post (enqueue + wake) ─────────────────────────────────────────────

  private def post(rt: ReadyTask[H]): Unit =
    runQueue.enqueue(rt); wake()

  // ── Task allocation ───────────────────────────────────────────────────

  /** Fresh coroutine thread pinned via a registry Ref (the thread object
    * lands on the parent stack; ref() consumes it).
    */
  private def newTaskThread(): (H, Ref[H]) =
    val thread = binding.newThread(state)
    (thread, binding.ref(state))

  private def allocTask(thread: H, threadRef: Ref[H], initial: TaskState): Task[H] =
    val task = Task[H](threadRef, thread, idCounter.incrementAndGet())
    task.setState(initial)
    liveTasks.put(task.id, task)
    task

  /** Push fn + args onto the task thread, consuming the caller's refs.
    * Returns nargs for lua_resume — args only; the function sits below them
    * (Luau convention: firstArg = top - nargs, callee at firstArg - 1).
    */
  private def pushFnAndArgs(thread: H, fnRef: Ref[H], extraArgs: List[Ref[H]]): Int =
    binding.pushRef(thread, fnRef.registryKey)
    extraArgs.foreach { ref => binding.pushRef(thread, ref.registryKey) }
    fnRef.close()
    extraArgs.foreach(_.close())
    extraArgs.size

  // ── Spawn ─────────────────────────────────────────────────────────────

  def spawn(): Task[H] =
    val (thread, threadRef) = newTaskThread()
    val task = allocTask(thread, threadRef, TaskState.Queued)
    post(ReadyTask[H](task, ResumeValues.Pushed(0)))
    task

  // ── Spawn immediate ───────────────────────────────────────────────────

  /** Run the function NOW, up to its first yield (Roblox task.spawn
    * semantics). If the task ends terminal here (Complete/Failed), the
    * handle's threadRef stays OPEN so the caller can still push the thread
    * object — the caller owns closing it (TaskLibrary closes after pushing).
    */
  def spawnImmediate(fnRef: Ref[H], extraArgs: List[Ref[H]]): TaskHandle[H] =
    val (rawThread, threadRef) = newTaskThread()
    val task = allocTask(rawThread, threadRef, TaskState.Running)
    val nargs = pushFnAndArgs(rawThread, fnRef, extraArgs)
    // The immediate burst nests inside the caller's resume: the spawned task
    // is current for its own burst (task.wait inside it must see itself),
    // then the enclosing task is restored.
    val prev = _currentTask
    _currentTask = Some(task)
    val result =
      try binding.resume(rawThread, nargs)
      finally _currentTask = prev
    handleResumeResult(task, result, releaseOnTerminal = false)
    TaskHandle(threadRef, task, this)

  // ── Defer task ────────────────────────────────────────────────────────

  def deferTask(fnRef: Ref[H], extraArgs: List[Ref[H]]): TaskHandle[H] =
    val (rawThread, threadRef) = newTaskThread()
    val task = allocTask(rawThread, threadRef, TaskState.Queued)
    val nargs = pushFnAndArgs(rawThread, fnRef, extraArgs)
    post(ReadyTask[H](task, ResumeValues.Pushed(nargs)))
    TaskHandle(threadRef, task, this)

  // ── Schedule delayed ──────────────────────────────────────────────────

  def scheduleTimer(seconds: Double)(callback: => Unit): Cancel =
    timer.schedule(seconds)(() => callback)

  def scheduleDelayed(fnRef: Ref[H], extraArgs: List[Ref[H]], seconds: Double): TaskHandle[H] =
    val (rawThread, threadRef) = newTaskThread()
    val task = allocTask(rawThread, threadRef, TaskState.Parked)
    task.setPendingCompletion(true)
    val nargs = pushFnAndArgs(rawThread, fnRef, extraArgs)

    timer.schedule(seconds) { () =>
      transitionLock.synchronized {
        if task.state == TaskState.Parked then
          task.setPendingCompletion(false)
          task.setState(TaskState.Queued)
          post(ReadyTask[H](task, ResumeValues.Pushed(nargs)))
      }
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

  /** Cancel the live task owning the thread VALUE behind `threadRef`.
    * Registry keys are per-ref, not per-object: task.cancel pins a fresh ref
    * to the stack value, so key equality never matches the task's own
    * threadRef. Probe object identity through a Lua table instead — a table
    * keyed by the target thread answers rawget hits only for the same object.
    */
  def cancelThread(threadRef: Ref[H]): Unit =
    val top = binding.stackTop(state)
    try
      binding.newTable(state)
      binding.pushRef(state, threadRef.registryKey)
      binding.pushBoolean(state, true)
      binding.rawSet(state, -3)
      val owner = liveTasks.values.find { t =>
        !t.threadRef.isClosed && {
          binding.pushRef(state, t.threadRef.registryKey)
          binding.rawGet(state, -2)
          val hit = binding.toBoolean(state, -1)
          binding.setStackTop(state, top + 1)
          hit
        }
      }
      owner.foreach(cancelTask)
    finally binding.setStackTop(state, top)

  // ── Enqueue resume (off-Driver) ───────────────────────────────────────

  def enqueueResume(task: Task[H], result: Either[LuaError, LuaValue]): Unit =
    transitionLock.synchronized {
      task.setPendingCompletion(false)
      task.setState(TaskState.Queued)
      val rv = result match
        case Right(value) => ResumeValues.SuspendValue(value)
        case Left(err)    => ResumeValues.Failure(err)
      post(ReadyTask[H](task, rv))
    }

  // ── Facade spawn surface (consumed by Task 8's TaskWorld) ─────────────

  def spawnChunk(source: String, chunkname: String): Either[LuaError, TaskHandle[H]] =
    val (thread, threadRef) = newTaskThread()
    binding.compileAndLoad(thread, source, chunkname) match
      case Left(e) =>
        threadRef.close()
        Left(e)
      case Right(()) =>
        val task = allocTask(thread, threadRef, TaskState.Queued)
        post(ReadyTask(task, ResumeValues.Pushed(0)))
        Right(TaskHandle(threadRef, task, this))

  def spawnReady(threadRef: Ref[H], thread: H, nargs: Int): TaskHandle[H] =
    val task = allocTask(thread, threadRef, TaskState.Queued)
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
      case ResumeValues.Pushed(n)       => binding.resume(thread, n)
      case ResumeValues.SuspendValue(v) =>
        pushValue(thread, v)
        binding.resume(thread, 1)

  /** @param releaseOnTerminal false only for spawnImmediate, whose caller
    *   still needs the threadRef to push the thread object and then owns
    *   closing it.
    */
  private def handleResumeResult(
    task: Task[H],
    result: ResumeResult,
    releaseOnTerminal: Boolean = true,
  ): Unit =
    result match
      case ResumeResult.Returned(_) =>
        task.setState(TaskState.Complete)
        liveTasks.remove(task.id)
        if releaseOnTerminal then task.releaseThread()

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
        if releaseOnTerminal then task.releaseThread()
        errorPolicy.onTaskError(task, err)

  // ── Suspend wiring ────────────────────────────────────────────────────

  private def wireSuspend(task: Task[H], register: Resume => Cancel): Unit =
    task.setPendingCompletion(true)
    val resume: Resume = Resume { (either: Either[LuaError, LuaValue]) =>
      transitionLock.synchronized {
        if task.pendingCompletion then
          task.clearCancel()
          task.setPendingCompletion(false)
          enqueueResume(task, either) // reentrant on transitionLock
      }
    }

    val cancel: Cancel = register(resume)
    task.installCancel(cancel)
    if task.state == TaskState.Queued then
      task.clearCancel()

  // ── Quiescence + reaping ──────────────────────────────────────────────

  def isQuiescent: Boolean =
    transitionLock.synchronized {
      runQueue.isEmpty && _currentTask.isEmpty &&
        liveTasks.values.forall(t => !t.pendingCompletion)
    }

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

  def close(): Unit =
    cancelAll()
    // The Scheduler shuts the timer down whether it created it (default arg)
    // or got it injected — the Driver tears the world down through here, and
    // a second shutdown (Driver's own) is an idempotent no-op.
    timer.shutdown()
