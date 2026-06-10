# Plan 06 — Scheduler and Task Model

## 1. Milestone and Goal

This plan delivers the single-threaded **Scheduler** and **Task** lifecycle in the `scheduler` Mill module. By the end of this milestone the Host can:

1. Spawn a **Task** from any Luau thread ref (a `lua_newthread` Ref) and push it onto the **Run queue**.
2. Drive all ready **Task**s to completion via a **Driver** loop that drains the queue: pop a ready **Task**, call `lx_resume`, park on yield/return/error.
3. Wire the **Async primitive** (`Suspend(register)`) so that `register` receives a one-shot, thread-safe `resume` callback that **only enqueues** onto the **Run queue** and never calls `lx_resume` inline, and returns a `Cancel` that fires on **Task** teardown.
4. Observe the coroutine-substrate rule: the **Scheduler** only ever resumes **Task**s it owns; Coroutines resumed by scripts yield to their own resumer and are invisible to the **Scheduler**.
5. Propagate resume errors: a failed resume transitions the **Task** to an error state; a pluggable parent policy decides whether to propagate to a parent **Task** or drop the error.

The implementation is backend-agnostic: the `scheduler` module depends only on `core` abstractions and can be exercised against the fake backend from P03, the Panama backend from P04, or the WASM backend from P05. The parallelism extension described in ADR-0002 (movable-state actor concurrency across a worker pool) is **deferred** — this plan covers the single-Driver MVP only.

---

## 2. Dependencies

### Required prior plans

| Plan | Key deliverables consumed here |
|------|-------------------------------|
| P03 `docs/plans/03-core-abstractions.md` | `Binding` trait, `Ref`, `Scope`, `LuaError`, `NativeFnResult` ADT (`Return`/`Fail`/`Suspend`), `Resume` and `Cancel` type aliases, the `Suspend(register)` case class, `FakeBackend` for unit tests without FFI |
| P04 `docs/plans/04-panama-backend-jvm.md` | JVM-side `Binding` impl; needed to run integration tests on JVM |
| P05 `docs/plans/05-wasm-backend-js.md` | JS-side `Binding` impl; needed to run integration tests on JS |

P04 and P05 are not strictly required to compile the `scheduler` module itself — only to run the backend-specific integration tests. The module must compile and its unit tests must pass with only `core` and the fake backend.

### Exact symbols provided by P03 that this plan consumes

```scala
// core — platform-agnostic

// The platform handle abstraction (P03)
trait Binding:
  type State   // opaque platform handle to a lua_State
  type ThreadH // opaque handle to a lua_newthread thread state
  def lx_newthread(state: State): ThreadH
  def lx_resume(state: State, thread: ThreadH, nargs: Int): ResumeResult
  def lx_close(state: State): Unit
  // ...and other lx_* ops

sealed trait ResumeResult
object ResumeResult:
  case class  Ok(nresults: Int)           extends ResumeResult
  case class  Yielded(nresults: Int)      extends ResumeResult
  case class  Error(msg: String)          extends ResumeResult

// Async primitive types (P03, ADR-0007)
type Resume = Either[LuaError, Result] => Unit
type Cancel = () => Unit
final case class Suspend(register: Resume => Cancel)

// Tri-state Native function return ADT (P03, ADR-0007)
sealed trait NativeFnResult
object NativeFnResult:
  final case class Return(nResults: Int)  extends NativeFnResult
  final case class Fail(msg: String)      extends NativeFnResult
  final case class Suspend(register: Resume => Cancel) extends NativeFnResult

// Ref (P03, ADR-0005)
final class Ref[+A](val id: Int) extends AutoCloseable:
  def close(): Unit = ...

// LuaError (P03)
final case class LuaError(message: String) extends Exception(message)

// FakeBackend (P03) — used for unit tests
object FakeBackend extends Binding: ...
```

The `Binding` trait's exact shape is defined in P03; this plan uses it without modifying it. Any additions needed (e.g., `lx_newthread`) must first be validated against P02's shim ABI.

---

## 3. Design Context

### ADR-0001 — No protected calls across the FFI boundary

All Luau execution enters only through the **Resume boundary** (`lx_resume` / `lua_resume`). This is the sole reason the **Scheduler** can treat a failed resume as a value (the error status) rather than a signal (a thrown exception or longjmp). The consequence for this plan: the Driver loop calls `lx_resume` and pattern-matches on `ResumeResult.Error` — it never wraps the call in a try/catch as a substitute for error recovery.

### ADR-0002 — Movable-state actor concurrency (deferred for this plan)

The Run queue is designed for state migration (release/acquire handoff as the memory fence), but this plan implements the single-Driver variant only. The queue is a `java.util.concurrent.LinkedBlockingDeque` (JVM) or equivalent (JS), allowing off-Driver completions to enqueue safely, but the Driver loop runs on one thread. The multi-Driver worker pool from ADR-0002 layers on top without API changes.

### ADR-0003 — Stackless Task model

A parked **Task** holds no native C stack. The `setjmp` buffer inside `lua_resume` is only live during an in-progress resume; once it returns (status ok, yielded, or error), the buffer is gone and the **Task** is pure heap data. This is what makes it safe to migrate the **Task** across threads (ADR-0002) and, crucially, why completion callbacks can post a resume from any thread without holding the Luau state locked.

**Critical corrolary for this plan**: a completion callback (the `Resume` from `Suspend(register)`) must never call `lx_resume` directly. It only enqueues a `ReadyTask` onto the **Run queue**. The Driver then calls `lx_resume` on its own thread.

See `/home/hoangdinh/OSS/luau-scala/docs/research/topic-coroutines-on-jvm.md` §3.1 (LuaJ) for why the thread-per-coroutine approach (one OS thread blocked per suspended Task) is rejected: ~1 MB stack per Task, catastrophic at scale (the SwitchCraft 250k-thread crash). Stackless + enqueue avoids this entirely — a parked Task is a few heap words, and the Run queue is the only synchronisation point.

### ADR-0004 — Coroutine substrate, Task library on top, single-threaded Scheduler

The **Scheduler** is a blind queue drain. It pops a ready **Task**, calls `lx_resume`, and parks the **Task** on yield/return/error. It never inspects the yield payload. Rescheduling is driven entirely by side effects the script registered **before** yielding: a timer (task.wait), a spawned child, or an **Async primitive** completion. A bare `coroutine.yield` with no prior registration parks the **Task** permanently — this is intentional and matches Roblox behavior.

Script-created Coroutines yield to their own resumer (the script that called `coroutine.resume`), never to the **Scheduler**. The nesting of `lua_resume` calls handles this automatically: the **Scheduler** calls `lx_resume(task.thread)`, which calls the Luau script, which may call `coroutine.resume(innerCo)`, which internally calls `lua_resume(innerCo)`. When `innerCo` yields, it yields to the `coroutine.resume` call inside the Luau script, not to the outermost `lx_resume`. The **Scheduler** never sees the inner yield.

### ADR-0005 — Deterministic Ref lifetime

The `lua_newthread` Luau thread is held as a `Ref[ThreadH]` (a registry reference). It is released when the **Task** completes, errors, or is cancelled. It is never held beyond the **Scheduler**'s teardown. The `Cancel` callback returned from `register` fires the `Ref.close()` chain on teardown.

### ADR-0007 — Callback-based async primitive and tri-state native return

The `Suspend(register: Resume => Cancel)` pathway is the only mechanism for a **Task** to wait on host-side async work. The `register` function:

1. Wires the async operation against the one-shot `resume: Resume` callback.
2. Returns a `Cancel` that the **Scheduler** calls if the **Task** is torn down before the operation completes.
3. `resume` is one-shot: calling it a second time is a no-op in production and throws in dev-mode. This prevents double-resume, which would corrupt the Coroutine.
4. `resume` only **enqueues** — it posts a `ReadyTask` onto the **Run queue** and returns immediately. It never calls `lx_resume`.

From `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-piccolo-rust.md` §3.4 (Executor) and §11.5 (Thread as State Machine): the piccolo scheduler similarly uses an explicit `ThreadMode` state machine (Stopped / Normal / Suspended / Waiting / Running / Result), and completion callbacks do not call into the VM directly but return a `CallbackReturn` instruction to the trampoline. This plan mirrors that pattern in Scala: `TaskState` is a sealed enum, and completion posts a `ReadyTask` message rather than calling `lx_resume`.

### Luau C API functions involved

| C API function | Used via Shim as | Purpose |
|---------------|-----------------|---------|
| `lua_newthread(L)` | `lx_newthread(state)` | Create a new Luau thread (Coroutine substrate for a Task) |
| `lua_resume(L, from, narg)` | `lx_resume(state, thread, nargs)` | The sole Resume boundary entry |
| `lua_ref(L, LUA_REGISTRYINDEX)` | `lx_ref(state)` | Pin the thread in the registry (produce a Ref) |
| `lua_unref(L, LUA_REGISTRYINDEX, ref)` | `lx_unref(state, ref)` | Release the Ref when Task is done |
| `lua_resetthread(L, thread)` | `lx_resetthread(state, thread)` | Reset a dead/errored Coroutine for potential reuse (deferred to P07) |

Note: `lua_newthread` returns a `lua_State*` (a new coroutine sharing the parent's `global_State`). The **Shim** immediately refs it into the registry so it cannot be GC'd, then returns the registry ref. The `ThreadH` in `Binding` is that registry index.

---

## 4. Task Breakdown

### 4.1 Mill module: `scheduler`

File: `/home/hoangdinh/OSS/luau-scala/build.sc` (modification, not new file)

Add the `scheduler` module as a cross-platform `ScalaModule` depending on `core`. It does not depend on `panama` or `wasm` directly — backend-specific test wiring uses test-only dependencies.

```scala
// In build.sc — add alongside core, panama, wasm
object scheduler extends Cross[SchedulerModule](ScalaVersions*)
class SchedulerModule(val crossScalaVersion: String)
    extends CrossScalaModule with CrossPlatformModule:
  def moduleDeps = Seq(core(crossScalaVersion))
  // Test suite wires backends via test-only deps — see section 4.8
```

---

### 4.2 TaskState — the lifecycle enum

File: `/home/hoangdinh/OSS/luau-scala/scheduler/src/luau/scheduler/TaskState.scala`

**Purpose**: Represent every possible lifecycle state of a **Task**. Transitions are guarded; invalid transitions throw in dev-mode. Mirrors piccolo's `ThreadMode` (see `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-piccolo-rust.md` §6.1) but simpler because we have one Driver.

```scala
package luau.scheduler

/** Lifecycle state of a Task owned by the Scheduler.
 *
 *  Transitions (single-threaded Driver, off-Driver only posts ReadyTask):
 *
 *    Spawned  → Running   (Driver picks Task from Run queue)
 *    Running  → Parked    (lx_resume returned Yielded; Task registered an async op before yielding)
 *    Running  → Complete  (lx_resume returned Ok with no more work)
 *    Running  → Failed    (lx_resume returned Error)
 *    Parked   → Queued    (off-Driver completion fires: resume callback posts ReadyTask)
 *    Queued   → Running   (Driver drains Run queue, picks this Task)
 *    Parked   → Cancelled (Scheduler teardown fires Cancel)
 *    Queued   → Cancelled (Scheduler teardown fires Cancel before Driver picks it up)
 */
enum TaskState:
  /** Initial state: Task created but not yet placed on the Run queue. */
  case Spawned
  /** On the Run queue, waiting for the Driver to resume it. */
  case Queued
  /** Currently executing inside lx_resume on the Driver thread. */
  case Running
  /** Yielded to Host; waiting for an async completion to re-enqueue it. */
  case Parked
  /** lx_resume returned Ok; Task thread has returned normally. */
  case Complete
  /** lx_resume returned Error; Task thread errored. */
  case Failed(error: String)
  /** Scheduler teardown fired before Task reached Complete/Failed. */
  case Cancelled
```

---

### 4.3 Task — the scheduling unit

File: `/home/hoangdinh/OSS/luau-scala/scheduler/src/luau/scheduler/Task.scala`

**Purpose**: Hold the Luau thread `Ref` (the Coroutine substrate) plus all Host-side scheduling state. This is the object the **Scheduler** manages; the Luau VM knows nothing about it.

```scala
package luau.scheduler

import luau.core.{Binding, Ref, LuaError, Cancel}
import java.util.concurrent.atomic.{AtomicReference, AtomicBoolean}

/** A Luau thread the Scheduler owns, plus its scheduling state.
 *
 *  @param threadRef  Registry Ref pinning the lua_newthread coroutine.
 *                    Closed when the Task reaches a terminal state.
 *  @param parent     Optional parent Task; receives error notification
 *                    if this Task fails and the parent policy requests it.
 *  @param id         Unique monotonic ID for logging/debugging.
 */
final class Task(
  val threadRef: Ref[?],
  val parent: Option[Task],
  val id: Long
):
  // State is mutated only by the Driver thread; reads from other threads
  // only happen after a Run-queue acquire, which provides the happens-before.
  @volatile private var _state: TaskState = TaskState.Spawned

  def state: TaskState = _state

  /** Set state; called only on the Driver thread (or during teardown under lock). */
  private[scheduler] def setState(s: TaskState): Unit =
    _state = s

  // Cancel hook installed when a Suspend(register) parks the Task.
  // Null when Task is not parked (Spawned/Queued/Running/terminal).
  // Written on Driver thread (after lx_resume returns Yielded).
  // Read on any thread during teardown.
  private val _cancel: AtomicReference[Cancel | Null] =
    AtomicReference(null)

  /** Install the Cancel hook returned by register().
   *  Called on the Driver thread immediately after lx_resume returns Yielded.
   */
  private[scheduler] def installCancel(c: Cancel): Unit =
    _cancel.set(c)

  /** Clear and return the Cancel hook (used when Task is re-queued).
   *  Called on the Driver thread when the completion fires (before the
   *  Task transitions Parked → Queued).
   */
  private[scheduler] def clearCancel(): Cancel | Null =
    _cancel.getAndSet(null)

  /** Fire Cancel if one is installed. Safe to call from any thread. */
  private[scheduler] def fireCancel(): Unit =
    val c = _cancel.getAndSet(null)
    if c != null then c()

  /** Terminal: release the Luau thread Ref. Idempotent. */
  private[scheduler] def releaseThread(): Unit =
    threadRef.close()
```

Key design rule: `_state` is only written by the **Driver** thread (the thread currently draining the **Run queue**). The `AtomicReference` on `_cancel` is needed because completion callbacks (from off-Driver threads) read it during teardown.

---

### 4.4 RunQueue — the concurrent queue

File: `/home/hoangdinh/OSS/luau-scala/scheduler/src/luau/scheduler/RunQueue.scala`

**Purpose**: The bounded concurrent queue a state flows through between resumes. On the JVM, backed by `java.util.concurrent.LinkedBlockingDeque` (unbounded in practice; a capacity limit can be added). On Scala.js (JS backend), a simple mutable `ArrayDeque` suffices because JS is single-threaded — the Driver loop drains it synchronously in the JS event loop tick.

This class is **cross-platform** (Scala 3 `@scala.annotation.nowarn` for JS where `LinkedBlockingDeque` is absent). The JS-specific implementation uses the same API surface but a `js.special` queue via a `platformQueue` expect/actual pattern (see `4.4.1` and `4.4.2`).

```scala
package luau.scheduler

/** A ready Task plus the resume values the completion posted.
 *
 *  @param task    The Task to be resumed by the Driver.
 *  @param values  Resume values pushed onto the thread's stack before
 *                 lx_resume is called. Length 0 for the initial spawn.
 *                 For a Suspend completion, the Either result.
 */
final case class ReadyTask(task: Task, resumeValues: ResumeValues)

/** Values passed back to a Task when it is re-queued after a Suspend. */
enum ResumeValues:
  /** Initial spawn — no arguments to push. */
  case None
  /** Async completion succeeded — push the result. */
  case Success(result: luau.core.Result)
  /** Async completion failed — resume Task with error. */
  case Failure(error: LuaError)
```

Cross-platform queue interface (expect/actual):

```scala
// scheduler/src/luau/scheduler/PlatformQueue.scala
package luau.scheduler

/** Minimal cross-platform queue for ReadyTask items.
 *  JVM: backed by LinkedBlockingDeque (thread-safe).
 *  JS:  backed by js.collection.mutable.Queue (single-threaded; no locking).
 */
expect class PlatformQueue[A]():
  /** Enqueue an item. Thread-safe on JVM; called from Driver or off-Driver completion. */
  def enqueue(item: A): Unit
  /** Dequeue the next item, or None if empty. Non-blocking. */
  def dequeueOption(): Option[A]
  /** True if no items are pending. */
  def isEmpty: Boolean
```

JVM actual (`scheduler/jvm/src/luau/scheduler/PlatformQueue.scala`):

```scala
package luau.scheduler

import java.util.concurrent.LinkedBlockingDeque

actual class PlatformQueue[A]():
  private val q = LinkedBlockingDeque[A]()
  actual def enqueue(item: A): Unit        = q.offerLast(item)
  actual def dequeueOption(): Option[A]    = Option(q.pollFirst())
  actual def isEmpty: Boolean              = q.isEmpty
```

JS actual (`scheduler/js/src/luau/scheduler/PlatformQueue.scala`):

```scala
package luau.scheduler

import scala.collection.mutable

actual class PlatformQueue[A]():
  private val q = mutable.ArrayDeque[A]()
  actual def enqueue(item: A): Unit        = q.addOne(item)
  actual def dequeueOption(): Option[A]    = if q.isEmpty then None else Some(q.removeHead())
  actual def isEmpty: Boolean              = q.isEmpty
```

The `RunQueue` wraps `PlatformQueue[ReadyTask]`:

```scala
// scheduler/src/luau/scheduler/RunQueue.scala
package luau.scheduler

/** The Run queue for a single Luau state (Isolate).
 *
 *  On JVM: thread-safe via LinkedBlockingDeque — off-Driver completions
 *  call enqueue() from any thread; the Driver calls dequeueOption() on its thread.
 *  On JS: single-threaded; both sides run on the JS event loop.
 *
 *  The enqueue/dequeue pair (release/acquire) establishes happens-before
 *  between the completing thread and the Driver — no additional fence needed.
 *  See ADR-0002.
 */
final class RunQueue:
  private val q = PlatformQueue[ReadyTask]()

  /** Post a ReadyTask. May be called from any thread (JVM) or the event loop (JS). */
  def enqueue(rt: ReadyTask): Unit = q.enqueue(rt)

  /** Non-blocking dequeue. Returns None if queue is empty. Called only on Driver. */
  def dequeueOption(): Option[ReadyTask] = q.dequeueOption()

  /** True if the queue is empty. */
  def isEmpty: Boolean = q.isEmpty
```

---

### 4.5 Scheduler — the Driver loop and spawn API

File: `/home/hoangdinh/OSS/luau-scala/scheduler/src/luau/scheduler/Scheduler.scala`

**Purpose**: Owns the **Run queue** and the **Driver** loop. Exposes `spawn`, `runAllReady` (run until queue empty), and `close`. The `Binding` type parameter threads the platform handle through without coupling the scheduler to a specific backend.

```scala
package luau.scheduler

import luau.core.{Binding, Ref, LuaError, Resume, Cancel, Result}
import luau.core.NativeFnResult.Suspend

/** Error policy applied when a Task fails.
 *  The Scheduler calls this after transitioning the Task to Failed.
 */
trait ErrorPolicy:
  def onTaskError(task: Task, error: String): Unit

object ErrorPolicy:
  /** Log and discard — the default for standalone Tasks. */
  val logAndDiscard: ErrorPolicy = (task, error) =>
    Console.err.println(s"[luau-scheduler] Task ${task.id} failed: $error")

  /** Propagate to parent Task by erroring it in the Run queue. */
  val propagateToParent: ErrorPolicy = (task, error) =>
    task.parent.foreach { parent =>
      // TODO P07: implement cross-task error propagation via task.cancel semantics
      Console.err.println(s"[luau-scheduler] Task ${task.id} propagating error to parent ${parent.id}: $error")
    }
```

```scala
/** Single-threaded Scheduler for one Luau state.
 *
 *  Thread-safety contract:
 *    - runAllReady() and spawn() must be called on the Driver thread.
 *    - The Resume callback (produced by wiring Suspend) may be called from
 *      any thread; it only calls runQueue.enqueue() and returns immediately.
 *    - close() must be called on the Driver thread after all async ops complete
 *      (or it cancels in-flight ones).
 *
 *  @param binding      Platform binding for this state.
 *  @param state        The Luau state handle.
 *  @param errorPolicy  Called when a Task fails.
 */
final class Scheduler[B <: Binding](
  val binding: B,
  val state: B#State,
  val errorPolicy: ErrorPolicy = ErrorPolicy.logAndDiscard
):
  private val runQueue  = RunQueue()
  private val idCounter = java.util.concurrent.atomic.AtomicLong(0L)

  // All Tasks spawned and not yet terminal. Used during close() to cancel
  // in-flight Tasks. Guarded by: written on Driver thread; read during close().
  private val liveTasks = java.util.concurrent.ConcurrentHashMap[Long, Task]()

  // ── Spawn ──────────────────────────────────────────────────────────────────

  /** Create a new Task backed by a fresh lua_newthread coroutine.
   *
   *  The thread is created, pinned in the registry as a Ref, wrapped in a Task,
   *  and enqueued as ReadyTask(task, ResumeValues.None).
   *
   *  Must be called on the Driver thread (or before the Driver starts).
   *
   *  @param parent  Optional parent Task for error propagation.
   *  @return        The spawned Task.
   */
  def spawn(parent: Option[Task] = None): Task =
    val threadRef: Ref[B#ThreadH] = binding.lx_newthread(state)
    val id   = idCounter.incrementAndGet()
    val task = Task(threadRef, parent, id)
    task.setState(TaskState.Queued)
    liveTasks.put(id, task)
    runQueue.enqueue(ReadyTask(task, ResumeValues.None))
    task

  // ── Driver loop ────────────────────────────────────────────────────────────

  /** Drain the Run queue until it is empty.
   *
   *  This is the "JS event-loop tick" equivalent: the caller pumps this in a
   *  loop (on JS, scheduled via setInterval or a microtask queue flush; on JVM,
   *  in a dedicated thread or a blocking loop).
   *
   *  Returns the number of Tasks resumed in this call.
   */
  def runAllReady(): Int =
    var count = 0
    while
      runQueue.dequeueOption() match
        case Some(rt) => resumeTask(rt); count += 1; true
        case None     => false
    do ()
    count

  // ── Internal resume ────────────────────────────────────────────────────────

  /** Resume a single Task. Called only on the Driver thread. */
  private def resumeTask(rt: ReadyTask): Unit =
    val task = rt.task
    if task.state == TaskState.Cancelled then
      return // Task was cancelled between enqueue and dequeue; skip.

    task.setState(TaskState.Running)

    // Push resume values onto the thread's Luau stack before calling lx_resume.
    pushResumeValues(task, rt.resumeValues)

    val nargs = resumeValueCount(rt.resumeValues)
    val result = binding.lx_resume(state, task.threadRef.handle, nargs)

    result match
      case ResumeResult.Ok(_) =>
        // Task coroutine returned normally.
        task.setState(TaskState.Complete)
        liveTasks.remove(task.id)
        task.releaseThread()

      case ResumeResult.Yielded(_) =>
        // Task yielded. A Native function registered a Suspend before yielding.
        // The pending Suspend is retrieved from the binding's yield-payload slot.
        // See section 4.6 for how Suspend is transferred from Native function → Scheduler.
        val suspend = binding.takePendingSuspend(state)
        suspend match
          case Some(Suspend(register)) =>
            task.setState(TaskState.Parked)
            wireSuspend(task, register)
          case None =>
            // Bare coroutine.yield with no Suspend registered — Task parks permanently.
            // This matches Roblox behavior (coroutine.yield with no task.* rescheduler).
            task.setState(TaskState.Parked)
            // Task stays in liveTasks; it will be cancelled on close().

      case ResumeResult.Error(msg) =>
        task.setState(TaskState.Failed(msg))
        liveTasks.remove(task.id)
        task.releaseThread()
        errorPolicy.onTaskError(task, msg)

  // ── Suspend wiring ─────────────────────────────────────────────────────────

  /** Wire a Suspend: call register with a one-shot Resume that posts to the
   *  Run queue. Install the returned Cancel on the Task.
   *
   *  Called on the Driver thread, immediately after lx_resume returns Yielded.
   */
  private def wireSuspend(task: Task, register: Resume => Cancel): Unit =
    // The resume callback: one-shot, thread-safe, enqueue-only.
    // Written as a var so that the cancel can disable it.
    @volatile var fired = false

    val resume: Resume = either =>
      // Guard: second call is a no-op (dev-mode should assert here).
      if !fired then
        fired = true
        task.clearCancel() // no longer cancellable once completion fires
        task.setState(TaskState.Queued)
        val rv = either match
          case Right(result) => ResumeValues.Success(result)
          case Left(err)     => ResumeValues.Failure(err)
        runQueue.enqueue(ReadyTask(task, rv))
      // else: dev-mode log double-resume attempt

    val cancel: Cancel = register(resume)
    task.installCancel(cancel)

  // ── Resume value marshaling ────────────────────────────────────────────────

  /** Push values the completion posted onto the Task thread's Luau stack.
   *  Called on the Driver thread immediately before lx_resume.
   */
  private def pushResumeValues(task: Task, rv: ResumeValues): Unit =
    rv match
      case ResumeValues.None =>
        () // Initial spawn: no values to push.
      case ResumeValues.Success(result) =>
        // Push true + result values.
        binding.lx_pushboolean(state, task.threadRef.handle, true)
        // Codec-encode result onto the thread's stack — delegated to binding.
        binding.lx_pushResult(state, task.threadRef.handle, result)
      case ResumeValues.Failure(err) =>
        // Push false + error message (mirrors coroutine.resume error return).
        binding.lx_pushboolean(state, task.threadRef.handle, false)
        binding.lx_pushstring(state, task.threadRef.handle, err.message)

  private def resumeValueCount(rv: ResumeValues): Int = rv match
    case ResumeValues.None         => 0
    case ResumeValues.Success(r)   => 1 + r.nValues // boolean + result arity
    case ResumeValues.Failure(_)   => 2             // boolean + string

  // ── Teardown ────────────────────────────────────────────────────────────────

  /** Cancel all live Tasks and release their Refs.
   *
   *  Must be called on the Driver thread after the Driver loop exits.
   *  After this call the Scheduler must not be used again.
   */
  def close(): Unit =
    liveTasks.values().forEach { task =>
      task.setState(TaskState.Cancelled)
      task.fireCancel()
      task.releaseThread()
    }
    liveTasks.clear()
```

---

### 4.6 Pending Suspend slot — transferring Suspend from Native function to Scheduler

**The problem**: A **Native function** returns `NativeFnResult.Suspend(register)` to the Shim trampoline, which calls `lua_yield(k)`. From the Scala side, `lx_resume` returns `ResumeResult.Yielded`. The `Suspend` object needs to reach the Scheduler.

**Solution**: The `Binding` trait exposes a per-state "pending suspend slot" — a mutable cell that the Native function dispatcher writes into before returning `Suspend` to the Shim, and that `Scheduler.resumeTask` reads immediately after `lx_resume` returns `Yielded`.

This is documented in P03 but the exact slot API is defined here because the Scheduler is the consumer. The Binding must support:

```scala
// Add to Binding trait in core (coordinate with P03):
/** Write a Suspend into the per-state pending slot.
 *  Called by the Native function dispatcher before returning Suspend to the Shim.
 *  The slot holds at most one Suspend at a time (the Scheduler clears it immediately).
 */
def setPendingSuspend(state: State, s: Suspend): Unit

/** Read and clear the pending Suspend slot.
 *  Called by the Scheduler immediately after lx_resume returns Yielded.
 *  Returns None if the yield was a bare coroutine.yield (no Suspend registered).
 */
def takePendingSuspend(state: State): Option[Suspend]
```

The FakeBackend (P03) must implement this pair. The Panama backend (P04) stores it in a `java.lang.ThreadLocal` keyed by state handle. The WASM backend (P05) stores it in a JS `Map` keyed by the WASM state pointer.

---

### 4.7 Driver — the JVM thread / JS event-loop adapter

File: `/home/hoangdinh/OSS/luau-scala/scheduler/src/luau/scheduler/Driver.scala`

**Purpose**: Wrap the `Scheduler.runAllReady()` call in a platform-specific execution context. On the JVM, a dedicated daemon thread loops until `stop()` is called, blocking between turns. On JS, a `setInterval`-like mechanism pumps `runAllReady()` each tick.

This file uses `expect`/`actual` for the platform-specific scheduling primitive. The `Scheduler` itself is identical on both platforms; only how the Driver loop is invoked differs.

```scala
// scheduler/src/luau/scheduler/Driver.scala
package luau.scheduler

import luau.core.Binding

/** Drives a Scheduler: calls runAllReady() repeatedly until stopped.
 *
 *  On JVM: runs on a dedicated Thread, blocking between turns via
 *  a LockSupport.park-based idle mechanism.
 *  On JS: uses setInterval (or a Promise microtask queue flush).
 *
 *  The implementing agent may choose a simpler synchronous driver for tests:
 *  call scheduler.runAllReady() in a while loop until a termination condition.
 */
trait Driver[B <: Binding]:
  val scheduler: Scheduler[B]
  def start(): Unit
  def stop(): Unit
  def awaitIdle(): Unit  // Block (JVM) / return after flush (JS) until queue is empty
```

JVM platform Driver (simple blocking loop, sufficient for MVP):

```scala
// scheduler/jvm/src/luau/scheduler/JvmDriver.scala
package luau.scheduler

import luau.core.Binding
import java.util.concurrent.{CountDownLatch, LinkedBlockingDeque}

final class JvmDriver[B <: Binding](val scheduler: Scheduler[B]) extends Driver[B]:
  private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
  private val idle    = java.util.concurrent.atomic.AtomicBoolean(false)
  private val notifier = LinkedBlockingDeque[Unit](1)

  /** Notify the Driver that new work has been enqueued.
   *  Called by RunQueue.enqueue() to wake a parked Driver.
   */
  def notifyWork(): Unit =
    notifier.offerLast(())

  private val thread: Thread = Thread.ofVirtual().name("luau-driver").unstarted { () =>
    while !stopped.get() do
      val n = scheduler.runAllReady()
      if n == 0 then
        idle.set(true)
        notifier.pollFirst(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        idle.set(false)
  }

  def start(): Unit = thread.start()

  def stop(): Unit =
    stopped.set(true)
    notifyWork()
    thread.join()
    scheduler.close()

  def awaitIdle(): Unit =
    // Spin until queue empty and Driver is idle.
    while !scheduler.runQueue.isEmpty || !idle.get() do
      Thread.onSpinWait()
```

JS platform Driver (event-loop based):

```scala
// scheduler/js/src/luau/scheduler/JsDriver.scala
package luau.scheduler

import luau.core.Binding
import scala.scalajs.js

final class JsDriver[B <: Binding](val scheduler: Scheduler[B]) extends Driver[B]:
  private var intervalHandle: js.Any = null

  def start(): Unit =
    intervalHandle = js.Dynamic.global.setInterval(
      js.Any.fromFunction0(() => scheduler.runAllReady()),
      0  // 0 ms: schedule on next event loop tick
    )

  def stop(): Unit =
    if intervalHandle != null then
      js.Dynamic.global.clearInterval(intervalHandle)
      intervalHandle = null
    scheduler.close()

  def awaitIdle(): Unit =
    scheduler.runAllReady()  // Synchronous flush for tests
```

---

### 4.8 Test suite

File: `/home/hoangdinh/OSS/luau-scala/scheduler/test/src/luau/scheduler/SchedulerTests.scala`

**Purpose**: Cross-platform test suite runnable against the FakeBackend (unit tests) and optionally against real backends (integration tests). Uses `munit` or `utest` as the test framework (consistent with P03).

The tests below use the **FakeBackend** exclusively so they compile and run without any native or WASM artifact.

#### Test helpers

```scala
// scheduler/test/src/luau/scheduler/TestHelpers.scala
package luau.scheduler

import luau.core.{FakeBackend, Binding, LuaError, Result, Resume, Cancel}
import luau.core.NativeFnResult.Suspend

/** Build a Scheduler over the FakeBackend. */
def makeScheduler(): Scheduler[FakeBackend.type] =
  val state = FakeBackend.lx_newstate()
  Scheduler(FakeBackend, state)

/** A controllable async op: exposes the captured Resume so tests can fire it. */
final class ControllableAsync:
  @volatile var resume: Resume | Null    = null
  @volatile var cancelled: Boolean       = false

  val suspend: Suspend = Suspend { r =>
    resume = r
    () => cancelled = true
  }
```

#### Unit tests

```scala
// scheduler/test/src/luau/scheduler/SchedulerTests.scala
package luau.scheduler

class SchedulerTests extends munit.FunSuite:

  // ── TC-01: Spawn and immediate complete ─────────────────────────────────
  test("TC-01 spawned Task transitions Queued → Running → Complete") {
    val sched = makeScheduler()
    // FakeBackend: lx_resume returns Ok(0) immediately (no-op coroutine).
    val task = sched.spawn()
    assertEquals(task.state, TaskState.Queued)
    sched.runAllReady()
    assertEquals(task.state, TaskState.Complete)
  }

  // ── TC-02: Suspend → enqueue → resume ────────────────────────────────────
  test("TC-02 Suspend parks Task; completion re-enqueues; second runAllReady resumes") {
    val sched = makeScheduler()
    val async  = ControllableAsync()

    // FakeBackend: first lx_resume returns Yielded and sets pending Suspend.
    // Second lx_resume (after enqueue) returns Ok.
    FakeBackend.programResumes(
      ResumeResult.Yielded(0),
      ResumeResult.Ok(0)
    )
    FakeBackend.setPendingSuspendOnYield(async.suspend)

    val task = sched.spawn()
    sched.runAllReady() // First drain: Task parks.
    assertEquals(task.state, TaskState.Parked)
    assert(async.resume != null, "register() was called")

    async.resume(Right(Result.empty)) // Off-Driver completion fires.
    assertEquals(task.state, TaskState.Queued)
    sched.runAllReady() // Second drain: Task completes.
    assertEquals(task.state, TaskState.Complete)
  }

  // ── TC-03: Resume is one-shot ────────────────────────────────────────────
  test("TC-03 double resume is a no-op (does not enqueue twice)") {
    val sched = makeScheduler()
    val async  = ControllableAsync()
    FakeBackend.programResumes(ResumeResult.Yielded(0), ResumeResult.Ok(0))
    FakeBackend.setPendingSuspendOnYield(async.suspend)

    sched.spawn()
    sched.runAllReady()
    val r = async.resume
    r(Right(Result.empty))
    r(Right(Result.empty)) // second call — must be a no-op
    assertEquals(sched.runQueue.isEmpty, false) // one item
    sched.runAllReady()
    assertEquals(sched.runQueue.isEmpty, true)  // only one item consumed
  }

  // ── TC-04: Error status → Failed state → error policy ───────────────────
  test("TC-04 lx_resume error transitions Task to Failed and invokes error policy") {
    var capturedError: Option[String] = None
    val policy: ErrorPolicy = (_, err) => capturedError = Some(err)
    val sched = makeScheduler(errorPolicy = policy)
    FakeBackend.programResumes(ResumeResult.Error("script error"))

    val task = sched.spawn()
    sched.runAllReady()
    assertEquals(task.state, TaskState.Failed("script error"))
    assertEquals(capturedError, Some("script error"))
  }

  // ── TC-05: Close cancels parked Tasks ────────────────────────────────────
  test("TC-05 close() fires Cancel for parked Tasks") {
    val sched = makeScheduler()
    val async  = ControllableAsync()
    FakeBackend.programResumes(ResumeResult.Yielded(0))
    FakeBackend.setPendingSuspendOnYield(async.suspend)

    val task = sched.spawn()
    sched.runAllReady()
    assertEquals(task.state, TaskState.Parked)
    assert(!async.cancelled)

    sched.close()
    assert(async.cancelled, "Cancel was fired")
    assertEquals(task.state, TaskState.Cancelled)
  }

  // ── TC-06: Bare yield parks Task permanently ──────────────────────────────
  test("TC-06 bare coroutine.yield (no Suspend registered) parks Task permanently") {
    val sched = makeScheduler()
    // FakeBackend: Yielded with no pending Suspend.
    FakeBackend.programResumes(ResumeResult.Yielded(0))
    FakeBackend.setPendingSuspendOnYield(None)

    val task = sched.spawn()
    sched.runAllReady()
    assertEquals(task.state, TaskState.Parked)
    // Queue is now empty — Task is parked with no re-queue scheduled.
    assertEquals(sched.runQueue.isEmpty, true)
  }

  // ── TC-07: Multiple Tasks interleave correctly ───────────────────────────
  test("TC-07 two Tasks both spawn, first completes then second completes") {
    val sched = makeScheduler()
    FakeBackend.programResumes(
      ResumeResult.Ok(0), // task1 first resume
      ResumeResult.Ok(0)  // task2 first resume
    )
    val t1 = sched.spawn()
    val t2 = sched.spawn()
    sched.runAllReady()
    assertEquals(t1.state, TaskState.Complete)
    assertEquals(t2.state, TaskState.Complete)
  }

  // ── TC-08: Cancelled Task in queue is skipped ────────────────────────────
  test("TC-08 Task cancelled between enqueue and dequeue is skipped by Driver") {
    val sched = makeScheduler()
    val async  = ControllableAsync()
    FakeBackend.programResumes(ResumeResult.Yielded(0), ResumeResult.Ok(0))
    FakeBackend.setPendingSuspendOnYield(async.suspend)

    val task = sched.spawn()
    sched.runAllReady() // Task parks.
    async.resume(Right(Result.empty)) // Re-enqueues.
    task.setState(TaskState.Cancelled) // Simulate external cancel.
    sched.runAllReady() // Driver should skip it without calling lx_resume.
    // If lx_resume were called, FakeBackend would have no more programmed resumes and would throw.
    // No exception = test passes.
    assertEquals(task.state, TaskState.Cancelled)
  }
```

#### Integration test wiring

```scala
// scheduler/test/src/luau/scheduler/BackendIntegrationTests.scala
package luau.scheduler

/** Base trait mixed in by JvmSchedulerIntegrationTests and JsSchedulerIntegrationTests.
 *  Tests here run actual Luau scripts via a real Binding.
 *  Each test loads a small Luau script that yields once and checks the result.
 */
trait SchedulerIntegrationBase[B <: Binding](binding: B) extends munit.FunSuite:

  // ── ITC-01: End-to-end: script suspends, host completes, script gets result ──
  test("ITC-01 script calls native suspend fn, host completes async op, script receives result") {
    // 1. Register a native function 'doAsync' that returns Suspend(register).
    // 2. Load a script: local r = doAsync(); assert(r == 42)
    // 3. Spawn a Task running that script.
    // 4. runAllReady() — Task parks at doAsync().
    // 5. Fire resume with Right(Result.of(42)).
    // 6. runAllReady() — Task completes; no error.
    pending // Requires real Binding; fleshed out in P04/P05 integration test impl
  }

  // ── ITC-02: Error propagation through the Resume boundary ────────────────
  test("ITC-02 script error causes Task to fail; error policy receives the message") {
    // Script: error("deliberate failure")
    pending
  }
```

---

## 5. Acceptance Criteria and Tests

### Unit tests (FakeBackend, both JVM and JS)

| Test ID | Description | Pass condition |
|---------|-------------|----------------|
| TC-01 | Spawn and immediate complete | Task.state == Complete after runAllReady() |
| TC-02 | Suspend → park → enqueue → re-resume | Task.state == Parked after first drain; Queued after async.resume; Complete after second drain |
| TC-03 | One-shot Resume | Double-call to resume does not double-enqueue |
| TC-04 | Error propagation | Task.state == Failed; error policy invoked with message |
| TC-05 | Close cancels parked Tasks | async.cancelled == true after close() |
| TC-06 | Bare yield parks permanently | Task.state == Parked; queue empty |
| TC-07 | Multiple Task interleaving | Both Tasks complete after one runAllReady() |
| TC-08 | Cancelled Task skipped | Driver does not call lx_resume for a cancelled Task |

### Integration tests (real backend, opt-in)

| Test ID | Description | Backend |
|---------|-------------|---------|
| ITC-01 | End-to-end async suspend | Panama (JVM) or WASM (JS) |
| ITC-02 | Error propagation via Resume boundary | Both |

### How to run

```bash
# Unit tests (FakeBackend, JVM)
./mill scheduler.jvm.test

# Unit tests (FakeBackend, JS via Node)
./mill scheduler.js.test

# Integration tests (requires P04 Panama backend artifact)
./mill scheduler.jvm.test --only '*Integration*'

# Integration tests (requires P05 WASM backend artifact)
./mill scheduler.js.test --only '*Integration*'

# All tests, both platforms
./mill scheduler.__.test
```

---

## 6. Risks and Gotchas

### R-01: Pending Suspend slot is a per-state global — reentrancy is forbidden

The `setPendingSuspend`/`takePendingSuspend` slot is a single-valued cell. If a Native function calls back into Luau (via `lx_resume` on a different thread), which then calls another Native function that returns `Suspend`, the second write overwrites the first. This situation (re-entrant resume) is **forbidden** by the single-Driver model: only the Driver calls `lx_resume`, and it does so one Task at a time. Document this constraint clearly in the `Binding` trait.

### R-02: lua_resume return status encoding

From `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-official-cpp.md` §4.5: The Luau VM uses `lua_State::status` to record thread state. `lua_resume` returns `LUA_OK` (0), `LUA_YIELD` (1), or an error code. The Shim's `lx_resume` maps these to `ResumeResult.Ok`, `ResumeResult.Yielded`, `ResumeResult.Error` (with the error message string already popped from the stack). The Scheduler must never inspect `lua_State::status` directly.

### R-03: Luau coroutine status after error

From the Luau C API: after `lua_resume` returns an error status, the coroutine is in `LUA_THREADDEAD` state and cannot be resumed again. The Scheduler's `Complete` and `Failed` transitions release the `Ref` immediately — a Task in either state must never be re-enqueued. Enforce this in the `wireSuspend` one-shot guard.

### R-04: JS single-threaded assumption — no `wait()` allowed

On JS, off-Driver "completions" are actually microtasks or setTimeout callbacks that fire on the same JS thread. The `PlatformQueue` for JS uses a simple `ArrayDeque`. There is no need for — and the code must not attempt — any blocking wait (`CountDownLatch`, `LinkedBlockingDeque.take()`, etc.) because there is no second thread. The `JsDriver` pumps `runAllReady()` via `setInterval(0)`, which interleaves with JS Promise resolution. This is the correct model.

### R-05: LuaJ-style thread-per-coroutine is explicitly rejected

From `/home/hoangdinh/OSS/luau-scala/docs/research/topic-coroutines-on-jvm.md` §3.1: LuaJ uses one platform thread per coroutine with `wait()`/`notify()` handoff, resulting in ~1 MB stack per Task. This is catastrophic at scale (the SwitchCraft 250k-thread crash). The stackless + enqueue model here avoids this entirely: a parked Task is a `Task` object (~few hundred bytes on heap) and a `ReadyTask` in the queue. No OS threads are created per Task. This is the reason the `Resume` callback must **only enqueue** and never call `lx_resume` inline.

### R-06: Completion fires before register() returns

A degenerate but valid scenario: the async operation completes synchronously inside `register()` (before `register` returns to `wireSuspend`). The `resume` callback fires, checks `fired = false`, sets `fired = true`, and calls `runQueue.enqueue(ReadyTask(task, rv))`. Then `register` returns the `Cancel`. At this point `wireSuspend` calls `task.installCancel(cancel)` — but the Task is already `Queued`. The `clearCancel()` call inside `resume` already ran (`_cancel.getAndSet(null)`), so `installCancel` writes a stale `Cancel` that will never fire. This is safe but wasteful: the Cancel will be installed, then cleared when the Driver picks up the Task. To avoid the Cancel leaking across the resume, `wireSuspend` should check `task.state == Queued` after `task.installCancel(cancel)` and if so, clear it. Add this check.

### R-07: Piccolo-style ExecutorMode transitions — use them as a model

From `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-piccolo-rust.md` §6.1 and §11.5: piccolo models coroutines as a `ThreadMode` state machine where Yield is a mode transition, not a stack unwind. The `TaskState` enum in this plan is the direct equivalent. The key lesson: **no state transition should be performed by a function that can be called from an arbitrary context**. All transitions except `Parked → Queued` (the completion callback path, which is thread-safe via the enqueue/dequeue acquire edge) happen on the Driver thread.

### R-08: lua_newthread shares global_State — do not cross Isolate boundaries

`lua_newthread(L)` creates a thread sharing `L`'s `global_State`. Using a thread from Isolate A in the Scheduler for Isolate B would corrupt the GC (two `global_State`s are not involved; you'd be resuming a thread on the wrong state). The `Scheduler` is scoped to a single `state: B#State` and must only resume threads created from that state. Enforce via the `Scheduler` constructor's type parameter.

---

## 7. Out of Scope / Deferred

| Item | Owning plan |
|------|-------------|
| Multi-Driver worker pool (movable-state actor concurrency, ADR-0002) | Future plan — ADR-0002 is deferred |
| `task.spawn`, `task.defer`, `task.delay`, `task.wait`, `task.cancel` — the Roblox Task library built on top of the Scheduler | P07 `docs/plans/07-stdlib-and-task-library.md` |
| Luau standard libraries (base, math, string, coroutine, …) | P07 |
| `lua_resetthread` coroutine reuse (reset a dead coroutine for respawning) | P07 |
| Fuel / instruction budget for DoS prevention | Future plan |
| Dev-mode double-resume assertion (throw instead of no-op) | Can be added in this plan but is optional for MVP |
| Parent–child Task error propagation beyond logging (`task.cancel` semantics) | P07 |

---

## 8. References

The implementing agent should read the following documents in full before starting:

### ADRs (all in `/home/hoangdinh/OSS/luau-scala/docs/adr/`)

| File | Key constraint for this plan |
|------|------------------------------|
| `0001-embed-upstream-luau-via-slim-cpp-shim.md` | No protected calls across FFI; errors returned as status, never longjmp'd |
| `0002-movable-state-actor-concurrency.md` | Deferred but informs RunQueue design; off-Driver completions enqueue, never inline resume |
| `0003-stackless-task-model.md` | Tasks are stackless when parked; why thread-per-coroutine is rejected |
| `0004-coroutine-substrate-task-on-top.md` | Scheduler is blind; nesting disambiguates host-yield vs script-yield |
| `0005-deterministic-ref-lifetime-no-finalizer.md` | ThreadH is a Ref; released on Task terminal state |
| `0007-callback-based-async-and-tristate-native-return.md` | Resume is one-shot + enqueue-only; Cancel fires on teardown |

### CONTEXT.md glossary terms used throughout

`/home/hoangdinh/OSS/luau-scala/CONTEXT.md` — exact definitions for: **Scheduler**, **Task**, **Coroutine**, **Driver**, **Run queue**, **Suspension**, **Async primitive**, **Native function**, **Resume boundary**, **Ref**, **Isolate**, **Binding backend**.

### Research docs

| File | Relevant sections |
|------|-------------------|
| `/home/hoangdinh/OSS/luau-scala/docs/research/topic-coroutines-on-jvm.md` | §3.1 (LuaJ thread-per-coroutine, the rejected approach and the SwitchCraft 250k-thread crash), §4.1 (platform thread cost model — why stackless is mandatory) |
| `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-piccolo-rust.md` | §3.2 (trampoline loop as Scheduler analog), §3.3 (Fuel — instruction budget; deferred here but motivates future fuel work), §6.1 (ThreadMode state machine — direct inspiration for TaskState), §11.5 (Thread as State Machine lesson for Scala), §11.8 (pre-emptive scheduling via round-robin step — inspiration for future multi-Task fairness) |
| `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-official-cpp.md` | §4.2 (lua_State per-thread fields, status field), §4.8 (lua_newthread / lua_resume / lua_resetthread API), §8.3 (incremental GC pacing — informs why Driver must not spin too long without yielding to GC) |
