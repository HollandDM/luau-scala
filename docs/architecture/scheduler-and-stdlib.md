# Scheduler and Standard Library — Architecture Reference

> **Date:** 2026-06-10
>
> **Status:** Source on disk, **NOT wired into `build.mill`**. The `scheduler/jvm` and `stdlib/jvm`
> modules have complete Scala 3 source trees and test suites (verified as of this writing), but
> neither directory is declared as a Mill module in `build.mill`. Running `./mill __.compile`
> compiles only `core.jvm`, `core.js`, `panama`, `wasm`, and `shim`. The scheduler and stdlib
> sources are invisible to Mill until module definitions are added to `build.mill`.

This document describes the design, implementation, and known gaps of two subsystems:

- **Scheduler** (`scheduler/jvm/src/luau/scheduler/`) — the single-threaded Driver loop that owns
  a Luau state and drives Tasks through their lifecycle.
- **Standard Library / Task Library** (`stdlib/jvm/src/luau/stdlib/`) — the Roblox-compatible
  `task.*` API and standard library opening layer built on top of the Scheduler.

Both subsystems are pure Scala 3 over the `luau.core.Binding[H]` abstraction. They depend on no
Panama or WASM APIs directly; all platform specifics are hidden behind `Binding[H]`.

Use the domain vocabulary from `CONTEXT.md` throughout this document. Capital-T "Task", capital-D
"Driver", capital-R "Ref" and so on match that glossary exactly.

---

## Table of Contents

1. [Module Layout and Build Status](#1-module-layout-and-build-status)
2. [Scheduler Architecture](#2-scheduler-architecture)
   - 2.1 [Core Data Types](#21-core-data-types)
   - 2.2 [Driver Loop: runAllReady](#22-driver-loop-runallready)
   - 2.3 [Spawn Entry Points](#23-spawn-entry-points)
   - 2.4 [Suspend Wiring and the Async Primitive](#24-suspend-wiring-and-the-async-primitive)
   - 2.5 [Cancellation and Teardown](#25-cancellation-and-teardown)
   - 2.6 [Timer Support](#26-timer-support)
   - 2.7 [Error Policy](#27-error-policy)
3. [Scheduler Public API Summary](#3-scheduler-public-api-summary)
4. [Standard Library Architecture](#4-standard-library-architecture)
   - 4.1 [StdlibOpener and Opening Order](#41-stdlibopener-and-opening-order)
   - 4.2 [StdlibMask](#42-stdlibmask)
   - 4.3 [TaskLibrary: the Roblox task.* API](#43-tasklibrary-the-roblox-task-api)
   - 4.4 [LuaArgs Utility](#44-luaargs-utility)
5. [Test Coverage](#5-test-coverage)
   - 5.1 [Scheduler Unit Tests](#51-scheduler-unit-tests)
   - 5.2 [Stdlib Suite and Luau Test Resources](#52-stdlib-suite-and-luau-test-resources)
6. [API Against Core: What Has Drifted](#6-api-against-core-what-has-drifted)
7. [Known Bugs and Risks](#7-known-bugs-and-risks)
8. [Open Questions](#8-open-questions)

---

## 1. Module Layout and Build Status

```
scheduler/
  jvm/
    src/luau/scheduler/
      Scheduler.scala       — Driver loop, Run queue management, timer
      Task.scala            — Coroutine + scheduling state
      TaskState.scala       — 7-state lifecycle enum
      TaskHandle.scala      — Return type from spawn entry points
      ReadyTask.scala       — Queue item pairing Task with ResumeValues
      PlatformQueue.scala   — synchronized ArrayDeque wrapper
      ErrorPolicy.scala     — pluggable error handler trait
    test/src/luau/scheduler/
      SchedulerTests.scala  — 8 unit tests (TC-01 through TC-08)
      TestHelpers.scala     — TestBinding, ControllableAsync, factory helpers

stdlib/
  jvm/
    src/luau/stdlib/
      StdlibOpener.scala    — one-shot opener: openLibs → install → sandbox
      TaskLibrary.scala     — task.spawn/defer/delay/wait/cancel Native functions
      LuaArgs.scala         — typed argument helper (defined, not used internally)
    test/
      src/luau/stdlib/
        StdlibSuite.scala   — unit tests for call order, mask values, Scheduler API, global shape
      resources/luau/       — 15 Luau test scripts (see Section 5.2)
```

**build.mill (lines 1–60)** declares `core` (jvm + js), `panama`, `wasm`, and `shim`. No
`scheduler` or `stdlib` object exists. Both source trees compiled separately at some earlier point
(`.class` artifacts appear under `out/`), but there is no Mill target to compile, test, or publish
them via standard `./mill` commands.

When adding these modules to `build.mill`, the minimal additions are:

```scala
object scheduler extends Module {
  object jvm extends LuauCrossPlatformModule {
    override def moduleDeps = Seq(core.jvm)
    object test extends ScalaTests with TestModule.Munit {
      def mvnDeps = Seq(mvn"org.scalameta::munit::${build.munitVersion}")
    }
  }
}

object stdlib extends Module {
  object jvm extends LuauCrossPlatformModule {
    override def moduleDeps = Seq(core.jvm, scheduler.jvm)
    object test extends ScalaTests with TestModule.Munit {
      def mvnDeps = Seq(mvn"org.scalameta::munit::${build.munitVersion}")
    }
  }
}
```

The scheduler sources use `java.util.concurrent.atomic.AtomicLong`, `scala.collection.mutable`,
and `java.util.Timer`; all are JVM-only. The `jvm/` subdirectory reflects this intentional split.
A cross-platform `scheduler/js/` does not yet carry source files, so there is no Scala.js
cross-build for the Scheduler today.

---

## 2. Scheduler Architecture

### 2.1 Core Data Types

The Scheduler is built on five data types, each in its own file.

#### TaskState — the lifecycle enum

`scheduler/jvm/src/luau/scheduler/TaskState.scala`

```scala
enum TaskState:
  case Spawned
  case Queued
  case Running
  case Parked
  case Complete
  case Failed(error: String)
  case Cancelled
```

The documented transition diagram (from inline comments at line 6):

```
Spawned  → Running   (Driver picks Task from Run queue — only spawnImmediate path)
Running  → Parked    (lx_resume returned Yielded; async op registered)
Running  → Complete  (lx_resume returned Returned)
Running  → Failed    (lx_resume returned Error)
Parked   → Queued    (off-Driver completion fires resume callback)
Queued   → Running   (Driver dequeues ReadyTask)
Parked   → Cancelled (teardown fires Cancel)
Queued   → Cancelled (teardown before Driver picks it up)
```

`Failed` carries the error string. No transition from `Complete`, `Failed`, or `Cancelled` to
any other state is defined; these are terminal.

The state field `_state` is `@volatile` (`Task.scala:20`), which guarantees visibility across
threads for read; all writes happen on the Driver thread except the `Queued` write inside the
`Resume` callback (which is safe because the `Parked → Queued` transition inside `wireSuspend` is
the only off-Driver write and it happens before the `runQueue.enqueue` release fence).

#### Task — the scheduling unit

`scheduler/jvm/src/luau/scheduler/Task.scala`

```scala
final class Task[H](
  val threadRef: Ref[H],
  val thread:    H,
  val parent:    Option[Task[H]],
  val id:        Long,
)
```

- `threadRef` is a `Ref[H]` created immediately after `binding.newThread(state)`. It pins the
  `lua_newthread` Coroutine in the Luau registry so it cannot be garbage-collected. Closed exactly
  once when the Task reaches a terminal state (`Task.releaseThread()`, line 40).
- `thread` is the raw platform handle (`H`) passed to `binding.resume`. It is the coroutine thread
  handle, not the main state.
- `parent` is optional. The `errorPolicy` at the Scheduler level handles error forwarding; parent
  is provided for future propagation logic.
- `id` is a monotonic `AtomicLong` counter (`Scheduler.scala:29`).
- `_cancel` is an `AtomicReference[Cancel | Null]` used for thread-safe install and fire of the
  cancellation hook. The `getAndSet(null)` idiom ensures exactly-once firing.

#### ReadyTask and ResumeValues

`scheduler/jvm/src/luau/scheduler/ReadyTask.scala`

```scala
final case class ReadyTask[H](task: Task[H], values: ResumeValues)

enum ResumeValues:
  case None
  case Success(result: LuaValue)
  case Failure(error: LuaError)
  case SuspendValue(result: LuaValue)
```

`ResumeValues.None` is used for initial Task enqueue (no arguments to push before the first
resume). `SuspendValue` is used when a `Suspend` completion fires successfully.
`Failure` is used when the `Resume` callback delivers a `Left(LuaError)`.

`ResumeValues.Success` is defined but never constructed anywhere in the current codebase (verified
by `rg 'ResumeValues.Success'` returning only the definition and match arms at
`Scheduler.scala:248,267`). It was planned for a two-value resume path (`ok: Boolean + result`)
that was never wired.

#### PlatformQueue

`scheduler/jvm/src/luau/scheduler/PlatformQueue.scala`

```scala
final class PlatformQueue[A]:
  private val q = ArrayDeque[A]()
  def enqueue(item: A): Unit      = synchronized { q.addOne(item) }
  def dequeueOption(): Option[A]  = synchronized { ... }
  def isEmpty: Boolean            = synchronized { q.isEmpty }
```

Wraps a `scala.collection.mutable.ArrayDeque` with `synchronized` guards. All three methods are
synchronized; this is safe for the JVM multi-threaded case where off-Driver completion threads
call `enqueue` while the Driver thread calls `dequeueOption`. On Scala.js, `synchronized` is a
no-op, making this a simple ArrayDeque without overhead. The queue holds `ReadyTask[H]` items
representing Tasks that are ready to be resumed.

#### TaskHandle

`scheduler/jvm/src/luau/scheduler/TaskHandle.scala`

```scala
final class TaskHandle[H](
  val threadRef: Ref[H],
  val task:      Task[H],
)
```

Returned by `spawnImmediate`, `deferTask`, and `scheduleDelayed`. The `threadRef` gives callers
a Luau registry key they can push as a thread value (for example, to return the coroutine handle
from `task.spawn` or `task.defer` at the Luau level). See the known bug about closed-Ref push in
Section 7.

### 2.2 Driver Loop: runAllReady

`scheduler/jvm/src/luau/scheduler/Scheduler.scala:179`

```scala
def runAllReady(): Int =
  var count = 0
  while
    runQueue.dequeueOption() match
      case Some(rt) => resumeTask(rt); count += 1; true
      case None     => false
  do ()
  count
```

`runAllReady` is the Driver loop. It drains `PlatformQueue[ReadyTask[H]]` until empty, returning
the count of Tasks resumed. Per ADR-0004, the Scheduler is a blind drain: it pops a `ReadyTask`,
resumes it, and parks it based on the `ResumeResult`. It never inspects yield payloads.

The internal `resumeTask` method (`Scheduler.scala:190`) handles one Task:

1. If `task.state == TaskState.Cancelled`, return immediately (skip — Task was cancelled between
   enqueue and dequeue, test TC-08).
2. Transition to `Running`, set `_currentTask = Some(task)`.
3. Push `ResumeValues` onto the thread stack via `pushResumeValues`.
4. Call `binding.resume(task.thread, nargs)`.
5. Set `_currentTask = None`.
6. Match on `ResumeResult`:
   - `Returned(_)` → `Complete`, remove from `liveTasks`, `releaseThread()`.
   - `Yielded(_)` → call `takePendingSuspend()`. If a `Suspend` was registered, transition to
     `Parked` and call `wireSuspend`. If not (bare `coroutine.yield`), transition to `Parked`
     with no re-queue — the Task parks permanently (matches Roblox behavior per ADR-0004).
   - `Error(err)` → `Failed`, remove from `liveTasks`, `releaseThread()`, invoke `errorPolicy`.

The `currentTask` accessor (`Scheduler.scala:36`) is the mechanism by which `task.wait` in
`TaskLibrary` identifies which Task to re-enqueue when the timer fires. This slot is `None`
outside a `resumeTask` call.

The `pendingSuspend` slot (`Scheduler.scala:43`) is a single `Option[NativeFnResult.Suspend]`
cell. `setPendingSuspend` and `takePendingSuspend` are `private[scheduler]` — they are called by
test code directly in `SchedulerTests` (which sets up a `ControllableAsync.suspend` before spawn)
and are intended to be called by the Binding backend's Native function dispatcher after
`binding.resume` returns `Yielded`.

### 2.3 Spawn Entry Points

The Scheduler exposes four ways to create and enqueue Tasks:

#### spawn

`Scheduler.scala:55`

```scala
def spawn(parent: Option[Task[H]] = None): Task[H]
```

Creates a new Luau thread via `binding.newThread(state)`, wraps it in a `Task`, transitions to
`Queued`, and enqueues `ReadyTask(task, ResumeValues.None)`. No function is pushed onto the
thread stack before the enqueue. When the Driver later dequeues this `ReadyTask` and calls
`binding.resume(task.thread, 0)`, the thread has an empty stack — behavior depends on the Luau
Runtime (under `FakeBinding` the programmed resume sequence is used; under a real backend this
would be an error unless the caller pushed something between `spawn()` and `runAllReady()`).
This entry point is used in `SchedulerTests` as a raw controllable harness and is not called by
`TaskLibrary`.

#### spawnImmediate

`Scheduler.scala:67`

```scala
def spawnImmediate(fnRef: Ref[H], extraArgs: List[Ref[H]]): TaskHandle[H]
```

Creates a thread, pushes `fnRef` and `extraArgs` onto it, closes the input Refs, then immediately
calls `binding.resume(rawThread, nargs)` **synchronously** — not via the Run queue. The result
is handled inline:

- `Returned` → `Complete`, `releaseThread()` (closes `threadRef`).
- `Yielded` → `Parked`. The pending Suspend slot is **not** consumed here (unlike in
  `resumeTask`); the Suspend must be wired by a higher-level caller or will be left dangling.
- `Error` → `Failed`, `releaseThread()`, `errorPolicy`.

The `TaskHandle` is returned with the (possibly already-closed) `threadRef`. This synchronous
execution is what gives `task.spawn` its Roblox "immediate" semantics — the spawned function
runs to its first yield before `spawnImmediate` returns.

#### deferTask

`Scheduler.scala:101`

```scala
def deferTask(fnRef: Ref[H], extraArgs: List[Ref[H]]): TaskHandle[H]
```

Creates a thread, pushes `fnRef` and `extraArgs` onto it, closes the input Refs, transitions to
`Queued`, and enqueues `ReadyTask(task, ResumeValues.None)`. Unlike `spawnImmediate`, execution
is deferred to the next `runAllReady` call. This is `task.defer` semantics.

#### scheduleDelayed

`Scheduler.scala:120`

```scala
def scheduleDelayed(fnRef: Ref[H], extraArgs: List[Ref[H]], seconds: Double): TaskHandle[H]
```

Creates a thread, pushes function and args, transitions to `Parked`, and schedules a
`java.util.Timer` task to enqueue `ReadyTask(task, ResumeValues.None)` after `seconds * 1000`
milliseconds. Checks `task.state == TaskState.Parked` before enqueue to guard against
cancellation. This is `task.delay` semantics.

### 2.4 Suspend Wiring and the Async Primitive

The Async primitive (ADR-0007) flows through three layers:

1. A Native function (e.g. `task.wait`) returns `NativeFnResult.Suspend(register)`.
2. The Shim trampoline calls `lua_yield(k)` in pure C; `binding.resume` returns
   `ResumeResult.Yielded`.
3. The Driver calls `takePendingSuspend()`, retrieves the `Suspend`, and calls `wireSuspend`.

`wireSuspend` (`Scheduler.scala:223`):

```scala
private def wireSuspend(task: Task[H], register: Resume => Cancel): Unit =
  @volatile var fired = false
  val resume: Resume = Resume { (either: Either[LuaError, LuaValue]) =>
    if !fired then
      fired = true
      task.clearCancel()
      task.setState(TaskState.Queued)
      val rv = either match
        case Right(value) => ResumeValues.SuspendValue(value)
        case Left(err)    => ResumeValues.Failure(err)
      runQueue.enqueue(ReadyTask[H](task, rv))
  }
  val cancel: Cancel = register(resume)
  task.installCancel(cancel)
  if task.state == TaskState.Queued then
    task.clearCancel()
```

The `@volatile var fired` flag enforces the one-shot invariant: a second call to `resume` is a
no-op. This matches ADR-0007 ("double-resume would corrupt the Coroutine").

The race guard at the end (`if task.state == TaskState.Queued then task.clearCancel()`) handles
the case where the async operation completes synchronously inside `register` before `register`
returns. In that scenario, `resume` fires, sets `fired = true`, transitions the Task to `Queued`,
and enqueues a `ReadyTask` — all before `task.installCancel(cancel)` runs. After `register`
returns `cancel`, `wireSuspend` installs it, then immediately clears it because the Task is
already `Queued`. Without this guard, a stale `Cancel` would sit on the Task and might fire on
the next dequeue, incorrectly cancelling an already-completed re-enqueue.

The critical integration gap between this mechanism and the Panama backend is described in
Section 7 (Suspend bridge missing).

### 2.5 Cancellation and Teardown

#### cancelTask

`Scheduler.scala:155`

```scala
def cancelTask(task: Task[H]): Unit =
  val prev = task.state
  if prev == TaskState.Parked || prev == TaskState.Queued then
    task.setState(TaskState.Cancelled)
    task.fireCancel()
    liveTasks.remove(task.id)
    task.releaseThread()
```

Only `Parked` and `Queued` Tasks can be cancelled. `Running`, `Complete`, `Failed`, and
`Cancelled` Tasks are ignored silently. `fireCancel` calls `task._cancel.getAndSet(null)()` —
if a `Cancel` was installed by `wireSuspend`, it fires here, stopping the underlying async
operation (for example, cancelling a `java.util.TimerTask`).

#### cancelThread

`Scheduler.scala:163`

```scala
def cancelThread(threadRef: Ref[H]): Unit =
  liveTasks.values.find { t =>
    !t.threadRef.isClosed && t.threadRef.registryKey == threadRef.registryKey
  }.foreach(cancelTask)
```

Linear scan of `liveTasks` looking for a Task whose `threadRef` has the same registry key as the
given `Ref`. This is O(n) in the number of live Tasks. Called by `TaskLibrary.registerCancelFn`
for every `task.cancel` Lua call.

#### close

`Scheduler.scala:272`

```scala
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
```

Drains the Run queue (discarding pending Tasks), cancels all remaining live Tasks, stops the
`java.util.Timer`, and calls `binding.closeState`. Must be called on the Driver thread.

### 2.6 Timer Support

`Scheduler.scala:144`

```scala
private val timer = new Timer("luau-scheduler-timer", true)

def scheduleTimer(seconds: Double)(callback: => Unit): Cancel =
  val ms = (seconds * 1000).toLong
  val timerTask = new TimerTask:
    def run(): Unit = callback
  timer.schedule(timerTask, ms)
  Cancel { () => timerTask.cancel(); () }
```

A single `java.util.Timer` (daemon thread, name `"luau-scheduler-timer"`) serves all delayed
Tasks and all `task.wait` calls. The timer is created at `Scheduler` construction and stopped by
`close()`. Timer callbacks must not call `binding.resume` inline; they must only enqueue onto the
Run queue (ADR-0002 / ADR-0004 invariant).

`task.wait` uses `scheduleTimer` inside its `Suspend.register` closure (`TaskLibrary.scala:117`):
when the timer fires, it calls `scheduler.enqueueResume(currentTask, Right(LuaValue.Number(elapsed)))`,
which sets the Task to `Queued` and enqueues a `ReadyTask` with `ResumeValues.SuspendValue(elapsed)`.

### 2.7 Error Policy

`scheduler/jvm/src/luau/scheduler/ErrorPolicy.scala`

```scala
trait ErrorPolicy:
  def onTaskError(task: Task[?], error: String): Unit

object ErrorPolicy:
  val logAndDiscard: ErrorPolicy = (task, error) =>
    Console.err.println(s"[luau-scheduler] Task ${task.id} failed: $error")
```

A pluggable trait. The default `logAndDiscard` prints to stderr. Tests supply a custom
`ErrorPolicy` to capture the error message (TC-04 in `SchedulerTests.scala:59`). Parent-Task
error propagation (the `task.parent` field) is not implemented; the field exists for future use.

---

## 3. Scheduler Public API Summary

| Signature | Description |
|-----------|-------------|
| `Scheduler[H](binding, state, errorPolicy)` | Constructor. `errorPolicy` defaults to `logAndDiscard`. |
| `spawn(parent): Task[H]` | Create Task, enqueue with no function pushed. Driver-thread only. |
| `spawnImmediate(fnRef, extraArgs): TaskHandle[H]` | Create Task, push fn, resume synchronously. Returns after first yield or completion. |
| `deferTask(fnRef, extraArgs): TaskHandle[H]` | Create Task, push fn, enqueue for next drain. |
| `scheduleDelayed(fnRef, extraArgs, seconds): TaskHandle[H]` | Create Task, push fn, park until timer fires. |
| `runAllReady(): Int` | Drain Run queue; returns count resumed. Driver-thread only. |
| `enqueueResume(task, result): Unit` | Post async completion. Thread-safe; enqueue-only. |
| `cancelTask(task): Unit` | Cancel Parked or Queued Task. Driver-thread only. |
| `cancelThread(threadRef): Unit` | Cancel by Ref registry key (O(n) scan). Driver-thread only. |
| `currentTask: Option[Task[H]]` | Task being resumed, or None outside a resume call. |
| `scheduleTimer(seconds)(callback): Cancel` | Fire callback after delay. Returns Cancel. |
| `setPendingSuspend(s): Unit` | Write Suspend into per-state slot. `private[scheduler]`. |
| `takePendingSuspend(): Option[Suspend]` | Read and clear Suspend slot. `private[scheduler]`. |
| `close(): Unit` | Cancel all Tasks, stop timer, close state. Driver-thread only. |

---

## 4. Standard Library Architecture

### 4.1 StdlibOpener and Opening Order

`stdlib/jvm/src/luau/stdlib/StdlibOpener.scala`

```scala
object StdlibOpener:
  def open[H](
    binding:   Binding[H],
    state:     H,
    scheduler: Scheduler[H],
    mask:      Int = StdlibMask.Standard,
  ): Unit =
    binding.openLibs(state, mask)
    TaskLibrary.install(binding, state, scheduler)
    binding.sandbox(state)
```

The three steps are sequential and must not be reordered:

1. `binding.openLibs(state, mask)` — calls `lx_openlibs` in the Shim, which calls the
   appropriate `luaopen_*` functions and nulls out unsafe globals (`io`, `os.execute`,
   `os.exit`, `os.getenv`, `package`, `dofile`, `loadfile`, `require`).
2. `TaskLibrary.install(binding, state, scheduler)` — installs the `task` global table with
   five Native functions. Must happen before `sandbox` so that the `task` table is frozen along
   with `_G`.
3. `binding.sandbox(state)` — calls `lx_sandbox` in the Shim, which calls `luaL_sandbox`.
   This sets `safeenv = 1` on the Luau state and freezes the global table and all opened library
   tables. Any write to `_G` from a script after this point raises a Lua error.

The `StdlibSuite` test (`StdlibSuite.scala:12`) verifies this ordering using a `CallOrderBinding`
subclass that records calls:

```
assert(order.indexOf("openLibs") < order.indexOf("sandbox"))
```

### 4.2 StdlibMask

`StdlibMask` defines 11 bit flags (`stdlib/jvm/src/luau/stdlib/StdlibOpener.scala:7`):

| Constant | Bit | Library |
|----------|-----|---------|
| `Base` | `1 << 0` | base globals (`assert`, `error`, `print`, `pcall`, …) |
| `Math` | `1 << 1` | `math.*` |
| `String` | `1 << 2` | `string.*` |
| `Table` | `1 << 3` | `table.*` |
| `Bit32` | `1 << 4` | `bit32.*` |
| `Utf8` | `1 << 5` | `utf8.*` |
| `Os` | `1 << 6` | `os.clock/time/date/difftime` (unsafe members stripped) |
| `Coroutine` | `1 << 7` | `coroutine.*` |
| `Vector` | `1 << 8` | `vector.*` |
| `Buffer` | `1 << 9` | `buffer.*` |
| `Debug` | `1 << 10` | `debug.*` |

`StdlibMask.Standard` (`line 18`) is `Base | Math | String | Table | Bit32 | Utf8 | Os | Coroutine | Vector | Buffer` — explicitly excludes `Debug`. The `StdlibSuite` test verifies this mask (`StdlibSuite.scala:29`).

### 4.3 TaskLibrary: the Roblox task.* API

`stdlib/jvm/src/luau/stdlib/TaskLibrary.scala`

`TaskLibrary.install` creates a Luau table, registers five Native functions as fields, and
installs it as the `task` global:

```scala
object TaskLibrary:
  def install[H](binding: Binding[H], state: H, scheduler: Scheduler[H]): Unit =
    binding.newTable(state)
    binding.pushString(state, "spawn");  registerSpawnFn(binding, state, scheduler);  binding.rawSet(state, -3)
    binding.pushString(state, "defer");  registerDeferFn(binding, state, scheduler);  binding.rawSet(state, -3)
    binding.pushString(state, "delay");  registerDelayFn(binding, state, scheduler);  binding.rawSet(state, -3)
    binding.pushString(state, "wait");   registerWaitFn(binding, state, scheduler);   binding.rawSet(state, -3)
    binding.pushString(state, "cancel"); registerCancelFn(binding, state, scheduler); binding.rawSet(state, -3)
    binding.setGlobal(state, "task")
```

Each `rawSet(state, -3)` pops the key–value pair into the table sitting at index -3. After
all five fields are set, `setGlobal(state, "task")` pops the table into the global environment.

#### task.spawn

`TaskLibrary.scala:38`

Reads a `LuaType.Function` at argument position 1. Calls `binding.pushCopy` + `binding.ref` to
capture a `Ref[H]` for the function, then does the same for extra args. Calls
`scheduler.spawnImmediate(fnRef, extraRefs)`. Then pushes the returned `handle.threadRef` via
`handle.threadRef.push()` and returns `Return(1)`.

**Known bug** (see Section 7): if the spawned Task completes synchronously inside
`spawnImmediate`, `task.releaseThread()` is called, closing `threadRef`. The subsequent
`handle.threadRef.push()` call will then throw because `Ref.push()` requires `!closed`.

#### task.defer

`TaskLibrary.scala:59`

Same as `task.spawn` but calls `scheduler.deferTask(fnRef, extraRefs)`. The Task is enqueued
for the next `runAllReady` call rather than run immediately. Returns the thread Ref as a Luau
thread value.

#### task.delay

`TaskLibrary.scala:80`

Reads a `Double` at argument position 1 (seconds), a `LuaType.Function` at position 2, and
extra args from position 3 onward. Calls `scheduler.scheduleDelayed(fnRef, extraRefs, seconds)`.
Returns the thread Ref.

#### task.wait

`TaskLibrary.scala:102`

The only Suspend-producing function. Implementation:

```scala
val fn: NativeFn[H] = (s, nargs) =>
  val seconds = if nargs >= 1 then binding.toNumber(s, 1).getOrElse(0.0) else 0.0
  scheduler.currentTask match
    case None =>
      Fail(LuaValue.LuaString.fromUtf8(
        "task.wait called from a coroutine not owned by the Scheduler; " +
        "behavior is undefined (see ADR-0004)"
      ))
    case Some(currentTask) =>
      Suspend { resume =>
        val t0 = System.nanoTime()
        val cancel: Cancel = scheduler.scheduleTimer(seconds) {
          val elapsed = (System.nanoTime() - t0) / 1e9
          scheduler.enqueueResume(currentTask, Right(LuaValue.Number(elapsed)))
        }
        cancel
      }
```

The check on `scheduler.currentTask` implements ADR-0004's documented Roblox-ism: if `task.wait`
is called from a script-created Coroutine (not a Scheduler-owned Task), `currentTask` is `None`
and `Fail` is returned with a message referencing ADR-0004. The test resource
`task-raw-coroutine-wrinkle.luau` pins this error message.

`t0 = System.nanoTime()` is captured outside the `Suspend` closure, before yielding. The timer
callback lambda captures `t0` and computes `elapsed` when it fires. This is safe for visibility
because `scheduleTimer` uses a `java.util.Timer`, whose thread-safe task submission (`timer.schedule`)
provides a happens-before edge that makes `t0` visible to the timer thread.

`scheduler.enqueueResume(currentTask, Right(LuaValue.Number(elapsed)))` is called inside the
timer callback. This sets the Task to `Queued` and enqueues `ReadyTask(task, ResumeValues.SuspendValue(elapsed))`.
On the next `runAllReady`, the Driver pushes `elapsed` onto the thread stack and calls
`binding.resume(task.thread, 1)`, making it the return value of `task.wait` at the Lua level.

#### task.cancel

`TaskLibrary.scala:125`

Reads argument 1. If it is a `LuaType.Thread`, captures it as a `Ref[H]`, calls
`scheduler.cancelThread(ref)`, closes the Ref, and returns `Return(0)`. Any other type (including
`nil`) silently returns `Return(0)` — this is the Roblox-parity behavior (no error on wrong type
or nil). The test `task-cancel-noop.luau` covers `nil` and already-completed thread arguments.

### 4.4 LuaArgs Utility

`stdlib/jvm/src/luau/stdlib/LuaArgs.scala`

```scala
final class LuaArgs[H](
  val binding: Binding[H],
  val state:   H,
  val nargs:   Int,
):
  def readNumber(pos: Int): Option[Double]
  def readFunction(pos: Int): Either[LuaError, Ref[H]]
  def readThread(pos: Int): Option[Ref[H]]
  def pushRefValue(thread: H, ref: Ref[H]): Unit
```

A typed argument-reading wrapper. Delegates to `Binding[H]` methods with type-checked fallbacks.
`LuaArgs` is defined but not used by `TaskLibrary` itself; `TaskLibrary` calls `binding.typeAt`,
`binding.toNumber`, `binding.pushCopy`, and `binding.ref` directly. `LuaArgs` is dead code until
future Native function authors adopt it.

---

## 5. Test Coverage

### 5.1 Scheduler Unit Tests

`scheduler/jvm/test/src/luau/scheduler/SchedulerTests.scala`

All eight tests use `TestBinding` (a `Binding[FakeState]` over `FakeBinding` with a programmable
`resume` sequence) and `ControllableAsync` (which captures the `Resume` callback for off-Driver
firing):

| Test ID | Scenario | Key assertion |
|---------|----------|---------------|
| TC-01 | Spawn → immediate complete | `task.state == Complete` after `runAllReady()` |
| TC-02 | Suspend → park → completion → re-resume | `Parked` after first drain; `Complete` after second |
| TC-03 | One-shot Resume | Double call does not enqueue twice; `runAllReady()` returns 1 |
| TC-04 | Error → Failed → errorPolicy | `task.state == Failed("script error")`; policy captured error |
| TC-05 | `close()` fires Cancel for parked Tasks | `async.cancelled == true` after `close()` |
| TC-06 | Bare `coroutine.yield` parks permanently | `task.state == Parked`; queue empty |
| TC-07 | Two Tasks interleave | Both `Complete` after single `runAllReady()` |
| TC-08 | Cancelled Task skipped by Driver | No `lx_resume` call; state stays `Cancelled` |

`TestHelpers.scala` provides `TestBinding.programResumes(results*)` to preload a sequence of
`ResumeResult` values returned in order. `ControllableAsync` holds the captured `Resume` at
`@volatile var resume: Resume | Null`.

Note: TC-02 and TC-03 set `sched.setPendingSuspend(async.suspend)` before `sched.spawn()`. This
is how the tests supply a `Suspend` to the Scheduler without going through the actual Panama/WASM
Native function dispatch path.

### 5.2 Stdlib Suite and Luau Test Resources

`stdlib/jvm/test/src/luau/stdlib/StdlibSuite.scala`

The Scala test suite covers:

- `StdlibOpener.open` call ordering (using `CallOrderBinding`)
- `StdlibMask.Standard` bit values (includes Base through Buffer, excludes Debug)
- `Scheduler.spawnImmediate` state after synchronous completion
- `Scheduler.deferTask` enqueues in `Queued` state
- `Scheduler.scheduleDelayed` creates Task in `Parked` state
- `Scheduler.cancelTask` on Parked and Complete Tasks
- `Scheduler.currentTask` is `None` outside a resume
- `Scheduler.enqueueResume` transitions Task to `Queued`
- `Scheduler.cancelThread` finds Task by threadRef registry key
- `TaskLibrary.install` creates a `LuaType.Table` at `task` global

The 15 Luau scripts in `stdlib/jvm/test/resources/luau/` are present on disk and serve as
specification and regression documentation for the Roblox behavioral contract. They are not
loaded by any Scala test runner visible in the codebase today — no `StdlibIntegrationSuite`
exists that runs them against a real Binding backend. They require either a Panama or WASM
backend to execute:

| Script | Behavior pinned |
|--------|----------------|
| `task-spawn-immediacy.luau` | `task.spawn` resumes inline before returning |
| `task-defer-lateness.luau` | `task.defer` does not run until after current task yields |
| `task-wait-timing.luau` | `task.wait(0.05)` returns elapsed ≥ 0.05 s |
| `task-spawn-order.luau` | spawn runs before defer (A before B) |
| `task-wait-zero.luau` | `task.wait(0)` yields and allows deferred tasks to run |
| `task-cancel.luau` | `task.cancel` stops a `task.delay` callback |
| `task-cancel-noop.luau` | Cancelling completed Task and `nil` are both no-ops |
| `task-raw-coroutine-wrinkle.luau` | ADR-0004: `task.wait` in raw Coroutine → documented error |
| `e2e-combined.luau` | Combined: buffer, task.spawn, math, string.format, table |
| `base-libs.luau` | math/string/table/bit32/utf8/os/coroutine/buffer globals present |
| `coroutine-substrate.luau` | Raw `coroutine.create/resume/yield/status` without Task layer |
| `sandbox-denial.luau` | io/os.execute/os.exit/os.getenv/package/dofile/loadfile are nil |
| `global-freeze.luau` | `_G` mutation raises error after sandbox |
| `math-roundtrip.luau` | math stdlib correctness |
| `buffer-test.luau` | Buffer create/len/writei32/readu32/fill round-trip |

---

## 6. API Against Core: What Has Drifted

The Scheduler and stdlib were written against the `Binding[H]` trait as defined in
`core/jvm/src/luau/core/Binding.scala`. As of this writing the trait's method set includes
`openLibs(state, mask)` and `sandbox(state)` (lines 98–101), which were added for Plan P07.
These methods are present in the current `Binding` trait; there is no drift on those APIs.

The plan documents (P06, P07) used an earlier `Binding` shape that differed in two ways from
the actual source:

1. **`takePendingSuspend` on `Binding` (planned, not implemented):** Plan P06 proposed adding
   `setPendingSuspend(state, s)` and `takePendingSuspend(state)` to the `Binding` trait itself.
   The actual implementation places these as `private[scheduler]` methods on `Scheduler`
   (`Scheduler.scala:45,48`), not on `Binding`. The Scheduler's `pendingSuspend` field is a
   `private var` in the `Scheduler` instance, written by test code via `setPendingSuspend` and by
   the Driver internally via `takePendingSuspend`. This is a deliberate simplification — the slot
   lives in the Scheduler rather than in the Binding.

2. **`Binding.resume` signature:** The actual trait declares
   `def resume(thread: H, nargs: Int): ResumeResult` (`Binding.scala:21`). The Plan P06 spec
   showed `lx_resume(state, thread, nargs)` with a separate `state` argument. In the actual
   implementation, `PanamaState.resume` passes `L` (the main state) as the first argument to
   `lx_resume` and `thread` as the second (`PanamaState.scala:57`):
   ```scala
   val rc = LxHandles.lx_resume.invokeExact(L, thread, nargs, nResultsSeg).asInstanceOf[Int]
   ```
   The `Binding.resume(thread, nargs)` signature hides `L` inside the `PanamaState` object, which
   is correct. The Scheduler calls `binding.resume(task.thread, nargs)` with only the coroutine
   thread handle.

3. **`PlatformQueue` implementation:** The plan spec described a `LinkedBlockingDeque` for JVM
   and a plain `ArrayDeque` for JS with an `expect`/`actual` pattern. The actual implementation
   (`PlatformQueue.scala`) uses a `scala.collection.mutable.ArrayDeque` with `synchronized` guards
   on all three methods — a simpler cross-platform approach that avoids `expect`/`actual` but uses
   JVM synchronized blocks on both platforms (a no-op on Scala.js).

4. **`ResumeValues.Success` vs `SuspendValue`:** The Plan P06 spec named the success case
   `Success(result: LuaValue)`. The actual implementation has both `Success` and `SuspendValue`
   (`ReadyTask.scala:13`). `SuspendValue` is the case actually constructed (by `wireSuspend` and
   `enqueueResume`). `Success` is defined and matched in `pushResumeValues`
   (`Scheduler.scala:248`) but never constructed — it is dead code (see Section 7).

5. **Panama Suspend bridge:** Plan P06 proposed that `Binding.takePendingSuspend(state)` would
   be called by the Driver after `lx_resume` returns `Yielded`. In the Panama backend,
   `NativeFnDispatcher.dispatch` stores the `Suspend` in `SuspendRegistry` and sets
   `PanamaState.lastYieldToken` (`NativeFnDispatcher.scala:53–56`). There is no code path that
   reads `lastYieldToken` and calls `Scheduler.setPendingSuspend`. This gap means the
   Scheduler's `wireSuspend` is never reached when running against the Panama backend. See
   Section 7 for the full analysis.

---

## 7. Known Bugs and Risks

### BUG — Closed-Ref push in task.spawn (`TaskLibrary.scala:53`)

When `task.spawn` is called with a function that returns immediately (no yield):

1. `scheduler.spawnImmediate(fnRef, extraRefs)` runs the Task synchronously.
2. Inside `spawnImmediate`, `ResumeResult.Returned` is matched at line 85.
3. `task.releaseThread()` is called at line 88, which calls `threadRef.close()`.
4. `spawnImmediate` returns `TaskHandle(threadRef, task)`.
5. Back in `registerSpawnFn`, `handle.threadRef.push()` is called at line 53.
6. `Ref.push()` asserts `require(!closed)` — the `Ref` is already closed. This throws an
   exception inside the Native function body; the Shim converts it to a Lua error, so
   `task.spawn(function() end)` would silently fail rather than return the thread handle.

**Impact:** Any script calling `task.spawn` with a synchronously-completing function will receive
a Lua error instead of the expected thread handle.

**Fix options:** (a) Do not call `releaseThread()` inside `spawnImmediate` for the `Returned`
case; let the caller or GC/close handle it. (b) Return `Option[Ref[H]]` from `TaskHandle` when
the Task completed synchronously. (c) Add an `isClosed` guard in `registerSpawnFn` before
calling `push()`.

### BUG — Suspend bridge missing for Panama backend

The Panama backend's `NativeFnDispatcher.dispatch` (`NativeFnDispatcher.scala:52`) stores a
`NativeFnResult.Suspend` in the `SuspendRegistry` and records a token in
`PanamaState.lastYieldToken`:

```scala
case s @ NativeFnResult.Suspend(_) =>
  val panamaState = ps
  val token = panamaState.suspendRegistry.allocToken(s)
  panamaState.lastYieldToken = token
  lx_set_suspend_token.invokeExact(state, thread, token): Unit
  LX_SUSPEND
```

There is no code that reads `panamaState.lastYieldToken`, calls
`panamaState.suspendRegistry.consume(token)`, and calls `scheduler.setPendingSuspend(suspend)`.
The Scheduler reads `takePendingSuspend()` after `binding.resume` returns `Yielded`
(`Scheduler.scala:208`), but this slot is never populated by the Panama path.

Consequence: under Panama, `Scheduler.wireSuspend` is never called. `task.wait` causes the Task
to yield (the Shim calls `lua_yield(k)`) and the Driver transitions the Task to `Parked` with no
Suspend registered. The Task stays Parked forever. All `SuspendResumeTest` tests in the Panama
test suite are `.ignore`'d, which confirms this is a known-broken path.

**Fix:** One approach is to add a post-resume hook in the Driver. After `binding.resume` returns
`Yielded`, check if `binding` is a `PanamaState`, read `lastYieldToken`, consume from
`suspendRegistry`, and call `setPendingSuspend`. A cleaner approach is to override `resume` in
`PanamaState` to check the token and populate a slot on the `Scheduler` passed in at construction
(but this would create a circular dependency). The simplest fix is to add `setPendingSuspendFromToken`
to `Binding` and have `PanamaState` implement it.

### DEAD CODE — `ResumeValues.Success`

`ResumeValues.Success(result: LuaValue)` is defined in `ReadyTask.scala:15` and matched in
`Scheduler.scala:248,267`, but no code in the codebase constructs it. `enqueueResume` produces
`SuspendValue` or `Failure`; `wireSuspend` produces `SuspendValue` or `Failure`. The `Success`
case exists from the original design for a two-value resume `(ok: Boolean, result: LuaValue)` but
that path was never wired.

### RISK — O(n) `cancelThread` scan

`cancelThread` iterates all values in `liveTasks` to find a matching `threadRef.registryKey`.
At typical scales (tens or hundreds of concurrent Tasks) this is not a problem, but `cancelThread`
is called for every `task.cancel` Lua call. A Task-keyed HashMap by registry key would reduce
this to O(1).

### RISK — Single `java.util.Timer` for all delays

One `Timer` daemon thread (`"luau-scheduler-timer"`) handles all `scheduleDelayed` and
`scheduleTimer` calls. If a timer callback blocks (it must not — it should only enqueue), all
other timers stall. Timer is also not restarted if it enters an invalid state. For high-throughput
use, a `ScheduledThreadPoolExecutor` with a single thread would be more robust.

### RISK — `task.cancel` silently ignores non-Thread arguments

`registerCancelFn` returns `Return(0)` for any non-Thread argument (line 138). This matches
Roblox behavior (nil is a no-op), but passing a number or string also silently succeeds. Scripts
that pass wrong types get no error indication.

### RISK — Scheduler sources are JVM-only

`scheduler/jvm/src/luau/scheduler/` uses `java.util.concurrent.atomic.AtomicLong`,
`java.util.Timer`, and `synchronized` (though `synchronized` is a no-op on Scala.js, the others
are JVM-only). If `scheduler/jvm` were cross-compiled to Scala.js, the build would fail on
`AtomicLong` and `Timer`. The `js/` subdirectory under `scheduler/` exists but is empty — a
Scala.js-specific implementation replacing `AtomicLong` with a plain `Long` and `Timer` with
`setTimeout` would be required.

### RISK — Not wired into build.mill

No Mill module definitions exist for `scheduler` or `stdlib`. Source files compile and tests
reference `FakeBinding` from `core.jvm.fake`, but there is no `./mill scheduler.jvm.test` target.
Adding module definitions is the prerequisite for CI coverage.

---

## 8. Open Questions

1. **When will scheduler/stdlib be wired into `build.mill`?** The source is complete and tests
   compile conceptually (they reference `luau.core.fake.FakeBinding` which exists). The blocker
   is only the absence of Mill module declarations.

2. **How should the Panama Suspend bridge be completed?** Should `Scheduler` receive a reference
   to `PanamaState` and call `lastYieldToken` / `suspendRegistry.consume` itself, or should
   `Binding` expose a new method (e.g., `takePendingSuspend(): Option[NativeFnResult.Suspend]`)
   that `PanamaState` implements by consuming the token?

3. **What is the intended contract of `Scheduler.spawn()`?** It creates a thread and enqueues it
   with no function pushed. Is it meant for callers that manually push a function before the next
   `runAllReady`? Should it be made `private[scheduler]` or removed in favor of
   `spawnImmediate`/`deferTask`?

4. **Should `ResumeValues.Success` be removed or implemented?** The two-value resume
   `(ok: Boolean, result: LuaValue)` path was planned but never wired. If it is not needed for
   any current use case, the dead case should be removed.

5. **Should TaskHandle.threadRef be guarded against the closed-Ref push bug?** The fix options
   are described in Section 7. Which design best maintains Ref ownership semantics per ADR-0005?

6. **Are the Luau test resources in `stdlib/jvm/test/resources/luau/` ever executed?** There is
   no visible integration test runner for them. Are they intended to be run against a Panama or
   WASM backend in a future plan, or are they currently spec documentation only?

7. **Does `task.cancel` need to error on non-Thread arguments?** The current behavior (silent
   no-op for non-Thread types) matches Roblox. Is this the full Roblox contract, or should a
   type error be returned for invalid non-nil arguments?

8. **Is there a plan for `scheduler/js`?** The directory exists but is empty. A JS-compatible
   Scheduler would need to replace `java.util.Timer` with `setTimeout`-based scheduling and
   `AtomicLong` with a plain counter, since JS is single-threaded.

9. **How does the Scheduler handle permanent parks from `coroutine.yield`?** ADR-0004 says it
   parks permanently. Is there a mechanism to detect or report permanently-parked Tasks to avoid
   invisible hangs, beyond the `close()` cancellation path?

---

*This document was generated by static analysis of the source tree. All code references are
verified against the files listed. Claims about Panama behavior are based on reading
`panama/src/luau/panama/NativeFnDispatcher.scala` and `panama/src/luau/panama/PanamaState.scala`
directly.*
