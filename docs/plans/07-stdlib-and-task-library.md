# P07: Standard Library Opening and Roblox Task Library

## 1. Milestone & Goal

This plan delivers the complete standard library surface and the Roblox-compatible `task` library for
luau-scala. Concretely: all Luau base libraries that Roblox exposes (base globals, `math`, `string`,
`table`, `bit32`, `utf8`, `os` [time-only], `vector`, `buffer`, and `coroutine`) are opened into each
new Isolate state via the Shim, with explicit omissions or stub replacements for anything unsafe (`io`,
`os.execute`, `os.exit`, `os.getenv`, `package`, `dofile`, `loadfile`, and `require`). The global
environment is then locked with `lua_setreadonly` / `luaL_sandbox` (Luau's `safeenv` facility) so
scripts cannot monkey-patch globals. The Roblox `task` library — `task.spawn`, `task.defer`,
`task.delay`, `task.wait`, and `task.cancel` — is implemented as a set of Native functions that drive
the Scheduler defined in P06. The observable ordering semantics match Roblox exactly, are pinned by an
automated test suite, and the one known deviation from a naive mental model (task functions called from
inside a raw script-created Coroutine) is accepted, documented, and covered by a regression test.

At the end of this milestone an implementing agent can create an Isolate, load a Luau script that calls
`math.floor`, `string.format`, `table.sort`, `buffer.create`, `task.spawn`, `task.defer`, `task.wait`,
and `coroutine.create`, run it through the Scheduler, and observe Roblox-accurate behavior.

---

## 2. Dependencies

### Prior plans required (must be complete before this plan starts)

| Plan | Key artifact consumed |
|------|----------------------|
| P01 `01-project-scaffold-and-build-toolchain.md` | Mill cross-build; module layout: `core`, `panama`, `wasm`, `scheduler`, `stdlib` |
| P02 `02-cpp-shim-abi.md` | Shim ABI: `lx_newstate`, `lx_close`, `lx_resume`, `lx_ref`/`lx_unref`, native-function trampoline, `lx_setreadonly`, `lx_sandbox` (safeenv) |
| P03 `03-core-abstractions.md` | `Binding` trait, `NativeFn` = `LuaArgs => NativeFnResult`, `NativeFnResult` ADT (`Return`/`Fail`/`Suspend`), `LuauEncoder`/`LuauDecoder`, `Ref`, `Scope`, `LuaError` |
| P04 `04-panama-backend-jvm.md` | JVM-side `Binding` implementation (Panama); needed to run integration tests on JVM |
| P05 `05-wasm-backend-js.md` | Scala.js-side `Binding` implementation (WASM); needed for JS integration tests |
| P06 `06-scheduler-and-task-model.md` | `Scheduler`, `Task`, `Driver`, `RunQueue`; specifically: `Scheduler#spawn(thread, args)`, `Scheduler#defer(thread, args)`, `Scheduler#scheduleTimer(delaySeconds, Task)`, `Scheduler#cancelTask(Task)`, `Scheduler#currentTask: Option[Task]`, `Scheduler#enqueueResume(Task, result)` |

### Exact symbols this plan consumes from prior plans

From P03 `core`:
- `trait Binding` — the platform handle abstraction; this plan adds no new `Binding` methods but calls
  existing ones.
- `NativeFn: LuaArgs => NativeFnResult` — the function type for all task library natives.
- `NativeFnResult` sealed ADT: `Return(n: Int)`, `Fail(value: LuaValue)`, `Suspend(register: Resume => Cancel)`.
- `Resume = Either[LuaError, Result] => Unit`, `Cancel = () => Unit`.
- `Ref[A]` — used to hold Luau thread references for `task.cancel`.
- `Scope` — used inside `openStdlib` to safely manage transient Refs.
- `LuaError` — wraps script-visible errors.
- `LuauEncoder[A]`, `LuauDecoder[A]` — used to push/read arguments to/from task natives.

From P06 `scheduler`:
- `Scheduler` class (or trait): `spawn`, `defer`, `scheduleTimer`, `cancelTask`, `currentTask`,
  `enqueueResume`.
- `Task` — the opaque handle the Scheduler issues; used by `task.cancel`.
- `TaskHandle` — a `Ref`-like cancellable reference returned by spawn/defer for use by `task.cancel`.

---

## 3. Design Context

### 3.1 Governing ADRs

**ADR-0001 (Embed via Slim Shim):** All Luau execution enters through `lua_resume` — the Resume
boundary. The Host never calls `lua_pcall` directly. The Shim opens standard libraries by calling
Luau's `luaL_openlibs`-equivalent internally; the Host only calls one `lx_openlibs(state, mask)`
function that forwards to the appropriate `luaopen_*` functions in pure C. Errors from `luaopen_*`
(which can theoretically longjmp) stay inside the Shim and are captured as a status return.

**ADR-0004 (Coroutine substrate, Task library on top, single-threaded Scheduler):** The `coroutine`
library is exposed to scripts as-is. A Task is a Coroutine the Scheduler owns. Scripts may create
their own Coroutines with `coroutine.create`; the Scheduler does not own those and never sees their
yields. The `task` library is built on top of the Coroutine substrate and the Scheduler. The Roblox
behavioral quirk — calling `task.wait` or other task functions from inside a raw script-created
Coroutine (one not owned by the Scheduler) — reschedules against the wrong resumer (the script's
coroutine.resume, not the Scheduler). This is accepted, documented, and not worked around.

**ADR-0007 (Tri-state NativeFn return + callback async):** Every task library Native function that
must suspend (e.g. `task.wait`) returns `Suspend(register)`. The `register` function receives a
one-shot `Resume` callback and returns a `Cancel`. The Shim calls `lua_yield(k)` in C when it sees
`Suspend`; the Host's Scheduler re-enqueues the Task when the timer fires. Native functions that do
not suspend (e.g. `task.spawn`, `task.defer`, `task.cancel`) return `Return(0)` immediately.

**ADR-0001 constraint — no `lua_pcall` at the boundary:** Standard library opening (`luaopen_*`
calls) is performed inside the Shim via `lx_openlibs`. The Host never calls `luaopen_*` directly.

**ADR-0006 (Copy-only Codec):** Arguments to task library natives (function references, delay
seconds, additional arguments) are read from the Luau stack using non-raising `lua_to*` accessors.
No Host objects cross to Luau by reference; function threads created by `task.spawn`/`task.defer`
are Luau threads managed as `Ref[LuauThread]` on the Host side with explicit close semantics
(ADR-0005).

**ADR-0005 (Deterministic Ref lifetime):** The Luau thread created for a spawned Task is pinned
via `lx_ref`. It must be explicitly released when the Task completes or is cancelled. The Scheduler
(P06) is responsible for calling `ref.close()` on Task completion; the cancellation path in
`task.cancel` must also trigger close.

### 3.2 CONTEXT glossary terms in use

- **Runtime**: the upstream Roblox Luau engine embedded as-is.
- **Shim**: the C++ layer; exposes `lx_openlibs`, `lx_sandbox`, `lx_setreadonly`.
- **Binding backend**: the Panama (JVM) or WASM (JS) Scala code calling the Shim.
- **Coroutine**: the raw Luau `coroutine.*` primitive; exposed to scripts per Roblox parity.
- **Task**: a Coroutine the Scheduler owns. The task library creates Tasks.
- **Task library**: the Roblox `task.*` API built on the Coroutine substrate and Scheduler.
- **Scheduler**: the Host loop draining the Run queue.
- **Driver**: the single execution context owning the Luau state during a resume.
- **Run queue**: the queue Tasks flow through.
- **Native function**: a Scala function exposed to scripts via the Shim trampoline.
- **Suspension**: what a Task produces when it yields; the C stack is fully unwound.
- **Async primitive**: `Suspend(register: Resume => Cancel)` — the bedrock awaiting mechanism.
- **Resume boundary**: the single entry point; all Luau code runs inside `lua_resume`.
- **Ref**: stable Host handle to a Luau-heap object; `AutoCloseable`.
- **Scope**: closes owned Refs on exit.
- **Isolate**: independent Luau state; all state in this plan lives inside one Isolate.

### 3.3 Roblox behavioral spec (task ordering semantics)

Roblox is the behavioral reference. The following rules must hold exactly:

1. **`task.spawn(f, ...args)`** — creates a new Task from `f` and resumes it immediately (within
   the current Scheduler tick, before the current Task completes). The new Task runs until its first
   yield or return. The calling Task continues after `task.spawn` returns.

2. **`task.defer(f, ...args)`** — creates a new Task from `f` but queues it to run *after* the
   current resumption cycle completes (deferred). The calling Task sees `task.defer` return
   immediately; `f` does not start until the next Scheduler drain.

3. **`task.delay(seconds, f, ...args)`** — registers `f` to be spawned (as a new Task, not deferred)
   after at least `seconds` wall-clock seconds. The timer is single-shot. `seconds = 0` is valid and
   behaves like `task.defer` (next drain after the current cycle).

4. **`task.wait([seconds])`** — suspends the current Task for at least `seconds` seconds (default 0).
   Returns the actual elapsed time (a `number`). `task.wait(0)` defers to the next drain, exactly
   like a yield that re-enqueues at end-of-queue. The function uses the `Suspend` ADT path.

5. **`task.cancel(thread)`** — cancels a Task identified by its Luau thread. If the Task is parked
   (waiting on a timer or other suspension), it is removed from the timer queue and its Run queue
   entry is discarded. If the Task has already completed or does not exist, this is a no-op.

**Deferred-vs-spawn ordering invariant (Roblox parity):**
```
task.spawn(A) inside tick T  → A runs before tick T ends
task.defer(B) inside tick T  → B runs in tick T+1 or later
```
Concretely, if A and B are both enqueued in the same tick, A always completes (or yields) before B
starts, and B starts before any Task enqueued by C where C was deferred by B.

**`task.wait` timing tolerance:** on both JVM and JS, `task.wait(0.1)` must resume after
≥ 0.1 seconds. The actual elapsed time returned may be slightly greater (system timer granularity),
but must never be less than the requested duration. A tolerance of 50 ms above the target is
acceptable in tests; timing tests must not assert exact values.

### 3.4 Sandboxing policy

The following must NOT be available to scripts:

| Removed | Reason |
|---------|--------|
| `io.*` | filesystem access |
| `os.execute` | shell execution |
| `os.exit` | process exit |
| `os.getenv` | environment variable leak |
| `package.*` | `require` path manipulation |
| `dofile` | filesystem load |
| `loadfile` | filesystem load |
| `require` (default) | module loading — replaced by a Host-controlled stub or omitted |
| `debug` (optional) | expose only if Host explicitly enables it; default omit |
| `load` | constrained: available but restricted to bytecode (no `env` override) |

The following ARE available:

`assert`, `error`, `print`, `warn`, `tostring`, `tonumber`, `type`, `typeof`, `rawget`, `rawset`,
`rawequal`, `rawlen`, `select`, `ipairs`, `pairs`, `next`, `unpack`, `pcall`, `xpcall`,
`setmetatable`, `getmetatable`, `newproxy`, `collectgarbage` (limited), plus the full `math`,
`string`, `table`, `bit32`, `utf8`, `coroutine`, `vector`, `buffer` namespaces, and
`os.clock`, `os.time`, `os.date`, `os.difftime`.

After opening, the global table is frozen with `lua_setreadonly(L->gt, true)` via `lx_sandbox`.
Luau's `luaL_sandbox` additionally sets `safeenv = 1` on the state, which disables `getfenv`/
`setfenv` and prevents import-optimization bypass. Both calls must happen after all libraries are
opened and after the `task` table is installed.

---

## 4. Task Breakdown

All files are under `/home/hoangdinh/OSS/luau-scala/`. The `stdlib` module is cross-compiled
(JVM + JS). Files under `shim/` are C++ (compiled by the Shim build from P01/P02).

### 4.1 Shim additions — `shim/src/lx_stdlib.cpp` (new file, C++)

**Purpose:** Expose a single `lx_openlibs(lua_State*, uint32_t mask)` function that calls the
appropriate `luaopen_*` functions inside the Shim. Also expose `lx_sandbox(lua_State*)` which calls
`luaL_sandbox` (sets safeenv + freezes globals) and `lx_setglobal_nil(lua_State*, const char*)` for
nulling out individual unsafe globals after `luaL_openlibs`.

This file is the **only** place `luaopen_*` is called. The Host never calls these directly.

**Key declarations (C):**

```c
/* Bitmask values for lx_openlibs mask parameter */
#define LX_LIB_BASE      (1u << 0)
#define LX_LIB_MATH      (1u << 1)
#define LX_LIB_STRING    (1u << 2)
#define LX_LIB_TABLE     (1u << 3)
#define LX_LIB_BIT32     (1u << 4)
#define LX_LIB_UTF8      (1u << 5)
#define LX_LIB_OS        (1u << 6)   /* opens only clock/time/date/difftime */
#define LX_LIB_COROUTINE (1u << 7)
#define LX_LIB_VECTOR    (1u << 8)
#define LX_LIB_BUFFER    (1u << 9)
#define LX_LIB_DEBUG     (1u << 10)  /* off by default; embedder opts in */
#define LX_LIB_STANDARD  (LX_LIB_BASE | LX_LIB_MATH | LX_LIB_STRING | \
                           LX_LIB_TABLE | LX_LIB_BIT32 | LX_LIB_UTF8 | \
                           LX_LIB_OS | LX_LIB_COROUTINE | LX_LIB_VECTOR | \
                           LX_LIB_BUFFER)

/*
 * Open standard libraries selected by mask into the given state.
 * Returns 0 on success, non-zero if a luaopen_* call raised (caught via
 * lua_resume error path internally).
 * Must be called before lx_sandbox.
 */
int lx_openlibs(lua_State* L, uint32_t mask);

/*
 * After lx_openlibs: null out unsafe globals (io, os.execute, os.exit,
 * os.getenv, package, dofile, loadfile) and freeze the global table.
 * Calls luaL_sandbox(L) which sets safeenv=1 and lua_setreadonly on _G.
 * Must be called once, after all libraries (including task) are installed.
 */
void lx_sandbox(lua_State* L);
```

**Implementation sketch:**

```c
int lx_openlibs(lua_State* L, uint32_t mask) {
    if (mask & LX_LIB_BASE)      luaopen_base(L);
    if (mask & LX_LIB_MATH)      luaopen_math(L);
    if (mask & LX_LIB_STRING)    luaopen_string(L);
    if (mask & LX_LIB_TABLE)     luaopen_table(L);
    if (mask & LX_LIB_BIT32)     luaopen_bit32(L);
    if (mask & LX_LIB_UTF8)      luaopen_utf8(L);
    if (mask & LX_LIB_COROUTINE) luaopen_coroutine(L);
    if (mask & LX_LIB_VECTOR)    luaopen_vector(L);
    if (mask & LX_LIB_BUFFER)    luaopen_buffer(L);
    if (mask & LX_LIB_DEBUG)     luaopen_debug(L);
    if (mask & LX_LIB_OS) {
        luaopen_os(L);
        /* null out unsafe os members */
        lua_getglobal(L, "os");
        lua_pushnil(L); lua_setfield(L, -2, "execute");
        lua_pushnil(L); lua_setfield(L, -2, "exit");
        lua_pushnil(L); lua_setfield(L, -2, "getenv");
        lua_pop(L, 1);
    }
    /* null top-level unsafe globals */
    lua_pushnil(L); lua_setglobal(L, "dofile");
    lua_pushnil(L); lua_setglobal(L, "loadfile");
    lua_pushnil(L); lua_setglobal(L, "require");
    /* io and package were never opened, but defensively nil them */
    lua_pushnil(L); lua_setglobal(L, "io");
    lua_pushnil(L); lua_setglobal(L, "package");
    return 0;
}

void lx_sandbox(lua_State* L) {
    luaL_sandbox(L);  /* sets safeenv=1, freezes _G and all opened lib tables */
}
```

**Note on `luaL_sandbox`:** Luau's `luaL_sandbox` (declared in `lualib.h`) sets
`L->l_G->safeenv = 1` and calls `lua_setreadonly` on the global table and on each opened library
table. It does NOT freeze user-created tables — only `_G` and the standard library namespace tables.
The `task` table must be created and populated before `lx_sandbox` is called, so it too is frozen
along with globals.

**Additions to `shim/include/lx.h`:**

```c
/* mask constants as above, then: */
int  lx_openlibs(lua_State* L, uint32_t mask);
void lx_sandbox(lua_State* L);
```

**WASM export list additions (`shim/src/lx_exports.cpp` or emscripten flags):**

```
_lx_openlibs
_lx_sandbox
```

### 4.2 Shim additions — `shim/src/lx_stdlib.cpp` OS time isolation

The `os` library opened above retains `os.clock`, `os.time`, `os.date`, `os.difftime`. These
functions do not exec subprocesses or access the filesystem, so they are safe. `os.time()` uses
`time(3)` returning wall-clock seconds; `os.clock()` returns CPU time via `clock(3)`. Both are
acceptable in a sandboxed environment.

### 4.3 Scala cross-module — `stdlib/src/luau/scala/stdlib/StdlibOpener.scala`

**Module:** `stdlib` (cross: JVM + JS)  
**Purpose:** The Scala entry point that calls `lx_openlibs` and `lx_sandbox` via the `Binding`
trait. Provides a single `openStdlib(state: Binding, mask: Int = StdlibMask.Standard): Unit`
function. Also installs the `task` table into the global environment before sandbox is applied.

```scala
package luau.scala.stdlib

import luau.scala.core.Binding

object StdlibMask:
  val Base:      Int = 1 << 0
  val Math:      Int = 1 << 1
  val String:    Int = 1 << 2
  val Table:     Int = 1 << 3
  val Bit32:     Int = 1 << 4
  val Utf8:      Int = 1 << 5
  val Os:        Int = 1 << 6
  val Coroutine: Int = 1 << 7
  val Vector:    Int = 1 << 8
  val Buffer:    Int = 1 << 9
  val Debug:     Int = 1 << 10
  val Standard: Int =
    Base | Math | String | Table | Bit32 | Utf8 | Os | Coroutine | Vector | Buffer

object StdlibOpener:
  /**
   * Open the selected standard libraries into `state`, install the `task`
   * table, then freeze the global environment.
   *
   * Must be called once per Isolate, before any scripts are loaded.
   * `scheduler` must be the Scheduler that owns this state.
   *
   * @param state     the Binding for this Isolate
   * @param scheduler the Scheduler owning this Isolate (for task library)
   * @param mask      bitmask of libraries to open (default: StdlibMask.Standard)
   */
  def open(
    state:     Binding,
    scheduler: luau.scala.scheduler.Scheduler,
    mask:      Int = StdlibMask.Standard
  ): Unit =
    // 1. Open base libraries via Shim
    state.openLibs(mask)
    // 2. Install the task library natives
    TaskLibrary.install(state, scheduler)
    // 3. Freeze globals (safeenv)
    state.sandbox()
```

**New methods required on `Binding` trait (additions to P03's trait):**

```scala
trait Binding:
  // ... existing methods from P03 ...

  /**
   * Call lx_openlibs(state, mask) — opens selected Luau standard libraries.
   * Called at most once per state, before sandbox().
   */
  def openLibs(mask: Int): Unit

  /**
   * Call lx_sandbox(state) — sets safeenv=1 and freezes the global table.
   * Called once after all library tables (including task) are installed.
   */
  def sandbox(): Unit
```

These two methods must be added to `Binding` in P03 and implemented in both the Panama backend (P04)
and WASM backend (P05) before this plan can be completed.

### 4.4 `stdlib/src/luau/scala/stdlib/TaskLibrary.scala`

**Module:** `stdlib` (cross)  
**Purpose:** Defines all five task library Native functions and installs them into the `task` global
table. This file is the behavioral core of the plan.

```scala
package luau.scala.stdlib

import luau.scala.core.{Binding, NativeFn, NativeFnResult, LuaArgs, Ref, LuaError}
import luau.scala.core.NativeFnResult.{Return, Fail, Suspend}
import luau.scala.scheduler.{Scheduler, Task, TaskHandle}

object TaskLibrary:

  /**
   * Install the `task` table into the global environment of `state`.
   * Must be called after openLibs() and before sandbox().
   *
   * Creates a global table `task` containing:
   *   task.spawn, task.defer, task.delay, task.wait, task.cancel
   */
  def install(state: Binding, scheduler: Scheduler): Unit =
    state.pushNewTable()                        // push {} for `task`
    installField(state, "spawn",  spawn(state, scheduler))
    installField(state, "defer",  defer_(state, scheduler))
    installField(state, "delay",  delay(state, scheduler))
    installField(state, "wait",   wait(state, scheduler))
    installField(state, "cancel", cancel(state, scheduler))
    state.setGlobal("task")                     // _G.task = table; pops table

  private def installField(
    state: Binding, name: String, fn: NativeFn
  ): Unit =
    // stack has the task table at top; push fn, setfield
    state.registerNativeFunction(fn)            // pushes native closure
    state.setField(-2, name)                    // task[name] = closure

  // ---------------------------------------------------------------------------
  // task.spawn(f [, ...args]) -> thread
  //
  // Roblox semantics: enqueues f as a new Task that runs IMMEDIATELY within
  // the current Scheduler tick, before the current tick returns.
  //
  // Implementation: calls scheduler.spawnImmediate(f, args...) which pushes
  // the Task to the FRONT of the run queue and resumes it synchronously within
  // the Driver loop (within the current tick boundary).
  //
  // Returns the Luau thread (coroutine) for the spawned Task. The caller may
  // pass this to task.cancel later.
  //
  // ADR-0007: this function does NOT suspend — it returns Return(1) with the
  // thread on the stack.
  // ---------------------------------------------------------------------------
  def spawn(state: Binding, scheduler: Scheduler): NativeFn = args =>
    args.requireFunction(1) match
      case Left(err) => Fail(err)
      case Right(fnRef) =>
        val extraArgs = args.collectFrom(2)     // List[LuaValue]
        val handle: TaskHandle = scheduler.spawnImmediate(fnRef, extraArgs)
        args.pushThread(handle.thread)          // push thread onto stack
        Return(1)

  // ---------------------------------------------------------------------------
  // task.defer(f [, ...args]) -> thread
  //
  // Roblox semantics: enqueues f as a new Task to the BACK of the deferred
  // queue. The Task does not start until after the current tick completes.
  //
  // ADR-0007: returns Return(1) immediately; no suspension.
  // ---------------------------------------------------------------------------
  def defer_(state: Binding, scheduler: Scheduler): NativeFn = args =>
    args.requireFunction(1) match
      case Left(err) => Fail(err)
      case Right(fnRef) =>
        val extraArgs = args.collectFrom(2)
        val handle: TaskHandle = scheduler.deferTask(fnRef, extraArgs)
        args.pushThread(handle.thread)
        Return(1)

  // ---------------------------------------------------------------------------
  // task.delay(seconds, f [, ...args]) -> thread
  //
  // Roblox semantics: schedule f to run as a new Task after >= seconds.
  // seconds = 0 behaves like task.defer (deferred to next tick).
  //
  // ADR-0007: returns Return(1) immediately.
  // ---------------------------------------------------------------------------
  def delay(state: Binding, scheduler: Scheduler): NativeFn = args =>
    val seconds = args.readDouble(1).getOrElse(0.0)
    args.requireFunction(2) match
      case Left(err) => Fail(err)
      case Right(fnRef) =>
        val extraArgs = args.collectFrom(3)
        val handle: TaskHandle = scheduler.scheduleDelayed(seconds, fnRef, extraArgs)
        args.pushThread(handle.thread)
        Return(1)

  // ---------------------------------------------------------------------------
  // task.wait([seconds]) -> elapsed: number
  //
  // Roblox semantics: suspend the current Task for >= seconds (default 0).
  // Returns the actual elapsed time as a number.
  //
  // ADR-0007: uses Suspend(register). The register function:
  //   1. Records the wall-clock time at call site (t0).
  //   2. Schedules a timer with the Scheduler for `seconds` seconds.
  //   3. Returns a Cancel that removes the timer.
  //   4. When the timer fires, calls resume(Right(elapsed)) where elapsed = now - t0.
  //
  // The Shim calls lua_yield(k) when it sees Suspend; the Task is stackless
  // while parked (ADR-0003). The Scheduler re-enqueues via enqueueResume when
  // the timer fires (ADR-0004: off-Driver completions enqueue, never inline).
  // ---------------------------------------------------------------------------
  def wait(state: Binding, scheduler: Scheduler): NativeFn = args =>
    val seconds = args.readDouble(1).getOrElse(0.0)
    val currentTask: Task = scheduler.currentTask.getOrElse(
      // task.wait called from outside a Scheduler-owned Task.
      // This is the raw-Coroutine wrinkle (ADR-0004 / known Roblox-ism).
      // We still park the Task but resume is undefined w.r.t. the script's
      // coroutine.resume — document this in the error message.
      return Fail(LuaError.message(
        "task.wait called from a coroutine not owned by the Scheduler; " +
        "behavior is undefined (see ADR-0004)"
      ))
    )
    Suspend { resume =>
      val t0 = System.nanoTime()                // wall-clock at suspension point
      val cancel: luau.scala.core.Cancel = scheduler.scheduleTimer(seconds) {
        val elapsed = (System.nanoTime() - t0) / 1e9
        // Push elapsed as the return value before re-enqueueing.
        // The resume callback carries the result; the Scheduler will
        // lua_resume(thread, 1) where the 1 result is already on the thread's
        // stack via the yield continuation (k) path.
        resume(Right(luau.scala.core.Result.double(elapsed)))
      }
      cancel
    }

  // ---------------------------------------------------------------------------
  // task.cancel(thread) -> ()
  //
  // Roblox semantics: cancel a pending Task identified by its Luau thread.
  // If the Task is parked on a timer or deferred queue, it is removed.
  // If the Task has completed or does not exist, this is a no-op.
  //
  // ADR-0007: returns Return(0); no suspension.
  // ADR-0005: the Ref for the cancelled Task's thread is released by the
  // Scheduler's cancel path, not here.
  // ---------------------------------------------------------------------------
  def cancel(state: Binding, scheduler: Scheduler): NativeFn = args =>
    args.readThread(1) match
      case None         => Return(0)            // nil or non-thread arg: no-op
      case Some(thread) =>
        scheduler.cancelTask(thread)            // no-op if already done
        Return(0)
```

### 4.5 `stdlib/src/luau/scala/stdlib/LuaArgs.scala` (additions / helpers)

**Purpose:** Extends the `LuaArgs` helper (or defines it if P03 left it as a stub) with methods
used by the task library:

```scala
package luau.scala.stdlib

import luau.scala.core.{Binding, LuaValue, LuaError, Ref}

/**
 * Wrapper around a NativeFn call frame, providing typed accessors.
 * Passed to every NativeFn as its single argument.
 */
final class LuaArgs(val state: Binding, val nargs: Int):

  /** Read a number at argument position `pos` (1-based). */
  def readDouble(pos: Int): Option[Double]

  /** Read a thread (coroutine) Ref at argument position `pos`. */
  def readThread(pos: Int): Option[Ref[LuauThread]]

  /**
   * Require a function (callable) at position `pos`.
   * Returns Right(Ref) or Left(LuaError) if not a function.
   * Ref is owned by the caller; must be closed when no longer needed,
   * or transferred to the Scheduler which manages lifetime via TaskHandle.
   */
  def requireFunction(pos: Int): Either[LuaError, Ref[LuauFunction]]

  /**
   * Collect all arguments from position `from` to `nargs` as a List[LuaValue].
   * Values are copied (ADR-0006); safe to hold after the NativeFn returns.
   */
  def collectFrom(from: Int): List[LuaValue]

  /** Push a Luau thread value (from a Ref) onto the state stack. */
  def pushThread(thread: Ref[LuauThread]): Unit
```

### 4.6 `stdlib/src/luau/scala/stdlib/package.scala`

**Purpose:** Re-exports the public API of the stdlib module. Scripts using luau-scala should only
need to import from `luau.scala.stdlib`:

```scala
package luau.scala.stdlib

export StdlibOpener.open
export StdlibMask
export TaskLibrary
```

### 4.7 `scheduler/src/luau/scala/scheduler/TaskHandle.scala` (P06 addition, owned by P06)

**Note:** This file is logically part of P06 but the interface must be specified here because
`TaskLibrary.scala` depends on it. The implementing agent should add this to P06's deliverables
if not already present, or treat it as an addition spec'd here.

```scala
package luau.scala.scheduler

import luau.scala.core.Ref

/**
 * An opaque handle to a spawned or deferred Task, returned by
 * Scheduler#spawnImmediate, Scheduler#deferTask, Scheduler#scheduleDelayed.
 *
 * `thread` is the Luau thread Ref; scripts can pass this to task.cancel.
 * The Scheduler owns the Ref's lifetime; it is released on Task completion
 * or cancellation (ADR-0005).
 */
final class TaskHandle(
  val thread: Ref[LuauThread],   // the underlying Luau coroutine
  val task:   Task               // the Scheduler's Task record
)
```

**Required additions to `Scheduler` (specified here, implemented in P06's module):**

```scala
trait Scheduler:
  /**
   * Spawn f immediately (front of run queue). Returns a TaskHandle.
   * `f` is a Ref to the Luau function; `args` are copied values.
   * The Task runs synchronously to its first yield before this returns.
   */
  def spawnImmediate(f: Ref[LuauFunction], args: List[LuaValue]): TaskHandle

  /**
   * Defer f to the back of the deferred queue (runs after current tick).
   */
  def deferTask(f: Ref[LuauFunction], args: List[LuaValue]): TaskHandle

  /**
   * Schedule f to run after >= seconds wall-clock time.
   * seconds=0 behaves like deferTask.
   */
  def scheduleDelayed(seconds: Double, f: Ref[LuauFunction], args: List[LuaValue]): TaskHandle

  /**
   * Schedule a callback to fire after >= seconds. Returns a Cancel.
   * Used by task.wait. The callback receives no arguments; the caller
   * is responsible for pushing the elapsed result before calling resume.
   */
  def scheduleTimer(seconds: Double)(callback: => Unit): Cancel

  /**
   * The Task currently being resumed on this Driver, if any.
   * Returns None if called outside a resume (e.g. from a raw coroutine).
   */
  def currentTask: Option[Task]

  /**
   * Cancel a Task identified by its Luau thread Ref.
   * No-op if the Task has completed or does not exist.
   * Releases the Task's Ref on cancellation (ADR-0005).
   */
  def cancelTask(thread: Ref[LuauThread]): Unit

  /**
   * Enqueue a completed async result for a parked Task.
   * Thread-safe. Never resumes inline (ADR-0002 / ADR-0004).
   */
  def enqueueResume(task: Task, result: Either[LuaError, Result]): Unit
```

### 4.8 Test infrastructure — `stdlib/test/luau/scala/stdlib/StdlibSuite.scala`

**Purpose:** Cross-platform (JVM + JS) integration tests. Uses the fake Binding backend from P03
for unit tests and the real backend for integration tests. Each test creates a fresh Isolate,
opens stdlib, loads a Luau script, runs it to completion, and asserts on the result.

The test file exists at:
`/home/hoangdinh/OSS/luau-scala/stdlib/test/luau/scala/stdlib/StdlibSuite.scala`

Tests are enumerated in Section 5 below.

### 4.9 Test scripts — `stdlib/test/resources/luau/`

Directory: `/home/hoangdinh/OSS/luau-scala/stdlib/test/resources/luau/`

One `.luau` file per test scenario. Named by test case (e.g. `task-spawn-order.luau`). Loaded as
classpath resources in the test suite.

---

## 5. Acceptance Criteria and Tests

All tests run via:

```
./mill stdlib.jvm.test   # JVM integration tests (Panama backend)
./mill stdlib.js.test    # JS integration tests (WASM backend)
```

### 5.1 `StdlibBaseLibsTest` — base libraries accessible

**Script:** `stdlib/test/resources/luau/base-libs.luau`

```luau
-- Verify base libraries are accessible
assert(type(math.floor) == "function", "math.floor missing")
assert(type(string.format) == "function", "string.format missing")
assert(type(table.sort) == "function", "table.sort missing")
assert(type(bit32.band) == "function", "bit32.band missing")
assert(type(utf8.len) == "function", "utf8.len missing")
assert(type(os.time) == "function", "os.time missing")
assert(type(coroutine.create) == "function", "coroutine.create missing")
assert(type(buffer.create) == "function", "buffer.create missing")
assert(type(vector) ~= nil, "vector missing")
return "ok"
```

**Expected:** script returns `"ok"` with no error.

### 5.2 `StdlibSandboxDenialTest` — unsafe globals are nil

**Script:** `stdlib/test/resources/luau/sandbox-denial.luau`

```luau
-- io must not exist
assert(io == nil, "io should be nil")
-- os.execute must not exist
assert(os.execute == nil, "os.execute should be nil")
assert(os.exit == nil, "os.exit should be nil")
assert(os.getenv == nil, "os.getenv should be nil")
-- package must not exist
assert(package == nil, "package should be nil")
-- dofile / loadfile must not exist
assert(dofile == nil, "dofile should be nil")
assert(loadfile == nil, "loadfile should be nil")
return "sandbox ok"
```

**Expected:** script returns `"sandbox ok"`.

### 5.3 `StdlibGlobalFreezeTest` — script cannot mutate globals after sandbox

**Script:** `stdlib/test/resources/luau/global-freeze.luau`

```luau
-- Attempting to set a new global should fail with an error
local ok, err = pcall(function()
    _G.newGlobal = 42
end)
assert(not ok, "expected error when mutating frozen _G")
return "freeze ok"
```

**Expected:** script returns `"freeze ok"` (the `pcall` catches the write to frozen `_G`).

**Note:** Luau's `lua_setreadonly` raises a runtime error on any write to a frozen table, which
`pcall` catches. This confirms that `lx_sandbox` was applied.

### 5.4 `TaskSpawnOrderTest` — spawn runs before defer (deferred-vs-spawn ordering)

**Script:** `stdlib/test/resources/luau/task-spawn-order.luau`

```luau
local order = {}

-- task.defer(B) runs AFTER task.spawn(A) completes (or yields), even though
-- B is registered first in this script.
task.defer(function()
    table.insert(order, "B")
end)

task.spawn(function()
    table.insert(order, "A")
end)

-- After both complete: A should come before B in order
-- but since we're inside a synchronous script, we need to yield to let
-- deferred tasks run. We wait 0 to defer ourselves.
task.wait(0)

assert(order[1] == "A", "expected A first, got " .. tostring(order[1]))
assert(order[2] == "B", "expected B second, got " .. tostring(order[2]))
return "order ok"
```

**Expected:** script returns `"order ok"`.

**Invariant pinned:** `task.spawn` pushes A to front of run queue (runs in current tick); `task.defer`
pushes B to back of deferred queue (runs in next tick after current tick drains).

### 5.5 `TaskSpawnImmediacyTest` — spawn runs synchronously within tick

**Script:** `stdlib/test/resources/luau/task-spawn-immediacy.luau`

```luau
local ran = false

-- spawn returns before f finishes if f yields, but f starts immediately
task.spawn(function()
    ran = true
    -- yield to avoid blocking; but the assignment above must have happened
    task.wait(0)
end)

-- After task.spawn returns, `ran` must be true because spawn resumes
-- immediately (the spawned function ran up to its first yield).
assert(ran == true, "spawned task must have run before spawn returned")
return "immediacy ok"
```

**Expected:** script returns `"immediacy ok"`.

### 5.6 `TaskDeferLatenessTest` — deferred task does not run until next tick

**Script:** `stdlib/test/resources/luau/task-defer-lateness.luau`

```luau
local ran = false

task.defer(function()
    ran = true
end)

-- Immediately after task.defer returns, the deferred task has NOT started
assert(ran == false, "deferred task must not run before current task yields")

-- Now yield to next tick
task.wait(0)

-- After yielding, the deferred task should have run
assert(ran == true, "deferred task must run after yield")
return "defer ok"
```

**Expected:** script returns `"defer ok"`.

### 5.7 `TaskWaitTimingTest` — wait returns after >= requested duration

**Script:** `stdlib/test/resources/luau/task-wait-timing.luau`

```luau
local t0 = os.clock()
local elapsed = task.wait(0.05)   -- 50 ms
local t1 = os.clock()

-- elapsed returned by task.wait must be >= 0.05
assert(elapsed >= 0.05,
    string.format("elapsed %f < 0.05", elapsed))

-- Wall clock must also reflect the wait
local wall = t1 - t0
assert(wall >= 0.05,
    string.format("wall clock %f < 0.05", wall))

-- But not absurdly long (tolerance: 2 seconds above target, for CI slowness)
assert(elapsed < 2.05,
    string.format("elapsed %f unexpectedly large", elapsed))
return "timing ok"
```

**Expected:** script returns `"timing ok"`. Test may be slow (50 ms per run); it is included in the
integration suite but not in the unit-test-only path.

**Note:** On JS (Node.js event loop), `task.wait` is backed by `setTimeout`. Timer resolution may be
coarser than on JVM; the 2-second tolerance is intentionally wide for CI.

### 5.8 `TaskWaitZeroTest` — task.wait(0) defers to next tick

**Script:** `stdlib/test/resources/luau/task-wait-zero.luau`

```luau
local order = {}

task.spawn(function()
    table.insert(order, "A-before")
    task.wait(0)
    table.insert(order, "A-after")
end)

task.defer(function()
    table.insert(order, "B")
end)

-- Let scheduler drain
task.wait(0)
task.wait(0)  -- second wait to let deferred-from-deferred settle

assert(order[1] == "A-before", tostring(order[1]))
-- After A yields via task.wait(0), B (deferred) runs, then A resumes
assert(order[2] == "B" or order[2] == "A-after",
    "unexpected order[2]: " .. tostring(order[2]))
return "wait-zero ok"
```

**Expected:** script returns `"wait-zero ok"`.

### 5.9 `TaskCancelTest` — cancel removes a pending task

**Script:** `stdlib/test/resources/luau/task-cancel.luau`

```luau
local ran = false
local thread = task.delay(10, function()  -- 10-second delay
    ran = true
end)

-- Immediately cancel it
task.cancel(thread)

-- Wait a tick to ensure no stale execution
task.wait(0)

assert(ran == false, "cancelled task must not have run")
return "cancel ok"
```

**Expected:** script returns `"cancel ok"`.

### 5.10 `TaskCancelNoopTest` — cancelling a completed task is a no-op

**Script:** `stdlib/test/resources/luau/task-cancel-noop.luau`

```luau
local ran = false
local thread = task.spawn(function()
    ran = true
end)

-- thread has already run to completion (spawn is immediate)
-- cancelling it must not error
task.cancel(thread)  -- no-op
task.cancel(nil)     -- nil arg: no-op (no error)
assert(ran == true, "task should have run")
return "cancel-noop ok"
```

**Expected:** script returns `"cancel-noop ok"` with no error.

### 5.11 `StdlibMathRoundtripTest` — math library functional

**Script:** `stdlib/test/resources/luau/math-roundtrip.luau`

```luau
assert(math.floor(2.9) == 2)
assert(math.ceil(2.1) == 3)
assert(math.abs(-5) == 5)
assert(math.max(1, 2, 3) == 3)
assert(math.min(1, 2, 3) == 1)
assert(math.sqrt(4) == 2)
assert(math.huge > 0)
local x = math.random()
assert(x >= 0 and x < 1)
return "math ok"
```

**Expected:** script returns `"math ok"`.

### 5.12 `StdlibBufferTest` — buffer library functional

**Script:** `stdlib/test/resources/luau/buffer-test.luau`

```luau
local b = buffer.create(8)
assert(buffer.len(b) == 8)
buffer.writei32(b, 0, 0xDEADBEEF)
local v = buffer.readu32(b, 0)
assert(v == 0xDEADBEEF, string.format("got %x", v))
buffer.fill(b, 0, 0)
assert(buffer.readu32(b, 0) == 0)
return "buffer ok"
```

**Expected:** script returns `"buffer ok"`.

### 5.13 `TaskRawCoroutineWrinkleTest` — known Roblox-ism documented

**Script:** `stdlib/test/resources/luau/task-raw-coroutine-wrinkle.luau`

This test documents (and regression-guards) the known quirk from ADR-0004: calling `task.wait`
inside a script-created Coroutine (not owned by the Scheduler) produces a specific error.

```luau
-- A coroutine created directly with coroutine.create is NOT a Scheduler Task.
-- Calling task.wait inside it should produce the documented error.
local co = coroutine.create(function()
    -- This SHOULD fail with our documented error message, not silently misbehave.
    local ok, err = pcall(function()
        task.wait(0)
    end)
    assert(not ok, "expected error from task.wait in raw coroutine")
    assert(
        string.find(err, "ADR%-0004") ~= nil or
        string.find(err, "not owned by the Scheduler") ~= nil,
        "error message must reference the known limitation: " .. tostring(err)
    )
    return "wrinkle documented"
end)

local ok, result = coroutine.resume(co)
assert(ok, tostring(result))
assert(result == "wrinkle documented")
return "raw-coroutine ok"
```

**Expected:** script returns `"raw-coroutine ok"`.

**Note for implementing agent:** The `task.wait` implementation in `TaskLibrary.scala` calls
`scheduler.currentTask` and returns `Fail(...)` if `None`. This error propagates as a Lua error
inside the `pcall`, which the test catches and inspects. This is the correct, documented behavior
per ADR-0004.

### 5.14 `CoroutineSubstrateTest` — coroutine library accessible and functional

**Script:** `stdlib/test/resources/luau/coroutine-substrate.luau`

```luau
local results = {}

local co = coroutine.create(function(a, b)
    table.insert(results, a)
    local c = coroutine.yield(a + b)
    table.insert(results, c)
    return "done"
end)

local ok, v = coroutine.resume(co, 10, 20)
assert(ok and v == 30, "first resume must yield 30")
ok, v = coroutine.resume(co, 99)
assert(ok and v == "done", "second resume must return done")
assert(coroutine.status(co) == "dead")
assert(results[1] == 10)
assert(results[2] == 99)
return "coroutine ok"
```

**Expected:** script returns `"coroutine ok"`.

### 5.15 End-to-end test — full script using multiple libraries together

**Script:** `stdlib/test/resources/luau/e2e-combined.luau`

```luau
-- Combines: table, string, math, task, buffer, coroutine
local buf = buffer.create(4)
buffer.writei32(buf, 0, 42)
assert(buffer.readi32(buf, 0) == 42)

local results = {}
local t = task.spawn(function()
    for i = 1, 5 do
        table.insert(results, math.floor(i * 1.5))
    end
end)

task.wait(0)  -- let deferred tasks settle

assert(#results == 5)
assert(results[1] == 1)  -- floor(1.5)
assert(results[5] == 7)  -- floor(7.5)

local s = string.format("count=%d", #results)
assert(s == "count=5")

return "e2e ok"
```

**Expected:** script returns `"e2e ok"`.

---

## 6. Risks and Gotchas

### 6.1 `luaL_sandbox` freezes tables opened BEFORE it is called

**Risk:** If any library table is opened after `lx_sandbox` is called, that table will NOT be
frozen and scripts can mutate it.

**Mitigation:** The `StdlibOpener.open` function enforces a strict call order: `openLibs` → 
`TaskLibrary.install` → `sandbox`. Never call `sandbox` before all library tables are populated.
This is enforced by the API design (the three steps are sequential in `StdlibOpener.open`).

**Reference:** `runtime-luau-official-cpp.md` §9 — `luaL_sandbox` is Luau-specific; stock Lua
does not have this. The Luau C API declares it in `lualib.h`.

### 6.2 `task.wait` must use the Scheduler's timer, not a blocking sleep

**Risk:** Using `Thread.sleep` (JVM) or a JS `setTimeout` that resumes inline (without going
through the Run queue) breaks the single-Driver invariant (ADR-0002/ADR-0004). A completion that
resumes inline on a different thread would call `lua_resume` on a state owned by another thread.

**Mitigation:** The `Suspend(register)` path guarantees the resume callback is `enqueueResume`
(which posts to the Run queue), never `lua_resume` directly. The `scheduleTimer` in the Scheduler
must use the appropriate platform mechanism: a JVM `ScheduledExecutorService` or `java.util.Timer`
(not `Thread.sleep`), and on JS a `setTimeout` callback that posts to the Run queue rather than
resuming directly. This contract belongs to P06; this plan depends on it being correct.

**Reference:** `ADR-0007` §Consequences: "Calling `resume` only *enqueues* onto the Run queue;
it never resumes inline."

### 6.3 `task.spawn` immediate semantics require careful run-queue manipulation

**Risk:** If `spawnImmediate` pushes to the back of the run queue, spawn semantics degrade to
defer semantics, breaking the ordering invariant (test 5.4 / 5.5 will fail).

**Mitigation:** The Scheduler (P06) must provide `spawnImmediate` that pushes to the front of the
ready queue (ahead of any other queued tasks) but after the currently-executing Task. The
implementing agent must verify P06's `spawnImmediate` implementation satisfies this; if P06 does not
already have it, it must be added as an amendment.

### 6.4 `lx_openlibs` called from Scala must be inside a resume boundary

**Risk:** `luaopen_base` and related `luaopen_*` functions can call `luaG_runerror` (which
`longjmp`s) if the VM is in an unexpected state. If `lx_openlibs` is called from the Host at the
top level (outside any resume), there is no `lua_longjmp` handler on the C stack — the longjmp
would unwind through Panama or WASM frames.

**Mitigation:** `lx_openlibs` must be wrapped in the Shim in a `lua_resume` or at minimum in a
`lua_pcall`-equivalent. The implementation sketch above calls `luaopen_*` directly at the C
level — this is safe only if done immediately after `lx_newstate` before any script execution, when
the main thread's state is in the initial `LUA_OK` status. The implementing agent should verify
this is the case and add a `lua_pcall` guard inside `lx_openlibs` if the state may not be
pristine. An alternative: open libraries as the first action inside a `lua_resume` on the main
thread before any user code runs.

**Reference:** `runtime-puc-lua-c.md` §10.3 — `lua_resume` validation: "status must be LUA_YIELD
or unstarted (status=0)." Opening libraries on an unstarted main thread (status=0) is safe because
the main thread's `lua_resume` wraps errors into a status code. But calling `luaopen_*` outside
any resume guard is risky if the implementations call `luaG_runerror`.

### 6.5 `safeenv` disables `getfenv`/`setfenv` import optimization bypass

**Risk:** Some Luau features (specifically the `GETIMPORT` bytecode optimization) are disabled
when `getfenv` or `setfenv` are called on a state. The `luaL_sandbox` call (via `lx_sandbox`)
sets `safeenv=1`, which prevents `getfenv`/`setfenv` from being called and keeps the import
optimization active. If a Host extension calls `lua_getfenv`/`lua_setfenv` after `lx_sandbox`,
it silently bypasses the import cache.

**Mitigation:** Do not call `lua_getfenv` or `lua_setfenv` from Host code after `lx_sandbox`.
Document this constraint in code comments. This plan introduces no such calls.

**Reference:** `runtime-luau-official-cpp.md` §4.5 — "Import caching: `GETIMPORT` … replaces itself
with a direct reference, bypassing global table lookups … [disabled by] `getfenv`/`setfenv`/
`loadstring`."

### 6.6 `task.wait` inside a raw script coroutine — Ref and Resume lifetime

**Risk:** If `task.wait` is called from a raw Coroutine (not a Scheduler Task), the `Suspend`
path registers a timer against `scheduler.currentTask = None`. The `task.wait` implementation
(Section 4.4) short-circuits this case by returning `Fail(...)` before calling `Suspend`. This
is correct, but if the short-circuit is accidentally removed, the `register` callback would receive
a `resume` that has no associated Task in the Scheduler — the `resume` call would enqueue a dead
entry on the Run queue, potentially causing a double-resume or a use-after-free of a Luau thread.

**Mitigation:** The `None` check on `currentTask` must remain. The regression test in §5.13 pins
this behavior.

### 6.7 `task.cancel` and Ref lifetime races

**Risk:** If a Task completes between the Luau script calling `task.cancel(thread)` and the
`cancelTask` executing, the `Ref[LuauThread]` passed to `cancelTask` may refer to a thread that
has already been cleaned up by the Scheduler. A double-`unref` would corrupt the Luau registry.

**Mitigation:** The `cancelTask` implementation in P06 must be idempotent and check whether the
Task is still alive before releasing the Ref. The `no-op if already done` contract stated in the
Scheduler trait (Section 4.7) must be upheld by P06. This plan does not introduce new Ref
management; it depends on P06's correctness.

### 6.8 `vector` and `buffer` libraries — Luau version check

**Risk:** The `vector` library (`luaopen_vector`) and `buffer` library (`luaopen_buffer`) were
added in relatively recent Luau releases. If the pinned Luau submodule is an older version,
`luaopen_vector` may not exist, causing a linker error.

**Mitigation:** Check that the pinned Luau submodule (P01) is ≥ 0.600 (where `luaopen_vector`
was introduced) and ≥ 0.580 for buffer. The Shim CMake or emscripten build should fail with a
clear message if these symbols are absent. Add a `#if defined(LUAU_HAS_VECTOR_LIB)` guard in
`lx_stdlib.cpp` if a fallback (silently skip) is preferred.

**Reference:** `runtime-luau-official-cpp.md` §9 — `vector` and `buffer` libraries listed as
standard; §11 — "Not in Luau (removed from Lua)" does not include these, confirming they are
present in current Luau.

### 6.9 Timing tests in CI — flakiness

**Risk:** `TaskWaitTimingTest` (§5.7) sleeps 50 ms and asserts elapsed ≥ 50 ms. On overloaded CI
runners (especially those using QEMU for cross-architecture), 50 ms wall-clock may elapse but
`os.clock()` (CPU time) may not advance by the same amount. The test uses `os.clock()` but
should ideally use `os.time()` (wall clock) or the Host's `System.nanoTime`.

**Mitigation:** In the test script, prefer using the `elapsed` value returned by `task.wait` itself
(which is derived from `System.nanoTime` on the Host side) rather than `os.clock()`. The test
script above already does this for the primary assertion (`elapsed >= 0.05`). The `os.clock()`
check is secondary and can be loosened or removed if it causes flakiness.

---

## 7. Out of Scope / Deferred

| Item | Deferred to |
|------|------------|
| `require` module loading system | A future plan (not in the master index as of this writing); for now `require` is nil in the sandbox |
| `debug` library (full access) | Controlled by the `LX_LIB_DEBUG` flag; disabled by default; enabling it is a Host opt-in not specified further here |
| `task.desynchronize` / `task.synchronize` | Roblox-specific task primitives tied to RunService; not in scope for the base scheduler |
| Cross-Isolate message passing via `task.*` | Requires Isolate-level channels; deferred to Isolate communication plan (not in master index) |
| Native codegen (`--!native` / `@native`) | P01 build infrastructure concern; this plan opens libraries but does not alter codegen policy |
| `string.pack` / `string.unpack` security audit | Accepted as-is from upstream; no additional sandboxing needed since the functions are pure |
| `collectgarbage` tuning surface | Exposed as-is; Host may call `lx_gc_step` from P02 to control GC externally |
| Movable-state (multi-Driver) parallelism | Deferred per ADR-0002; single-threaded Scheduler only |
| Effect-system `Resource` wrapping of `TaskHandle` | P08 |

---

## 8. References

The implementing agent should read the following documents before starting, in order:

### ADRs (all in `/home/hoangdinh/OSS/luau-scala/docs/adr/`)

| File | Key points for this plan |
|------|--------------------------|
| `0001-embed-upstream-luau-via-slim-cpp-shim.md` | No `lua_pcall` at FFI boundary; `lx_openlibs` must stay inside Shim; longjmp safety |
| `0002-movable-state-actor-concurrency.md` | Off-Driver completions enqueue, never resume inline; timer callback must post to Run queue |
| `0003-stackless-task-model.md` | `task.wait` must yield all the way out via `lua_yield(k)`; no blocking the Driver thread |
| `0004-coroutine-substrate-task-on-top.md` | **Most important for this plan.** Task library on Coroutine substrate; Scheduler ignores raw Coroutine yields; known Roblox-ism for `task.wait` in raw Coroutine |
| `0005-deterministic-ref-lifetime-no-finalizer.md` | TaskHandle's `Ref[LuauThread]` must be explicitly closed on completion/cancel |
| `0006-copy-only-data-boundary-via-codec-typeclass.md` | `collectFrom` copies values; no Host references cross to Luau |
| `0007-callback-based-async-and-tristate-native-return.md` | **Critical for `task.wait`.** `Suspend(register)` shape; `resume` one-shot; Cancel for timer teardown |

### CONTEXT glossary

`/home/hoangdinh/OSS/luau-scala/CONTEXT.md` — use the exact terms from this file throughout any
code and comments: **Runtime**, **Shim**, **Binding backend**, **Coroutine**, **Task**,
**Task library**, **Scheduler**, **Driver**, **Run queue**, **Native function**, **Suspension**,
**Async primitive**, **Resume boundary**, **Ref**, **Scope**, **Isolate**.

### Research documents

| File | Relevant sections |
|------|------------------|
| `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-official-cpp.md` | §4.8 Coroutines — `lua_newthread`, `lua_resume`, `lua_yield`; §9 Standard Library — complete list of available and removed functions; §11 Differences from Vanilla Lua — confirms `luaL_sandbox`, `lua_setreadonly`, `table.freeze`, `luaopen_vector`, `luaopen_buffer` are Luau-specific additions |
| `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-puc-lua-c.md` | §10 Coroutines — `lua_resume` mechanism, yield mechanism, the C-boundary yield restriction that Luau lifts with the `k` continuation form; §5 GC — context for why `collectgarbage` is available but has limited effect in a sandboxed Isolate |

### Prior plan documents (cross-reference, do not duplicate)

- `docs/plans/01-project-scaffold-and-build-toolchain.md` — module layout, Mill build, `stdlib` module definition
- `docs/plans/02-cpp-shim-abi.md` — Shim ABI including `lx_newstate`, trampoline, required additions `lx_openlibs` and `lx_sandbox`
- `docs/plans/03-core-abstractions.md` — `Binding` trait (add `openLibs`/`sandbox` methods), `NativeFn`, `NativeFnResult`, `Ref`, `Scope`, `LuaError`
- `docs/plans/04-panama-backend-jvm.md` — Panama implementation of `openLibs`/`sandbox`
- `docs/plans/05-wasm-backend-js.md` — WASM implementation of `openLibs`/`sandbox`
- `docs/plans/06-scheduler-and-task-model.md` — `Scheduler` trait additions (`spawnImmediate`, `deferTask`, `scheduleDelayed`, `scheduleTimer`, `currentTask`, `cancelTask`, `enqueueResume`), `Task`, `TaskHandle`
