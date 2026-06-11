# Plan 10 — Backend Lifecycle + Scheduler/Driver

**Status:** Grilled (2026-06-11) — all open questions resolved (§4); writing
plan in §5; nothing implemented yet.
**Rescoped 2026-06-11:** the original draft covered lifecycle only. The plan now
centers on reviving the Scheduler/Driver (plan 06's design) on top of the
lifecycle fixes, under a decided concurrency model (§0). Grilling then made
`withTasks` the ONLY entry point (`withState` deleted) returning
`TaskResult[A]`.
**Depends on:** the shipped `luau.api` facade; plan 09 is independent.

## 0. Decided constraints (rescope, 2026-06-11)

Explored and decided before drafting:

- **One VM = one thread, for the VM's whole life.** No multi-thread access, no
  VM migration across a worker pool. Verified against the vendored engine:
  `global_State` (GC worklists, string intern table, allocator) is shared by
  all coroutines of a VM and mutated lock-free; Luau ships no `lua_lock`.
  Parallel Lua compute is only possible across VMs (Roblox Parallel Luau is
  N VMs under the hood) — out of scope here.
- **ADR-0002 (movable-state actor concurrency) stays deferred.** Its one
  surviving obligation: off-Driver completions (`Resume` callbacks from async
  work on other threads) may **enqueue** onto the Run queue — never call
  `lx_resume` inline. The queue's release/acquire handoff is the only
  cross-thread edge.
- **No thread API exposure.** Users never see `createThread()`; the public
  surface is tasks (Scala-side spawn + the Lua-side `task.*` library).
- **One live state per runtime at a time** *(decided 2026-06-11)*. The
  runtime holds a single live-state slot; the entry point (`withTasks` after
  §2.3) claims it and a second entry — concurrent or nested — while one is
  live throws `IllegalStateException` (programming error, not a `Try`
  failure). Sequential reuse is fine: close the state, the next entry works.
  Scripts inside the one state (`eval`/`run` — fresh threads) are unaffected.
  "Nested" means only re-entering the entry point inside `f` — no API mints
  sub-states; tasks and coroutines are all threads of the one state.
  Consequences: per-VM bookkeeping collapses from a map to one slot, and at
  most one mounted scheduler/Driver exists per process at a time.

## 1. Problems

### 1.1 Panama: every `withState` opens two VMs

`PanamaLuau.withState` = `PanamaState.use` (opens an **anchor VM** `L`) +
`Luau.withState`'s `binding.newState()` (the VM actually used). The anchor VM
runs nothing — it exists because `PanamaState` conflates two roles:

- **Runtime** (process-level): `stateArena`, `NativeFnDispatcher`, upcall
  stub, fn registry.
- **VM identity**: the `L` it opens in `PanamaState.open()`, plus per-VM
  mutable state (`suspendRegistry`, `lastYieldToken`) that the dispatcher
  reaches through a `ps` back-reference (`dispatcher.init(ps)`).

The back-reference carries two real defects (correction 2026-06-11: the
original draft claimed token *collision* — wrong, `SuspendRegistry.allocToken`
is an `AtomicLong` and the shim stores the token per-thread via
`lx_set_suspend_token`; the actual defects are narrower):

- `lastYieldToken` is a single last-write-wins volatile — meaningless with
  two VMs suspending; its only consumer today is `SuspendResumeTest`.
- `closeState` never purges registry entries for that VM's in-flight
  suspends — a leak, plus a stale `Resume` that targets a freed `lua_State`.
- `dispatcher.init(ps)` is null-until-init: a binding minted without it NPEs
  on the first `Suspend` dispatch.
- `registerNativeFn` puts closures into the dispatcher's `fns` map and
  nothing removes them on `closeState`: every `withState` that calls
  `defineGlobal` leaks those closures for the dispatcher's lifetime — the
  panama twin of the wasm Trampoline-table leak (§1.2).

### 1.2 wasm: full module reload per state

`WasmBackend.load()` instantiates a complete wasm module (MBs, plus
`Trampoline.reset()`). Worse than the test-suite symptom: **the production
path reloads too** — `WasmLuau.withState` calls `WasmBackend.load()` on every
invocation. The test suites additionally reload per test because of this
admitted papering-over (WasmBackendSuite):

> a state's create/teardown leaves the shared heap/registry dirty enough to
> crash a later test (lua_rawgeti aborts). Reload per test.

That is a real shim-or-binding bug hiding behind a slow workaround. Known
suspects, unverified:

- `Trampoline` global fn `table` is never cleaned on `closeState` — fnIds
  accumulate and registered closures keep dead-state references alive; a
  stale fn invoked by a later state dereferences freed `LxStateData`.
- Shim-side `LxStateData` lifecycle (`delete d` in `lx_close`) vs anything
  still holding the pointer (suspend tokens, upcall in flight).
- Emscripten heap views after `ALLOW_MEMORY_GROWTH` — the fresh-view getters
  fix landed earlier; out-of-date cached views elsewhere would corrupt reads.

### 1.3 Scheduler exists but is dead code

The `scheduler/` module (plan 06's single-Driver design, ~400 LoC, written
against the **current** core types) and `stdlib/` (`TaskLibrary`,
`StdlibOpener`) both exist on disk but:

- **Unwired**: neither module appears in `build.mill`. Nothing compiles them;
  no test runs them.
- **JVM-only layout**: sources live under `scheduler/jvm/` with no cross
  `src/` — plan 06 specified cross-platform with a `PlatformQueue`
  expect/actual split; the JS half was never made.
- **Pending-suspend transfer is unwired three ways**: the Scheduler reads its
  own private `pendingSuspend` slot, the Panama dispatcher writes
  `SuspendRegistry` + a per-thread shim token, the wasm Trampoline writes its
  own `pendingSuspend` slot. Three mechanisms, zero wiring — a `Suspend`
  returned by a host fn never reaches `Scheduler.resumeTask`.
- **VM ownership conflict**: `Scheduler.close()` calls
  `binding.closeState(state)`, but the facade's `Luau.withState` also closes
  the state in its `finally`. Both cannot own it.
- **No Driver**: plan 06 §4.7's `Driver` (JVM pump thread / JS event-loop
  adapter) was never written; only `runAllReady()` exists.
- **Facade mismatch**: the Scheduler predates `luau.api` — it traffics in raw
  `Ref[H]`s (`spawnImmediate(fnRef: Ref[H], ...)`), not `LuaFn`/`RefScope`
  handles, and `pushValue` silently degrades `LuaValue.LuaRef` to nil.
- **Two yield regimes collide**: `LuaState.runChunk` auto-pumps yields with
  zero args up to `MaxResumes` (treats top-level yield as a livelock hazard),
  while the Scheduler treats a yield as "park until a completion enqueues".
  A facade `eval` and a scheduled Task interpret the same VM event in
  opposite ways.

## 2. Proposal

### 2.1 Panama: split `PanamaRuntime` from VM identity

**Decided (Q1, 2026-06-11): the binding IS the runtime surface — one binding,
many VMs — and both backends unify on the stateless-binding shape** wasm
already has: the binding object carries no VM identity and no per-VM mutable
fields; every method takes its target state explicitly. Process-level
resources live in a runtime singleton behind it (wasm: `WasmModule` +
`Trampoline`; panama: a new `PanamaRuntime`).

```scala
/** Process-level singleton: arena, dispatcher, upcall stub, per-VM map.
  * Owns NO VM. */
object PanamaRuntime:
  // lazy: arena + dispatcher + stub allocated on first use, never closed
  // (Q2 resolved — JVM exit cleans up);
  // live: one VmData slot (§0: one live state at a time) — withTasks
  // claims it, closeState clears it

/** Stateless Binding[MemorySegment] over the runtime — mirror of
  * WasmBinding over WasmModule/Trampoline. */
final class PanamaBinding private () extends Binding[MemorySegment]
```

- **Decided (Q2, 2026-06-11): lazy singleton, never closed**, on both
  backends — panama's `PanamaRuntime` allocates stub + arena + dispatcher on
  first use; wasm's module instance is the same singleton with `load()`
  becoming lazy ensure-loaded (§2.2). Never closing removes the
  arena-closed-while-VM-alive use-after-free class outright. The leak
  firewall that makes never-closing safe is per-VM purge discipline:
- Per-VM dispatcher state moves into the runtime's single live slot: `VmData`
  carries the live state's **in-flight suspend entries and its registered
  fnIds**, populated on `newState`/`registerNativeFn`, purged on `closeState`
  — killing the `dispatcher.init(ps)` back-reference, the suspend leak, the
  host-fn closure leak, and the `lastYieldToken` last-write-wins var in the
  same move. Acceptance: after N sequential `withTasks`, dispatcher
  bookkeeping is empty.
- The panama entry becomes: lazy runtime → `newState()` → one VM total per
  `withTasks` call.
- **Suspend-transfer unification**: the wasm shim already exports
  `lx_set_suspend_token` / `lx_get_suspend_token` — the same per-thread token
  machinery panama uses. `Trampoline.pendingSuspend` (single slot) dies; both
  backends implement §2.3's `takePendingSuspend(thread)` identically:
  dispatcher allocates a token in a suspend registry → shim stores it on the
  thread → the Driver reads the token and consumes the registry entry.
- `PanamaState` is **deleted in-change** (Q8 resolved 2026-06-11): no
  deprecated shim. The low-level panama suites migrate to `PanamaBinding` +
  explicit `newState()`/`closeState()` — the surface they exercise is
  `Binding[MemorySegment]` anyway, and low-level tests should drive the
  production path.

### 2.2 wasm: root-cause, then one module instance for N sequential states

Phase 1 — **reproduce and bisect** (no fix yet):
single `load()`, then two sequential states (interleaved states are out per
§0). Capture which op aborts and on which state. Each suspect gives
a distinguishable signature (stale-fnId crash fires inside an upcall;
LxStateData use-after-free fires in `lx_*` on the second state; heap-view
staleness fires only after memory growth).

Phase 2 — fix accordingly. Expected shape regardless of which suspect wins:

- `WasmBinding.closeState` unregisters that state's fnIds (requires tracking
  fnId→state at registration — `Trampoline.register(state, fn)`).
- `Trampoline` stops being a process-global mutable singleton: its table is
  keyed per wasm instance, owned by `WasmModule`.
- The wasm entry stops reloading: module init becomes a lazy single load in
  the companion object — the same runtime-singleton shape as `PanamaRuntime`
  (Q3 resolved 2026-06-11). No public reload API. Tests that
  need a fresh instance (Phase 1 bisect, leak isolation) drive
  `LuauShimFactory` directly in the test harness.
- Tests drop reload-per-test; module instantiation happens once per process.

Phase 3 — measure: wasm suite wall-clock before/after (today every test pays
module instantiation).

### 2.3 Scheduler/Driver (the focus)

Revive plan 06's single-Driver scheduler as a first-class, wired,
cross-platform module, integrated with the facade. Building blocks already on
disk: `Scheduler[H]` (spawn / spawnImmediate / deferTask / scheduleDelayed /
cancelTask / enqueueResume / runAllReady / close), `Task` + `TaskState`,
`ReadyTask`, `PlatformQueue`, `ErrorPolicy`, `TaskHandle`; `stdlib`'s
`TaskLibrary` + `StdlibOpener` on top.

Work items:

1. **Wire the modules**: `scheduler` and `stdlib` enter `build.mill` as
   cross-platform modules (jvm + js), same shape as `core`. `PlatformQueue`
   gets its JS actual (plain `ArrayDeque` — single-threaded). The JVM
   `java.util.Timer` in `scheduleDelayed` gets a platform seam (JS:
   `setTimeout`).
2. **One pending-suspend contract on `Binding[H]`**:
   `takePendingSuspend(thread: H): Option[NativeFnResult.Suspend]` — the
   Driver calls it immediately after a `Yielded` resume. Panama implements it
   via the per-thread shim token (`lx_get_suspend_token(thread)` →
   `SuspendRegistry.consume`); wasm via `Trampoline.consumePendingSuspend()`.
   The Scheduler's private slot dies. Reentrancy stays forbidden (plan 06
   R-01): only the Driver resumes, one Task at a time.
3. **VM ownership** (Q5 resolved): exactly one owner — the facade. The
   scheduler is *mounted on* a state; `Scheduler.close()` only cancels tasks
   + releases refs (its `closeState` call is dropped).
4. **Facade integration — `withTasks`, the ONLY entry (Q4 resolved
   2026-06-11; amended same day: `withState` is deleted, not retained)**.
   Rationale: ADR-0001 means there is no synchronous execution path — every
   chunk is a coroutine through `lua_resume`; `withState`'s "synchrony" was
   the MaxResumes auto-pump faking it. One entry, one regime. Two-phase, both
   lambdas synchronous on the Driver thread:

   ```scala
   def withTasks[H, A](binding, libs, deadline: Option[FiniteDuration] = None)(
     setup:  TaskWorld[H]^ => Unit,   // t=0: set globals, install host fns, spawn
   )(finish: LuaState[H]^  => A       // at quiescence: read results, state alive
   ): TaskResult[A]
   ```

   - The lambdas are never async (`f => Future` examined and rejected:
     `scala.concurrent.Future` is not capture-annotated, so the state
     capability would smuggle through closures; and Future continuations hop
     threads, violating §0's 1-VM-1-thread). Async-ness lives in the result:
     completions enqueue from any thread, the Driver pumps, the entry
     completes the result at quiescence and only then closes the state.
   - **`TaskResult[A]`, not `Future[A]`** (decided 2026-06-11): `Future`
     drags in `ExecutionContext` and cross-thread continuation composition we
     deliberately don't support. Minimal one-shot surface:
     `poll: Option[Try[A]]`, `onComplete(Try[A] => Unit)` (fires on the
     Driver / event loop), and JVM-only `await(timeout): Try[A]` which blocks
     the caller thread — never the VM. No combinators, no EC.
   - **Quiescence** = run queue empty AND no pending completion (in-flight
     `Suspend` or armed timer = pending; bare-yield parked = abandoned →
     cancelled at quiescence so the result always completes).
   - `TaskWorld` = the `LuaState` surface + `spawn`/`spawnFn` (handle-based,
     not raw `Ref`s) + `defineAsync` (host `A => async R` wrapped into
     `Suspend(register)` whose `resume` enqueues). `pushValue`'s LuaRef-to-nil
     degradation is replaced by a real ref push; the scheduler owns its pins
     (a task may outlive the `RefScope` that minted its function).
5. **One yield regime — the MaxResumes pump dies.** Sync accessors
   (`eval`/`get`/`set`/`call`) stay synchronous on the Driver thread with
   honest semantics: a chunk/function that parks (yields) inside `eval`/
   `call` is a `Failure("chunk suspended — spawn it as a task")`; pure
   compute completes on first resume, identical to today. `spawn` is the
   async spelling. `coro()`/`LuaCoro` (manual host-stepped coroutines on the
   Driver thread) survive — invisible to the scheduler, per ADR-0004.
   Test migration: the TC-API suite moves from `withState` to
   `withTasks(){ w => … }{ _ => () }` + deadline, in the same change
   (pre-1.0, no aliases — same call as plan 09's rename).
6. **`task.*` library** (Q7 resolved): `withTasks` automatically installs
   `task.spawn` / `task.defer` / `task.delay` / `task.wait` / `task.cancel`
   backed by the mounted scheduler (via `StdlibOpener`/`TaskLibrary`,
   wired + made cross-platform). The rest of plan 07 stays deferred.

### 2.4 Shared acceptance

- Lifecycle (both backends): two sequential `withTasks` on one runtime work;
  a second entry while one is live (concurrent or nested) throws
  `IllegalStateException`; after N sequential states, runtime bookkeeping is
  empty (no fnId / suspend leak). Panama-only: exactly one `lx_newstate` per
  `withTasks` (countable via a test dispatcher hook).
- Scheduler (both backends): a host fn returns `Suspend`; the Task parks; an
  off-Driver completion enqueues; the Driver resumes with the value (plan
  06's ITC-01, finally runnable for real). Cancel fires on close;
  double-resume is a no-op; bare `coroutine.yield` parks forever;
  `task.wait` round-trips through the timer seam on both platforms.

## 3. Non-goals

- Multi-Driver worker pool / VM migration across threads (ADR-0002 —
  deferred; §0 decision).
- Parallel Lua compute, actor-style multi-VM worlds, cross-VM messaging.
- Multi-threaded access to one VM.
- Wasm multi-module concurrency (two instances side by side).
- Pooling/reusing VMs across `withTasks` calls.

## 4. Open questions — all resolved (grilled 2026-06-11)

1. **Runtime/Binding shape (panama). RESOLVED (2026-06-11):** the binding is
   the runtime surface — one binding, many VMs. Both backends unify on the
   stateless-binding shape (no VM identity, no per-VM fields; all methods
   take the target state). The `PanamaState.use` anchor pattern dies. See
   §2.1.
2. **Runtime lifetime. RESOLVED (2026-06-11):** lazy process-wide singleton,
   never closed, both backends (wasm's module instance = the same singleton;
   `load()` becomes lazy ensure-loaded). Per-VM purge in `VmData` (suspends +
   fnIds) is the leak firewall that makes never-closing safe. See §2.1.
3. **wasm reload. RESOLVED (2026-06-11):** same shape as panama — one lazy
   load in the companion object, no public reload. Tests needing a fresh
   instance drive `LuauShimFactory` directly. See §2.2.
4. **Scheduler facade entry. RESOLVED (2026-06-11):** two-phase
   `withTasks(setup)(finish): TaskResult[A]`, the ONLY entry — `withState`
   deleted, tests migrate in-change; result is `TaskResult`, not `Future`
   (no EC, no cross-thread continuations). See §2.3 items 4–5.
5. **Driver pump + deadline. RESOLVED (2026-06-11):** "enqueue wakes the
   Driver", symmetric. JVM: dedicated Driver (virtual) thread per
   `withTasks` — runs `newState()`, `setup`, every resume, `finish`; parked
   when idle, `enqueue` unparks; dies after `finish`. JS: `enqueue` schedules
   one microtask drain if none is scheduled (no `setInterval` — 4ms clamp,
   busy idle). Caller-pumped rejected (forgotten pump = silent hang).
   Deadline: optional, default none (armed timer = pending, holds the result
   — documented edge); at expiry cancel all live tasks (fire `Cancel` hooks)
   and FAIL the `TaskResult` with a timeout `LuaError` — never run `finish`
   on a partial world. `Scheduler.close()` stops closing the VM — the facade
   owns it.
6. **Task-failure policy. RESOLVED (2026-06-11): fail-fast default.** First
   unhandled task error → cancel all live tasks → fail the `TaskResult` with
   that `LuaError`; `finish` never runs on a partial world. `ErrorPolicy`
   stays as the pluggable opt-in for log-and-continue. `eval`/`call` `Try`
   failures inside `setup` are user-handled, not policy events; `setup`/
   `finish` throwing fails the `TaskResult` directly.
7. **Plan-07 absorption. RESOLVED (2026-06-11):** the five core fns land
   here — `task.spawn`/`defer`/`delay`/`wait`/`cancel`, installed by
   `withTasks` automatically (they are load-bearing for the entry's
   semantics). Scala-side `TaskHandle` stays minimal: `cancel()` + `isDone`,
   Driver-thread use in `setup`; no result-reading surface yet. Deferred to
   the plan-07 rump: full stdlib audit, `lua_resetthread` reuse,
   parent-error propagation beyond fail-fast, fuel, richer `TaskHandle`.
8. **`PanamaState` fate. RESOLVED (2026-06-11): deleted in-change**, no
   deprecated shim; low-level suites migrate to `PanamaBinding` + explicit
   `newState()`/`closeState()`. See §2.1.

## 5. Implementation plan (written 2026-06-11)

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** land §2 end-to-end — stateless runtime singletons on both backends,
one live state per runtime, the wired cross-platform Scheduler/Driver, and
`withTasks(setup)(finish): TaskResult[A]` as the only facade entry.

**Architecture:** eleven tasks, each leaving the tree green. Order: core
`Binding` contract (slot + suspend transfer) → panama split → wasm bisect +
fix → scheduler module wiring → scheduler rework → driver primitives
(`TaskResult`/`Pump`) → `withTasks` → one yield regime → `withState` deletion
→ acceptance sweep. The facade entry object is `luau.api.Tasks` (it lives in
the `stdlib` module, which sees both `scheduler` and `core`; `object Luau`
in core is deleted with `withState` in Task 10 — backends expose
`PanamaLuau.withTasks` / `WasmLuau.withTasks` as the user-facing spellings).

**Tech Stack:** Scala 3.8.3 (capture checking per-file), Mill (via
`./mill-launcher.sh`, NEVER `./mill` — that path is a directory), munit,
Panama FFM (JVM), Scala.js + Emscripten wasm shim (JS).

**Mill discipline (every task):**

```bash
MILL_LOG_DIR="/tmp/mill-logs/luau-scala-$(git rev-parse --abbrev-ref HEAD | tr '/ ' '--')"
mkdir -p "$MILL_LOG_DIR"
./mill-launcher.sh <targets> > "$MILL_LOG_DIR/<task>-$RANDOM.log" 2>&1; echo "exit=$?"
rg -n 'error|FAIL|tasks failed' "$MILL_LOG_DIR/<task>-*.log"
```

Never wildcard (`__`). Multi-target: `./mill-launcher.sh a + b + c`. Commit
messages: single-line `-m` is fine; multi-line via Write tool to `/tmp/msg.txt`
then `git commit -F /tmp/msg.txt` (never heredoc — `cat` is aliased to `bat`).

---

### Task 1: core contract — live-state slot, suspend transfer, `resumeError`

Adds four members to `Binding[H]` and moves `SuspendRegistry` into core.
Every binding (Fake, Panama, Wasm) implements them in this task so the tree
stays green. The wasm token path lands here too — it kills
`Trampoline.pendingSuspend` immediately (one mechanism from day one).

**Files:**
- Modify: `core/src/luau/core/Binding.scala`
- Create: `core/src/luau/core/SuspendRegistry.scala` (moved from panama, + `clear()`)
- Delete: `panama/src/luau/panama/SuspendRegistry.scala`
- Modify: `core/src/luau/core/fake/FakeBinding.scala`
- Modify: `panama/src/luau/panama/PanamaState.scala` (interim impls; file dies in Task 2)
- Modify: `wasm/src/luau/wasm/WasmBinding.scala`, `wasm/src/luau/wasm/Trampoline.scala`
- Modify: `panama/test/src/luau/panama/SuspendResumeTest.scala`
- Test: `core/test/src/luau/core/BindingContractSpec.scala` (new)

- [ ] **Step 1.1: read the shim's token default**

Run: `rg -n 'suspend_token' shim/src/lx.cpp -A4`
Confirm the per-thread token storage initializes to `0` (so `0` = "no pending
suspend"; `SuspendRegistry.seq` starts at `1`, no collision). If the default
is not `0`, initialize it to `0` in `lx_newstate`/thread setup and rebuild the
shim — the contract below assumes token `0` = none.

- [ ] **Step 1.2: write the failing core test**

```scala
// core/test/src/luau/core/BindingContractSpec.scala
package luau.core

import munit.FunSuite
import luau.core.fake.FakeBinding

class BindingContractSpec extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    FakeBinding.releaseStateSlot() // isolate slot state between tests

  test("second live state throws IllegalStateException"):
    val s1 = FakeBinding.newState()
    intercept[IllegalStateException] { FakeBinding.newState() }
    FakeBinding.closeState(s1)
    val s2 = FakeBinding.newState() // sequential reuse is fine
    FakeBinding.closeState(s2)

  test("reserveStateSlot blocks a second reservation and newState honors it"):
    FakeBinding.reserveStateSlot()
    intercept[IllegalStateException] { FakeBinding.reserveStateSlot() }
    val s = FakeBinding.newState() // fills the reservation
    FakeBinding.closeState(s)      // frees the slot
    FakeBinding.reserveStateSlot()
    FakeBinding.releaseStateSlot()

  test("takePendingSuspend is one-shot"):
    val s = FakeBinding.newState()
    val suspend = NativeFnResult.Suspend(_ => Cancel.noop)
    FakeBinding.setPendingSuspendForTest(s, suspend)
    assertEquals(FakeBinding.takePendingSuspend(s), Some(suspend))
    assertEquals(FakeBinding.takePendingSuspend(s), None)
    FakeBinding.closeState(s)
```

- [ ] **Step 1.3: run it to see it fail to compile**

```bash
./mill-launcher.sh core.jvm.test > "$MILL_LOG_DIR/t1-red-$RANDOM.log" 2>&1; echo "exit=$?"
```
Expected: compile errors — the four members don't exist.

- [ ] **Step 1.4: extend `Binding[H]`**

Append to `core/src/luau/core/Binding.scala` (inside the trait):

```scala
  // ---- Live-state slot (plan 10 §0: one live state per runtime) --------

  /** Reserve the runtime's single live-state slot without creating the VM.
    * The facade entry calls this on the caller thread so a second entry
    * fails synchronously; the Driver's newState() then fills the
    * reservation. Throws IllegalStateException if a state is live or the
    * slot is already reserved.
    */
  def reserveStateSlot(): Unit

  /** Free the slot without closing a state (entry failed before newState).
    * Idempotent; closeState frees the slot itself.
    */
  def releaseStateSlot(): Unit

  // ---- Suspend transfer (§2.3 item 2) ----------------------------------

  /** Consume the pending Suspend a host fn left on `thread` during the last
    * resume, if any. One-shot: a second call returns None. Implementations
    * route through the shim's per-thread token (lx_get_suspend_token) and a
    * SuspendRegistry; token 0 means none.
    */
  def takePendingSuspend(thread: H): Option[NativeFnResult.Suspend]

  // ---- Failing a suspension --------------------------------------------

  /** Resume a yielded thread by raising `error` at its suspension point
    * (script-side pcall can observe it). How the host fails a Suspend.
    */
  def resumeError(thread: H, error: LuaError): ResumeResult
```

Also `newState()`'s doc comment gains: "throws IllegalStateException while
another state is live (§0)".

- [ ] **Step 1.5: move `SuspendRegistry` to core**

Create `core/src/luau/core/SuspendRegistry.scala` (delete the panama file;
contents identical apart from the package and `clear()`):

```scala
package luau.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Token → Suspend table backing Binding.takePendingSuspend. Tokens start at
  * 1 (0 = "none" in the shim's per-thread slot) and are process-unique.
  */
final class SuspendRegistry:
  private val seq = new AtomicLong(1L)
  private val table = new ConcurrentHashMap[Long, NativeFnResult.Suspend]()

  def allocToken(suspend: NativeFnResult.Suspend): Long =
    val tok = seq.getAndIncrement()
    table.put(tok, suspend)
    tok

  def consume(token: Long): Option[NativeFnResult.Suspend] =
    Option(table.remove(token))

  /** Drop all in-flight entries — closeState's purge on backends that keep
    * one registry per runtime (wasm). */
  def clear(): Unit = table.clear()

  def size: Int = table.size
```

Update imports in `panama/src/luau/panama/PanamaState.scala` and
`NativeFnDispatcher.scala` (`luau.core.SuspendRegistry`).

- [ ] **Step 1.6: implement on `FakeBinding`**

In `core/src/luau/core/fake/FakeBinding.scala`:

```scala
  // ---- Live-state slot ---------------------------------------------------
  private var slot: Int = 0 // 0 free, 1 reserved, 2 live
  private var liveState: FakeState | Null = null
  private val pendingByState = scala.collection.mutable.HashMap[FakeState, NativeFnResult.Suspend]()

  def reserveStateSlot(): Unit =
    if slot != 0 then
      throw new IllegalStateException(
        "one live state per runtime (plan 10 §0): close the current state first")
    slot = 1

  def releaseStateSlot(): Unit =
    slot = 0
    liveState = null
    pendingByState.clear()

  def takePendingSuspend(thread: FakeState): Option[NativeFnResult.Suspend] =
    pendingByState.remove(thread)

  /** Test hook: plant a pending Suspend the way a dispatcher would. */
  def setPendingSuspendForTest(thread: FakeState, s: NativeFnResult.Suspend): Unit =
    pendingByState.update(thread, s)

  def resumeError(thread: FakeState, error: LuaError): ResumeResult =
    ResumeResult.Error(error)
```

And amend the existing lifecycle methods:

```scala
  def newState(): FakeState =
    if slot == 2 then
      throw new IllegalStateException(
        "one live state per runtime (plan 10 §0): close the current state first")
    slot = 2
    val s = FakeState()
    liveState = s
    s

  def closeState(state: FakeState): Unit =
    state.markClosed()
    state.registry.clear()
    if liveState == state then releaseStateSlot()
```

- [ ] **Step 1.7: interim impls on `PanamaState`** (dies in Task 2 — keep minimal)

```scala
  // plan 10 Task 1 interim — PanamaRuntime owns this slot from Task 2 on.
  private val stateSlot = new java.util.concurrent.atomic.AtomicInteger(0) // 0 free, 1 reserved, 2 live

  def reserveStateSlot(): Unit =
    if !stateSlot.compareAndSet(0, 1) then
      throw new IllegalStateException(
        "one live state per runtime (plan 10 §0): close the current state first")

  def releaseStateSlot(): Unit = stateSlot.set(0)

  def takePendingSuspend(thread: MemorySegment): Option[NativeFnResult.Suspend] =
    val token: Long = LxHandles.lx_get_suspend_token.invokeExact(thread)
    if token == 0L then None
    else
      LxHandles.lx_set_suspend_token.invokeExact(thread, 0L): Unit
      suspendRegistry.consume(token)
```

`newState()` gains slot bookkeeping (`if stateSlot.get == 2 then throw …;
stateSlot.set(2)` before `lx_newstate`; the anchor `L` is exempt — it never
goes through `newState`). `closeState(state)` (the non-`L` branch) sets
`stateSlot.set(0)`. `resumeError` already exists with the exact signature —
it just becomes an override. Delete the `lastYieldToken` field; its only
consumer migrates in Step 1.10.

- [ ] **Step 1.8: wasm — token path replaces `Trampoline.pendingSuspend`**

`wasm/src/luau/wasm/Trampoline.scala`: delete `pendingSuspend`,
`consumePendingSuspend()`; add a registry reference and rewrite the
`Suspend` dispatch case:

```scala
  val suspendRegistry = new luau.core.SuspendRegistry

  // in dispatch():
  case s @ NativeFnResult.Suspend(_) =>
    val token = suspendRegistry.allocToken(s)
    WasmModule.module._lx_set_suspend_token(thread, js.BigInt(token.toString))
    LxReturn.Suspend
```

(`reset()` also clears `suspendRegistry` via `suspendRegistry.clear()`.)

`wasm/src/luau/wasm/WasmBinding.scala`:

```scala
  // ---- Live-state slot ---------------------------------------------------
  override def reserveStateSlot(): Unit = WasmBinding.reserveSlot()
  override def releaseStateSlot(): Unit = WasmBinding.releaseSlot()

  override def takePendingSuspend(thread: Int): Option[NativeFnResult.Suspend] =
    val tok = module._lx_get_suspend_token(thread).toString.toLong
    if tok == 0L then None
    else
      module._lx_set_suspend_token(thread, js.BigInt(0))
      Trampoline.suspendRegistry.consume(tok)

  override def resumeError(thread: Int, error: LuaError): ResumeResult =
    pushString(thread, error.message)
    val (nresultsPtr, readNResults) = WasmMarshal.allocOutInt()
    try
      module._lx_resume_error(thread, nresultsPtr) match
        case LxStatus.Ok    => ResumeResult.Returned(readNResults())
        case LxStatus.Yield => ResumeResult.Yielded(readNResults())
        case _ =>
          val errMsg = readError(thread)
          if errMsg.nonEmpty then module._lx_pop(thread, 1)
          ResumeResult.Error(LuaError.runtime(errMsg))
    finally module._free(nresultsPtr)
```

Slot state on the companion (JS is single-threaded; plain vars):

```scala
object WasmBinding:
  private var slot: Int = 0 // 0 free, 1 reserved, 2 live
  private[wasm] def reserveSlot(): Unit =
    if slot != 0 then
      throw new IllegalStateException(
        "one live state per runtime (plan 10 §0): close the current state first")
    slot = 1
  private[wasm] def releaseSlot(): Unit = slot = 0
  private[wasm] def markLive(): Unit =
    if slot == 2 then
      throw new IllegalStateException(
        "one live state per runtime (plan 10 §0): close the current state first")
    slot = 2
  def create(): WasmBinding = new WasmBinding()
```

`newState()` calls `WasmBinding.markLive()` first; `closeState()` ends with
`WasmBinding.releaseSlot()` and `Trampoline.suspendRegistry.clear()`.

- [ ] **Step 1.9: rewrite the broken tests this exposes**

`wasm/test/src/luau/wasm/WasmSpecificSuite.scala` TC-WASM-04 currently holds
two live states — illegal under §0. Rewrite:

```scala
  test("TC-WASM-04 one live state per runtime; sequential reuse works"):
    val b = WasmBinding.create()
    val s1 = b.newState()
    intercept[IllegalStateException] { b.newState() }
    b.pushNumber(s1, 1.0)
    assert(b.toNumber(s1, -1).contains(1.0))
    b.pop(s1, 1)
    b.closeState(s1)
    val s2 = b.newState()
    try
      b.pushNumber(s2, 2.0)
      assert(b.toNumber(s2, -1).contains(2.0))
      b.pop(s2, 1)
    finally b.closeState(s2)
```

Sweep for other multi-live-state tests:
`rg -n 'newState' core/test panama/test wasm/test` — every hit that opens a
second state before closing the first gets the same sequential rewrite.
(`SharedBackendSuite` opens one state per test — unaffected.)

- [ ] **Step 1.10: migrate `SuspendResumeTest` off `lastYieldToken`**

Replace every `ps.lastYieldToken` + `ps.suspendRegistry.consume(token)`
read with the contract call:

```scala
  val suspend = ps.takePendingSuspend(thread)
    .getOrElse(fail("expected a pending Suspend on the thread"))
```

- [ ] **Step 1.11: green + commit**

```bash
./mill-launcher.sh core.jvm.test + core.js.test + panama.test + wasm.test \
  > "$MILL_LOG_DIR/t1-green-$RANDOM.log" 2>&1; echo "exit=$?"
git add -A && git commit -m "feat(core): Binding live-state slot + takePendingSuspend/resumeError contract"
```

---

### Task 2: panama split — `PanamaRuntime` + `PanamaBinding`, `PanamaState` deleted

**Files:**
- Create: `panama/src/luau/panama/PanamaRuntime.scala`
- Create: `panama/src/luau/panama/PanamaBinding.scala`
- Modify: `panama/src/luau/panama/NativeFnDispatcher.scala`
- Modify: `panama/src/luau/panama/PanamaLuau.scala`
- Delete: `panama/src/luau/panama/PanamaState.scala`
- Modify: every panama test file referencing `PanamaState`
  (enumerate: `rg -ln 'PanamaState' panama/test`)
- Test: `panama/test/src/luau/panama/PanamaLifecycleSpec.scala` (new)

- [ ] **Step 2.1: write the failing lifecycle test**

```scala
// panama/test/src/luau/panama/PanamaLifecycleSpec.scala
package luau.panama

import munit.FunSuite
import luau.core.LuauLib

class PanamaLifecycleSpec extends FunSuite:

  test("LC-01 sequential states leave dispatcher bookkeeping empty"):
    val b = PanamaBinding.instance
    (1 to 5).foreach { i =>
      val s = b.newState()
      try
        b.openLibs(s, LuauLib.Standard)
        b.sandbox(s)
        b.registerNativeFn(s, (_, _) => luau.core.NativeFnResult.Return(0))
        b.pop(s, 1) // registerNativeFn leaves the fn on the stack
      finally b.closeState(s)
    }
    assertEquals(PanamaRuntime.registeredFnCount, 0)
    assertEquals(PanamaRuntime.inFlightSuspendCount, 0)

  test("LC-02 exactly one lx_newstate per newState"):
    val before = PanamaRuntime.statesOpened
    val b = PanamaBinding.instance
    val s = b.newState()
    b.closeState(s)
    assertEquals(PanamaRuntime.statesOpened - before, 1)

  test("LC-03 second live state throws; sequential reuse fine"):
    val b = PanamaBinding.instance
    val s1 = b.newState()
    intercept[IllegalStateException] { b.newState() }
    b.closeState(s1)
    val s2 = b.newState()
    b.closeState(s2)
```

- [ ] **Step 2.2: create `PanamaRuntime`**

```scala
// panama/src/luau/panama/PanamaRuntime.scala
package luau.panama

import java.lang.foreign.{Arena, MemorySegment}
import java.util.concurrent.atomic.AtomicReference
import luau.core.SuspendRegistry

/** Process-level singleton: arena, dispatcher, upcall stub, and the single
  * live-state slot (§0). Owns NO VM. Lazy on first use; never closed (Q2) —
  * per-VM purge in VmData is the leak firewall.
  */
object PanamaRuntime:

  final class VmData(val state: MemorySegment):
    val suspendRegistry = new SuspendRegistry
    val fnIds = scala.collection.mutable.Set.empty[Int]

  private enum Slot:
    case Free
    case Reserved
    case Live(data: VmData)

  private val slot = new AtomicReference[Slot](Slot.Free)

  lazy val arena: Arena = Arena.ofShared()
  lazy val dispatcher: NativeFnDispatcher = new NativeFnDispatcher
  lazy val upcallStub: MemorySegment = dispatcher.allocateUpcallStub(arena)

  private val illegal =
    "one live state per runtime (plan 10 §0): close the current state first"

  def reserve(): Unit =
    if !slot.compareAndSet(Slot.Free, Slot.Reserved) then
      throw new IllegalStateException(illegal)

  def release(): Unit = slot.set(Slot.Free)

  /** newState: Free or Reserved → Live; Live → throw. */
  def mount(state: MemorySegment): VmData =
    val data = new VmData(state)
    slot.get match
      case Slot.Live(_) => throw new IllegalStateException(illegal)
      case prev =>
        if !slot.compareAndSet(prev, Slot.Live(data)) then
          throw new IllegalStateException(illegal)
        data

  /** closeState: purge fnIds + drop in-flight suspends, then Free. */
  def unmount(state: MemorySegment): Unit =
    slot.get match
      case Slot.Live(d) if d.state.address() == state.address() =>
        d.fnIds.foreach(dispatcher.unregister)
        d.suspendRegistry.clear()
        slot.set(Slot.Free)
      case _ => () // closing a non-live state: nothing mounted to purge

  def liveData: Option[VmData] = slot.get match
    case Slot.Live(d) => Some(d)
    case _            => None

  // ---- Test hooks (acceptance §2.4) -------------------------------------
  @volatile private[panama] var statesOpened: Long = 0L
  private[panama] def countOpen(): Unit = statesOpened += 1
  private[panama] def registeredFnCount: Int = dispatcher.registeredCount
  private[panama] def inFlightSuspendCount: Int =
    liveData.map(_.suspendRegistry.size).getOrElse(0)
```

- [ ] **Step 2.3: create `PanamaBinding`**

Move every `Binding` method body from `PanamaState` verbatim into a stateless
class — drop `L`, `closed`, `checkOpen()`, `close()`, `releaseRef`,
`isClosed`; keep `withArena`, `readError`, `strnlen` as private helpers
unchanged. The lifecycle and suspend members:

```scala
// panama/src/luau/panama/PanamaBinding.scala
package luau.panama

import java.lang.foreign.MemorySegment
import luau.core.*

/** Stateless Binding[MemorySegment] over PanamaRuntime — mirror of
  * WasmBinding over WasmModule/Trampoline. No VM identity, no per-VM fields;
  * every method takes its target state (§2.1, Q1).
  */
final class PanamaBinding private () extends Binding[MemorySegment]:

  def newState(): MemorySegment =
    val s: MemorySegment = LxHandles.lx_newstate.invokeExact(PanamaRuntime.upcallStub)
    if s.address() == 0L then throw new OutOfMemoryError("lx_newstate returned NULL")
    PanamaRuntime.mount(s)
    PanamaRuntime.countOpen()
    s

  def closeState(state: MemorySegment): Unit =
    PanamaRuntime.unmount(state)
    LxHandles.lx_close.invokeExact(state): Unit

  def reserveStateSlot(): Unit = PanamaRuntime.reserve()
  def releaseStateSlot(): Unit = PanamaRuntime.release()

  def takePendingSuspend(thread: MemorySegment): Option[NativeFnResult.Suspend] =
    val token: Long = LxHandles.lx_get_suspend_token.invokeExact(thread)
    if token == 0L then None
    else
      LxHandles.lx_set_suspend_token.invokeExact(thread, 0L): Unit
      PanamaRuntime.liveData.flatMap(_.suspendRegistry.consume(token))

  def registerNativeFn(state: MemorySegment, fn: NativeFn[MemorySegment]): Unit =
    val fnId = PanamaRuntime.dispatcher.register(fn)
    PanamaRuntime.liveData.foreach(_.fnIds.add(fnId))
    withArena { arena =>
      val name = Marshal.toNativeString(s"nativeFn_$fnId", arena)
      LxHandles.lx_register_native.invokeExact(state, fnId, name): Unit
    }

  // … all other Binding methods: bodies copied from PanamaState unchanged,
  // minus every checkOpen() call …

object PanamaBinding:
  val instance: PanamaBinding = new PanamaBinding()
```

`unref` loses its `if !closed` guard (the runtime never closes); `resume`,
`resumeError`, `compileAndLoad`, stack ops, table ops, globals, `openLibs`,
`sandbox`, `ref` move unchanged.

- [ ] **Step 2.4: rework `NativeFnDispatcher`**

Delete `private var ps`, `init`. Add a count hook. Suspend dispatch goes
through the runtime's live slot:

```scala
  private[panama] def registeredCount: Int = fns.size

  // in dispatch(), replacing the Suspend case:
  case s @ NativeFnResult.Suspend(_) =>
    PanamaRuntime.liveData match
      case None =>
        pushErrorMessage(thread, "luau-scala: Suspend with no live state")
        LX_FAIL
      case Some(vm) =>
        val token = vm.suspendRegistry.allocToken(s)
        lx_set_suspend_token.invokeExact(thread, token): Unit
        LX_SUSPEND
```

This kills the `init(ps)` NPE window, the suspend leak (registry dies with
`VmData`), the host-fn closure leak (`fnIds` purge in `unmount`), and
`lastYieldToken` — the four §1.1 defects.

- [ ] **Step 2.5: delete `PanamaState`; rewire `PanamaLuau` (interim)**

```scala
// panama/src/luau/panama/PanamaLuau.scala
object PanamaLuau:
  def withState[A](
    libs: Set[LuauLib] = LuauLib.Standard
  )(f: LuaState[MemorySegment]^ => A): A =
    Luau.withState(PanamaBinding.instance, libs)(f)
```

One VM total per call — §1.1's anchor VM is gone. (`withState` itself dies in
Task 10.)

- [ ] **Step 2.6: migrate the panama suites**

`rg -ln 'PanamaState' panama/test` and rewrite each:
`PanamaState.use(ps => body)` → `val b = PanamaBinding.instance; body` with
explicit `newState()`/`closeState()` where the body opened states through the
anchor. `PanamaApiSuite` becomes:

```scala
class PanamaApiSuite extends ApiSuite[MemorySegment]:
  override def withBinding[A](f: Binding[MemorySegment] => A): A =
    f(PanamaBinding.instance)
```

`SuspendResumeTest` swaps `ps` for `PanamaBinding.instance` (already on
`takePendingSuspend` from Task 1). Tests that relied on `ps.close()` between
cases now `closeState` the states they opened — the runtime itself is never
closed.

- [ ] **Step 2.7: green + commit**

```bash
./mill-launcher.sh panama.test > "$MILL_LOG_DIR/t2-green-$RANDOM.log" 2>&1; echo "exit=$?"
git add -A && git commit -m "feat(panama): stateless PanamaBinding over PanamaRuntime singleton; PanamaState deleted"
```

---

### Task 3: wasm Phase 1 — reproduce and bisect (no fix)

Goal: pin which §1.2 suspect causes the heap-dirty crash. Findings get
recorded in §1.2 of this doc; the test file lands with Task 4's fix (a
hard wasm abort kills the runner, so don't commit it red).

**Files:**
- Create (uncommitted until Task 4): `wasm/test/src/luau/wasm/WasmLifecycleSpec.scala`

- [ ] **Step 3.1: write the bisect matrix**

```scala
// wasm/test/src/luau/wasm/WasmLifecycleSpec.scala
package luau.wasm

import munit.FunSuite
import luau.core.*

/** Plan 10 §2.2 Phase 1: one load, two sequential states, escalating
  * per-state work. Which variant crashes (and where) identifies the suspect:
  *   - crash inside an upcall on state 2          → stale fnId (Trampoline table)
  *   - crash in lx_* on state 2 (e.g. lx_rawgeti) → LxStateData use-after-free
  *   - crash only in the memory-growth variant    → stale heap view
  */
class WasmLifecycleSpec extends FunSuite:

  override def beforeAll(): Unit = WasmBackend.load() // ONE load for the whole suite

  private def runState(b: WasmBinding, work: (WasmBinding, Int) => Unit): Unit =
    val s = b.newState()
    try
      b.openLibs(s, LuauLib.Standard)
      b.sandbox(s)
      work(b, s)
    finally b.closeState(s)

  private def basicWork(b: WasmBinding, s: Int): Unit =
    val thread = b.newThread(s)
    assertEquals(b.compileAndLoad(thread, "local t = {1,2,3}; return t[2]", "=bisect"), Right(()))
    b.resume(thread, 0) match
      case ResumeResult.Returned(1) => assertEquals(b.toNumber(thread, -1), Some(2.0))
      case other                    => fail(s"unexpected: $other")
    b.pop(s, 1) // the thread object

  test("BISECT-01 two plain sequential states"):
    val b = WasmBinding.create()
    runState(b, basicWork)
    runState(b, basicWork)

  test("BISECT-02 state 1 registers a host fn + takes a ref"):
    val b = WasmBinding.create()
    runState(b, (b, s) =>
      b.registerNativeFn(s, (_, _) => NativeFnResult.Return(0))
      b.pop(s, 1)
      b.pushNumber(s, 7.0)
      val r = b.ref(s)
      r.close()
      basicWork(b, s))
    runState(b, basicWork)

  test("BISECT-03 state 1 grows memory (1MB string churn)"):
    val b = WasmBinding.create()
    runState(b, (b, s) =>
      val thread = b.newThread(s)
      assertEquals(
        b.compileAndLoad(thread, "local x = string.rep('a', 1024*1024); return #x", "=grow"),
        Right(()))
      b.resume(thread, 0) match
        case ResumeResult.Returned(1) => assertEquals(b.toNumber(thread, -1), Some(1048576.0))
        case other                    => fail(s"unexpected: $other")
      b.pop(s, 1))
    runState(b, basicWork)

  test("BISECT-04 host fn registered on state 1, INVOKED on state 2 (stale fnId)"):
    val b = WasmBinding.create()
    var leakedFnId = 0
    runState(b, (b, s) =>
      // capture the fnId the Trampoline hands out
      leakedFnId = Trampoline.register((_, _) => NativeFnResult.Return(0))
      basicWork(b, s))
    runState(b, (b, s) =>
      // push the stale id as a native fn on the NEW state and call it
      b.pushFunction(s, leakedFnId)
      val thread = b.newThread(s)
      b.pushCopy(thread, -1) // misuse is fine — we only care whether it aborts
      b.pop(s, 2))
```

- [ ] **Step 3.2: run, capture the signature**

```bash
./mill-launcher.sh "wasm.test.testOnly" "luau.wasm.WasmLifecycleSpec" \
  > "$MILL_LOG_DIR/t3-bisect-$RANDOM.log" 2>&1; echo "exit=$?"
rg -n 'abort|RuntimeError|unreachable|FAIL|Error' "$MILL_LOG_DIR"/t3-bisect-*.log
```

- [ ] **Step 3.3: record findings in §1.2**

Edit §1.2 of this doc: replace the three "known suspects, unverified" bullets
with the confirmed culprit(s) + the crash signature line from the log. If all
four BISECT tests pass on current code, record that too — it means the
reload-per-test in `WasmBackendSuite` is cargo cult and Task 4 is pure
restructure. Commit the doc edit only:

```bash
git add docs/plans/10-backend-lifecycle.md && git commit -m "docs(plan10): record wasm bisect findings (§1.2)"
```

---

### Task 4: wasm Phase 2 — one module instance, per-state purge, no reload

**Files:**
- Modify: `wasm/src/luau/wasm/WasmBackend.scala` (lazy ensure-loaded)
- Modify: `wasm/src/luau/wasm/Trampoline.scala` (fnId → owner state)
- Modify: `wasm/src/luau/wasm/WasmBinding.scala` (closeState purge)
- Modify: `wasm/src/luau/wasm/WasmLuau.scala` (stop reloading)
- Modify: `wasm/test/src/luau/wasm/WasmBackendSuite.scala`,
  `WasmApiSuite.scala`, `WasmConformanceSuite.scala`, `WasmSpecificSuite.scala`
  (drop reload-per-test)
- Commit: `wasm/test/src/luau/wasm/WasmLifecycleSpec.scala` (from Task 3, green)

- [ ] **Step 4.1: lazy single load**

```scala
// wasm/src/luau/wasm/WasmBackend.scala
object WasmBackend:

  private var loaded = false

  /** Lazy single load (§2.2, Q3): the module instance is the process-wide
    * runtime singleton — same shape as PanamaRuntime. No public reload.
    * Tests needing a fresh instance drive LuauShimFactory directly.
    */
  def ensureLoaded(): Unit =
    if !loaded then
      WasmModule.set(LuauShimFactory(scala.scalajs.js.Dynamic.literal()))
      Trampoline.install()
      loaded = true

  def createBinding(): WasmBinding =
    ensureLoaded()
    WasmBinding.create()
```

Keep `def load(): Unit = ensureLoaded()` as a deprecated-comment alias only if
the bisect suite needs it; otherwise update callers. `Trampoline.reset()` and
`Trampoline.uninstall()` are deleted (no reload exists to need them).

- [ ] **Step 4.2: Trampoline tracks fnId → owner state**

```scala
  // Trampoline.scala
  private val table = scala.collection.mutable.HashMap.empty[Int, NativeFn]
  private val owner = scala.collection.mutable.HashMap.empty[Int, Int] // fnId → state ptr

  def register(state: Int, fn: NativeFn): Int =
    val id = nextId
    nextId += 1
    table(id) = fn
    owner(id) = state
    id

  /** closeState purge: drop every fn the dying state registered. */
  def unregisterAllFor(state: Int): Unit =
    val dead = owner.collect { case (id, s) if s == state => id }.toList
    dead.foreach { id => table.remove(id); owner.remove(id) }

  def registeredCount: Int = table.size
```

(The old one-arg `register(fn)` overload is deleted; `WasmBinding.
registerNativeFn` — find it with `rg -n 'Trampoline.register' wasm/src` —
passes the state. The BISECT-04 test updates to `Trampoline.register(s, …)`.)

- [ ] **Step 4.3: closeState purges**

In `WasmBinding`:

```scala
  override def closeState(state: Int): Unit =
    Trampoline.unregisterAllFor(state)
    Trampoline.suspendRegistry.clear()
    module._lx_close(state)
    WasmBinding.releaseSlot()
```

Plus whatever Task 3's confirmed culprit demands (e.g. if LxStateData
use-after-free: audit shim `lx_close` vs outstanding suspend tokens — fix in
`shim/src/lx.cpp` and rebuild via the existing `shim.wasmBuildNative` flow).

- [ ] **Step 4.4: kill the reloads**

- `WasmLuau.withState`: drop `WasmBackend.load()`; body is
  `Luau.withState(WasmBackend.createBinding(), libs)(f)`.
- `WasmApiSuite.withBinding`: `f(WasmBackend.createBinding())` — no load call,
  no per-test reload comment.
- `WasmBackendSuite`: delete the reload-per-test override + the
  papering-over comment; one `WasmBackend.ensureLoaded()` in `beforeAll`.
- `WasmSpecificSuite.beforeAll`: `WasmBackend.ensureLoaded()`.

- [ ] **Step 4.5: lifecycle spec goes green; add bookkeeping assert**

Append to `WasmLifecycleSpec`:

```scala
  test("LC-W-01 N sequential states leave Trampoline bookkeeping empty"):
    val b = WasmBinding.create()
    (1 to 5).foreach { _ =>
      runState(b, (b, s) =>
        b.registerNativeFn(s, (_, _) => NativeFnResult.Return(0))
        b.pop(s, 1))
    }
    assertEquals(Trampoline.registeredCount, 0)
    assertEquals(Trampoline.suspendRegistry.size, 0)
```

- [ ] **Step 4.6: measure, green, commit**

```bash
/usr/bin/time -v ./mill-launcher.sh wasm.test > "$MILL_LOG_DIR/t4-green-$RANDOM.log" 2>&1; echo "exit=$?"
rg -n 'Elapsed|tests' "$MILL_LOG_DIR"/t4-green-*.log
```
Record before/after wall-clock in §2.2 Phase 3 (the Task-3 log has the
"before"). Commit:

```bash
git add -A && git commit -m "feat(wasm): lazy single module load, per-state fnId purge, reload-per-test removed"
```

---

### Task 5: scheduler module — cross-platform wiring + timer seam

**Files:**
- Move: `scheduler/jvm/src/luau/scheduler/*.scala` → `scheduler/src/luau/scheduler/`
- Move: `scheduler/jvm/test/src/luau/scheduler/*.scala` → `scheduler/test/src/luau/scheduler/`
- Create: `scheduler/src/luau/scheduler/TaskTimer.scala`
- Create: `scheduler/jvm/src/luau/scheduler/TimerPlatform.scala`
- Create: `scheduler/js/src/luau/scheduler/TimerPlatform.scala`
- Modify: `build.mill`, `scheduler/src/luau/scheduler/Scheduler.scala`
  (timer injection only — behavior rework is Task 6),
  `ErrorPolicy.scala` (String → LuaError),
  `scheduler/test/src/luau/scheduler/TestHelpers.scala` (new Binding members)

- [ ] **Step 5.1: move sources**

```bash
mkdir -p scheduler/src/luau/scheduler scheduler/test/src/luau/scheduler
git mv scheduler/jvm/src/luau/scheduler/*.scala scheduler/src/luau/scheduler/
git mv scheduler/jvm/test/src/luau/scheduler/*.scala scheduler/test/src/luau/scheduler/
```

- [ ] **Step 5.2: timer seam**

```scala
// scheduler/src/luau/scheduler/TaskTimer.scala
package luau.scheduler

import luau.core.Cancel

/** Platform timer seam (§2.3 item 1). Callbacks fire on the timer thread
  * (JVM) / event loop (JS) and must only enqueue + wake — never touch the VM
  * (ADR-0007: off-Driver completions enqueue, never resume inline).
  */
trait TaskTimer:
  def schedule(seconds: Double)(callback: () => Unit): Cancel
  def shutdown(): Unit

object TaskTimer:
  def create(): TaskTimer = TimerPlatform.create()
```

```scala
// scheduler/jvm/src/luau/scheduler/TimerPlatform.scala
package luau.scheduler

import java.util.{Timer, TimerTask}
import luau.core.Cancel

private[luau] object TimerPlatform:
  def create(): TaskTimer = new TaskTimer:
    private val timer = new Timer("luau-task-timer", true)
    def schedule(seconds: Double)(callback: () => Unit): Cancel =
      val tt = new TimerTask { def run(): Unit = callback() }
      timer.schedule(tt, (seconds * 1000).toLong.max(0L))
      Cancel(() => { tt.cancel(); () })
    def shutdown(): Unit = timer.cancel()
```

```scala
// scheduler/js/src/luau/scheduler/TimerPlatform.scala
package luau.scheduler

import scala.scalajs.js.timers
import luau.core.Cancel

private[luau] object TimerPlatform:
  def create(): TaskTimer = new TaskTimer:
    def schedule(seconds: Double)(callback: () => Unit): Cancel =
      val handle = timers.setTimeout(seconds * 1000)(callback())
      Cancel(() => timers.clearTimeout(handle))
    def shutdown(): Unit = ()
```

In `Scheduler.scala`: delete `private val timer = new Timer(…)` and the
`TimerTask` import; ctor gains `timer: TaskTimer = TaskTimer.create()`;
`scheduleTimer` becomes
`def scheduleTimer(seconds: Double)(callback: => Unit): Cancel =
timer.schedule(seconds)(() => callback)`. `close()` stops calling
`timer.cancel()` (the timer's owner shuts it down — for now the default-arg
timer leaks a daemon thread per Scheduler in tests; Task 8's Driver owns it
properly).

- [ ] **Step 5.3: ErrorPolicy carries LuaError**

```scala
// scheduler/src/luau/scheduler/ErrorPolicy.scala
package luau.scheduler

import luau.core.LuaError

/** Invoked when a Task fails. withTasks defaults to failFast (Q6). */
trait ErrorPolicy:
  def onTaskError(task: Task[?], error: LuaError): Unit

object ErrorPolicy:
  val logAndDiscard: ErrorPolicy = (task, error) =>
    Console.err.println(s"[luau-scheduler] Task ${task.id} failed: ${error.message}")

  /** Sentinel recognized by the Driver: first unhandled task error cancels
    * the world and fails the TaskResult. The function body is never called.
    */
  val failFast: ErrorPolicy = (_, _) => ()
```

`Scheduler.resumeTask`'s Error case passes `err` (the `LuaError`), not
`err.message`; `Task.setState(TaskState.Failed(err.message))` keeps the
String.

- [ ] **Step 5.4: wire modules in build.mill**

After the `panama` object in `build.mill`:

```scala
object scheduler extends Module {
  object jvm extends LuauCrossPlatformModule {
    override def moduleDeps = super.moduleDeps ++ Seq(core.jvm)
    override def sources = Task.Sources(moduleDir / os.up / "src", moduleDir / "src")
    object test extends ScalaTests with TestModule.Munit {
      override def sources = Task.Sources(moduleDir / os.up / os.up / "test" / "src")
      def mvnDeps = Seq(mvn"org.scalameta::munit::${build.munitVersion}")
    }
  }
  object js extends LuauCrossPlatformJSModule {
    override def moduleDeps = super.moduleDeps ++ Seq(core.js)
    override def sources = Task.Sources(moduleDir / os.up / "src", moduleDir / "src")
    object test extends ScalaJSTests with TestModule.Munit {
      override def sources = Task.Sources(moduleDir / os.up / os.up / "test" / "src")
      def mvnDeps = Seq(mvn"org.scalameta::munit::${build.munitVersion}")
    }
  }
}
```

- [ ] **Step 5.5: fix what now compiles**

`TestHelpers.TestBinding` implements the four Task-1 `Binding` members
(delegate to a `pendingByState` map + a slot var, mirroring `FakeBinding`
Step 1.6 — copy that code, plus
`def resumeError(thread: FakeState, error: LuaError) = ResumeResult.Error(error)`).
`SchedulerTests` compiles against the timer-injected ctor (default args keep
call sites unchanged). Expect `Scheduler.close()`'s `binding.closeState`
to now trip TestBinding's slot accounting — if a test closes twice, fix the
test, not the slot (the real ownership fix is Task 6).

- [ ] **Step 5.6: green + commit**

```bash
./mill-launcher.sh scheduler.jvm.test + scheduler.js.test \
  > "$MILL_LOG_DIR/t5-green-$RANDOM.log" 2>&1; echo "exit=$?"
git add -A && git commit -m "feat(scheduler): cross-platform module wiring + TaskTimer seam"
```

---

### Task 6: scheduler rework — suspend transfer, quiescence, ownership

**Files:**
- Modify: `scheduler/src/luau/scheduler/Scheduler.scala`, `Task.scala`,
  `TaskHandle.scala`, `ReadyTask.scala`
- Modify: `scheduler/test/src/luau/scheduler/SchedulerTests.scala`
- Modify: `stdlib/jvm/src/luau/stdlib/TaskLibrary.scala` (task.wait one-shot;
  module still unwired — edit so Task 7 compiles clean)

- [ ] **Step 6.1: failing tests first** (extend `SchedulerTests`)

Once `close()` stops closing the state (Step 6.2 item 6), every existing
SchedulerTests case must end with an explicit `b.closeState(state)` —
`FakeBinding`'s slot guard (Task 1) makes a leaked live state fail the NEXT
test. Sweep TC-01…TC-08 for it while adding these:

```scala
  test("TC-09 takePendingSuspend comes from the Binding, not a private slot"):
    val b = new TestBinding
    val state = b.newState()
    val sched = Scheduler(b, state, wake = () => ())
    b.programResumes(ResumeResult.Yielded(0)) // first resume yields; second (default) returns
    val task = sched.spawn()
    var registerRan = false
    b.setPendingSuspendForTest(task.thread, NativeFnResult.Suspend { r =>
      registerRan = true
      r.succeed(LuaValue.Number(1.0)) // completion fires synchronously: re-queues the task
      Cancel.noop
    })
    sched.runAllReady() // resumes, parks, wires, re-queues, resumes again to completion
    assert(registerRan)
    assertEquals(task.state, TaskState.Complete)
    b.closeState(state)

  test("TC-10 quiescence: empty queue + no pending completions"):
    val b = new TestBinding
    val state = b.newState()
    val sched = Scheduler(b, state, wake = () => ())
    assert(sched.isQuiescent)
    b.programResumes(ResumeResult.Yielded(0))
    val task = sched.spawn()
    assert(!sched.isQuiescent)               // queued
    b.setPendingSuspendForTest(task.thread, NativeFnResult.Suspend(_ => Cancel.noop))
    sched.runAllReady()
    assert(sched.isQuiescent == false)       // parked WITH pending completion
    b.closeState(state)

  test("TC-11 bare-yield park is abandoned: cancelAbandoned reaps it"):
    val b = new TestBinding
    val state = b.newState()
    val sched = Scheduler(b, state, wake = () => ())
    b.programResumes(ResumeResult.Yielded(0)) // yield with NO pending suspend
    val task = sched.spawn()
    sched.runAllReady()
    assertEquals(task.state, TaskState.Parked)
    assert(sched.isQuiescent)                // abandoned ≠ pending
    assertEquals(sched.cancelAbandoned(), 1)
    assertEquals(task.state, TaskState.Cancelled)
    b.closeState(state)

  test("TC-12 close() does NOT close the state (facade owns it)"):
    val b = new TestBinding
    val state = b.newState()
    val sched = Scheduler(b, state, wake = () => ())
    sched.close()
    b.pushNumber(state, 1.0)                 // state still usable
    assertEquals(b.toNumber(state, -1), Some(1.0))
    b.closeState(state)

  test("TC-13 enqueue calls wake"):
    val b = new TestBinding
    val state = b.newState()
    var wakes = 0
    val sched = Scheduler(b, state, wake = () => wakes += 1)
    sched.spawn()
    assert(wakes >= 1)
    b.closeState(state)
```

(TestBinding gains the `setPendingSuspendForTest` hook from Step 5.5.)

- [ ] **Step 6.2: rework `Scheduler`**

Constructor:

```scala
final class Scheduler[H](
  val binding: Binding[H],
  val state: H,
  timer: TaskTimer = TaskTimer.create(),
  wake: () => Unit = () => (),
  val errorPolicy: ErrorPolicy = ErrorPolicy.logAndDiscard,
):
```

Changes, each mechanical:

1. **post + wake.** `private def post(rt: ReadyTask[H]): Unit = {
   runQueue.enqueue(rt); wake() }` — replace every `runQueue.enqueue`.
2. **Delete the private pending-suspend slot** (`pendingSuspend`,
   `setPendingSuspend`, `takePendingSuspend`). The Yielded arm becomes
   `binding.takePendingSuspend(task.thread)` — in BOTH `resumeTask` and
   `spawnImmediate` (the latter currently parks without wiring at all):

```scala
      case ResumeResult.Yielded(_) =>
        binding.takePendingSuspend(task.thread) match
          case Some(NativeFnResult.Suspend(register)) =>
            task.setState(TaskState.Parked)
            wireSuspend(task, register)
          case None =>
            task.setState(TaskState.Parked) // bare yield: abandoned
```

3. **ResumeValues**: drop `Success`, add `Pushed(nargs: Int)`; failures go
   through `resumeError` (raise at the suspension point — script `pcall` can
   observe; the old `(false, msg)` push hid errors from fail-fast):

```scala
enum ResumeValues:
  case None
  case SuspendValue(result: LuaValue)
  case Failure(error: LuaError)
  case Pushed(nargs: Int)
```

```scala
  // resumeTask: replace pushResumeValues + valueCount with:
  val result = rt.values match
    case ResumeValues.Failure(err)    => binding.resumeError(task.thread, err)
    case ResumeValues.None            => binding.resume(task.thread, 0)
    case ResumeValues.Pushed(n)       => binding.resume(task.thread, n)
    case ResumeValues.SuspendValue(v) =>
      pushValue(task.thread, v)
      binding.resume(task.thread, 1)
```

4. **pushValue ref fix** (§1.3): `case r: LuaValue.LuaRef =>
   binding.pushRef(thread, r.ref.registryKey)` replaces the nil degradation.
5. **pendingCompletion** on `Task`:

```scala
  // Task.scala
  @volatile private var _pendingCompletion = false
  def pendingCompletion: Boolean = _pendingCompletion
  private[scheduler] def setPendingCompletion(b: Boolean): Unit = _pendingCompletion = b
```

   `wireSuspend` sets it true on entry, the one-shot `resume` callback sets it
   false before `post`; `scheduleDelayed` sets it true at arm, the timer
   callback sets it false before `post`; `enqueueResume` and `cancelTask` set
   it false.
6. **Quiescence + reaping + teardown**:

```scala
  def isQuiescent: Boolean =
    runQueue.isEmpty && _currentTask.isEmpty &&
      liveTasks.values.forall(t => !t.pendingCompletion)

  /** Cancel bare-yield-parked tasks (no pending completion) — called by the
    * Driver at quiescence so the result always completes (§2.3 item 4). */
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

  def runOneReady(): Boolean =
    runQueue.dequeueOption() match
      case Some(rt) => resumeTask(rt); true
      case None     => false

  def runAllReady(): Int =
    var n = 0
    while runOneReady() do n += 1
    n

  def close(): Unit = cancelAll() // VM ownership: the facade closes the state (Q5)
```

7. **Facade spawn surface** (consumed by Task 8's `TaskWorld`):

```scala
  /** Compile a chunk onto a fresh task thread and queue it (TaskWorld.spawn). */
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

  /** Queue a thread whose function + nargs are already pushed (TaskWorld.spawnFn). */
  def spawnReady(threadRef: Ref[H], thread: H, nargs: Int): TaskHandle[H] =
    val id = idCounter.incrementAndGet()
    val task = Task[H](threadRef, thread, None, id)
    task.setState(TaskState.Queued)
    liveTasks.put(id, task)
    post(ReadyTask(task, ResumeValues.Pushed(nargs)))
    TaskHandle(threadRef, task, this)
```

8. **TaskHandle** gains behavior (Q7: minimal — cancel + isDone):

```scala
final class TaskHandle[H] private[scheduler] (
  private[luau] val threadRef: Ref[H],
  private[luau] val task: Task[H],
  scheduler: Scheduler[H],
):
  def cancel(): Unit = scheduler.cancelTask(task)
  def isDone: Boolean = task.state match
    case TaskState.Complete | TaskState.Cancelled | TaskState.Failed(_) => true
    case _ => false
```

   (`spawnImmediate` / `deferTask` / `scheduleDelayed` ctor calls add `this`.)

- [ ] **Step 6.3: task.wait uses the one-shot resume** (TaskLibrary; still
  unwired, compiles in Task 7)

The current `registerWaitFn` ignores the `resume` the scheduler hands it and
calls `enqueueResume` directly — bypassing `wireSuspend`'s one-shot +
pending accounting. Fix:

```scala
        case Some(currentTask) =>
          Suspend { resume =>
            val t0 = System.nanoTime()
            scheduler.scheduleTimer(seconds) {
              val elapsed = (System.nanoTime() - t0) / 1e9
              resume.succeed(LuaValue.Number(elapsed))
            }
          }
```

- [ ] **Step 6.4: green + commit**

```bash
./mill-launcher.sh scheduler.jvm.test + scheduler.js.test \
  > "$MILL_LOG_DIR/t6-green-$RANDOM.log" 2>&1; echo "exit=$?"
git add -A && git commit -m "feat(scheduler): binding suspend transfer, quiescence, resumeError path, facade spawn surface"
```

---

### Task 7: stdlib module wiring + `TaskResult` + `Pump`

**Files:**
- Move: `stdlib/jvm/src/luau/stdlib/*.scala` → `stdlib/src/luau/stdlib/`
- Move: `stdlib/jvm/test/src/luau/stdlib/StdlibSuite.scala` → `stdlib/test/src/luau/stdlib/`
- Delete: `stdlib/src/luau/stdlib/LuaArgs.scala` IF
  `rg -n 'LuaArgs' --glob '*.scala' .` shows no users outside its own file
- Create: `stdlib/src/luau/api/TaskResult.scala`
- Create: `stdlib/src/luau/api/Pump.scala`
- Create: `stdlib/jvm/src/luau/api/TaskResultPlatform.scala`
- Create: `stdlib/js/src/luau/api/TaskResultPlatform.scala`
- Create: `stdlib/jvm/src/luau/api/PumpPlatform.scala`
- Create: `stdlib/js/src/luau/api/PumpPlatform.scala`
- Modify: `build.mill`
- Test: `stdlib/test/src/luau/api/TaskResultSpec.scala` (new)

- [ ] **Step 7.1: build.mill** — clone the Task-5 scheduler block as
  `object stdlib`, with `moduleDeps = super.moduleDeps ++ Seq(core.jvm,
  scheduler.jvm)` (jvm) / `Seq(core.js, scheduler.js)` (js). Move the source
  trees with `git mv`. Check `StdlibSuite` for test-resource reads
  (`rg -n 'resources|getResource|\.luau' stdlib/test/src`) — the
  `stdlib/jvm/test/resources/luau/*.luau` files belong to plan-07's deferred
  rump; if nothing reads them, leave them in place untouched.

- [ ] **Step 7.2: shared `TaskResult` + `Pump`**

```scala
// stdlib/src/luau/api/TaskResult.scala
package luau.api

import scala.util.Try

/** One-shot async result of withTasks (Q4: not Future — no EC, no
  * cross-thread continuation composition). JVM adds a blocking
  * await(timeout) extension (TaskResultJvm.scala).
  */
trait TaskResult[A]:
  /** Some(result) once the world completed; None while running. */
  def poll: Option[Try[A]]
  /** Register a callback; fires on the Driver thread / event loop, or
    * immediately (caller thread) if already complete. One-shot result —
    * every callback observes the same value. */
  def onComplete(f: Try[A] => Unit): Unit

private[api] trait TaskResultCell[A] extends TaskResult[A]:
  private[api] def complete(r: Try[A]): Unit
```

```scala
// stdlib/src/luau/api/Pump.scala
package luau.api

/** Driver pump (Q5): wake() is safe from any thread and schedules a drain on
  * the driver context. JVM: dedicated virtual thread, parked when idle.
  * JS: synchronous re-entrant trampoline on the event loop (amendment to Q5:
  * a microtask adds nothing over trampolining on a single thread, and the
  * trampoline lets fully-synchronous worlds complete before withTasks
  * returns — which the test suites rely on).
  */
private[api] trait Pump:
  def wake(): Unit
  def shutdown(): Unit

private[api] object Pump:
  def start(drain: () => Unit): Pump = PumpPlatform.start(drain)
```

- [ ] **Step 7.3: platform cells**

```scala
// stdlib/jvm/src/luau/api/TaskResultPlatform.scala
package luau.api

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Try}
import luau.core.LuaError

private[api] final class JvmTaskResultCell[A] extends TaskResultCell[A]:
  private val latch = new CountDownLatch(1)
  @volatile private var result: Option[Try[A]] = None
  private var callbacks: List[Try[A] => Unit] = Nil

  def poll: Option[Try[A]] = result

  def onComplete(f: Try[A] => Unit): Unit =
    val now = synchronized {
      result match
        case some @ Some(_) => some
        case None           => callbacks ::= f; None
    }
    now.foreach(f)

  private[api] def complete(r: Try[A]): Unit =
    val cbs = synchronized {
      if result.isDefined then Nil
      else
        result = Some(r)
        val c = callbacks.reverse
        callbacks = Nil
        c
    }
    cbs.foreach(cb => try cb(r) catch case _: Throwable => ())
    latch.countDown()

  private[api] def awaitImpl(timeout: FiniteDuration): Try[A] =
    if latch.await(timeout.toMillis, TimeUnit.MILLISECONDS) then result.get
    else Failure(LuaError.runtime(s"TaskResult.await timed out after $timeout"))

private[api] object TaskResultPlatform:
  def cell[A](): TaskResultCell[A] = new JvmTaskResultCell[A]
```

```scala
// stdlib/jvm/src/luau/api/TaskResultJvm.scala
package luau.api

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/** JVM-only: block the CALLER thread (never the VM) until completion. */
extension [A](r: TaskResult[A])
  def await(timeout: FiniteDuration): Try[A] = r match
    case c: JvmTaskResultCell[A] => c.awaitImpl(timeout)
```

```scala
// stdlib/js/src/luau/api/TaskResultPlatform.scala
package luau.api

import scala.util.Try

private[api] object TaskResultPlatform:
  def cell[A](): TaskResultCell[A] = new TaskResultCell[A]:
    private var result: Option[Try[A]] = None
    private var callbacks: List[Try[A] => Unit] = Nil
    def poll: Option[Try[A]] = result
    def onComplete(f: Try[A] => Unit): Unit = result match
      case Some(r) => f(r)
      case None    => callbacks ::= f
    private[api] def complete(r: Try[A]): Unit =
      if result.isEmpty then
        result = Some(r)
        val cbs = callbacks.reverse
        callbacks = Nil
        cbs.foreach(cb => try cb(r) catch case _: Throwable => ())
```

- [ ] **Step 7.4: platform pumps**

```scala
// stdlib/jvm/src/luau/api/PumpPlatform.scala
package luau.api

private[api] object PumpPlatform:
  def start(drain: () => Unit): Pump = new Pump:
    private val lock = new Object
    private var signal = false
    @volatile private var alive = true
    Thread.ofVirtual().name("luau-driver").start { () =>
      while alive do
        lock.synchronized { while !signal && alive do lock.wait(); signal = false }
        if alive then drain()
    }
    def wake(): Unit = lock.synchronized { signal = true; lock.notifyAll() }
    def shutdown(): Unit = lock.synchronized { alive = false; lock.notifyAll() }
```

```scala
// stdlib/js/src/luau/api/PumpPlatform.scala
package luau.api

private[api] object PumpPlatform:
  def start(drain: () => Unit): Pump = new Pump:
    private var draining = false
    private var rerun = false
    def wake(): Unit =
      if draining then rerun = true
      else
        draining = true
        try
          drain()
          while rerun do { rerun = false; drain() }
        finally draining = false
    def shutdown(): Unit = ()
```

- [ ] **Step 7.5: cell unit test**

```scala
// stdlib/test/src/luau/api/TaskResultSpec.scala
package luau.api

import munit.FunSuite
import scala.util.{Success, Try}

class TaskResultSpec extends FunSuite:

  test("poll is None until complete; complete is one-shot"):
    val cell = TaskResultPlatform.cell[Int]()
    assertEquals(cell.poll, None)
    cell.complete(Success(1))
    cell.complete(Success(2)) // ignored
    assertEquals(cell.poll, Some(Success(1)))

  test("onComplete fires once, after completion or immediately"):
    val cell = TaskResultPlatform.cell[Int]()
    var seen = List.empty[Try[Int]]
    cell.onComplete(r => seen ::= r)
    cell.complete(Success(7))
    cell.onComplete(r => seen ::= r) // already complete: immediate
    assertEquals(seen, List(Success(7), Success(7)))
```

- [ ] **Step 7.6: green + commit**

```bash
./mill-launcher.sh stdlib.jvm.test + stdlib.js.test \
  > "$MILL_LOG_DIR/t7-green-$RANDOM.log" 2>&1; echo "exit=$?"
git add -A && git commit -m "feat(stdlib): cross-platform wiring, TaskResult cells, driver Pump"
```

---

### Task 8: `withTasks` — `Tasks`, `Driver`, `TaskWorld`, backend entries

**Files:**
- Create: `stdlib/src/luau/api/Tasks.scala`
- Create: `stdlib/src/luau/api/Driver.scala`
- Create: `stdlib/src/luau/api/TaskWorld.scala`
- Modify: `core/src/luau/api/LuaState.scala` (widen `install` to `private[api] def installNative`)
- Modify: `panama/src/luau/panama/PanamaLuau.scala`, `wasm/src/luau/wasm/WasmLuau.scala`
- Modify: `build.mill` (`panama` deps += `stdlib.jvm`; `wasm` deps += `stdlib.js`;
  `panama.test` deps += `stdlib.jvm.test` if the integration suite lands there)
- Test: `panama/test/src/luau/panama/WithTasksSuite.scala`,
  `wasm/test/src/luau/wasm/WasmWithTasksSuite.scala` (new)

- [ ] **Step 8.1: the entry**

```scala
// stdlib/src/luau/api/Tasks.scala
package luau.api

import language.experimental.captureChecking

import scala.concurrent.duration.FiniteDuration
import luau.core.{Binding, LuauLib}
import luau.scheduler.ErrorPolicy

/** The ONLY facade entry (Q4): every chunk is a coroutine (ADR-0001), so the
  * world is always a task world. Two-phase, both lambdas synchronous on the
  * Driver thread; async-ness lives in the TaskResult.
  */
object Tasks:

  def withTasks[H, A](
    binding:     Binding[H],
    libs:        Set[LuauLib] = LuauLib.Standard,
    deadline:    Option[FiniteDuration] = None,
    errorPolicy: ErrorPolicy = ErrorPolicy.failFast,
  )(setup: TaskWorld[H]^ => Unit)(finish: LuaState[H]^ => A): TaskResult[A] =
    binding.reserveStateSlot() // second entry fails HERE, synchronously (§0)
    val result = TaskResultPlatform.cell[A]()
    new Driver[H, A](binding, libs, deadline, errorPolicy, setup, finish, result).start()
    result
```

- [ ] **Step 8.2: the Driver**

```scala
// stdlib/src/luau/api/Driver.scala
package luau.api

// NOTE: deliberately NOT capture-checked — the driver stores the user
// lambdas across threads by design; the cc guarantees live in Tasks.withTasks'
// signature and in LuaState/TaskWorld themselves. If a cc import sneaks in
// here, the field captures below become errors.

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Try}
import luau.core.{Binding, Cancel, LuaError, LuauLib}
import luau.scheduler.{ErrorPolicy, Scheduler, TaskTimer}
import luau.stdlib.StdlibOpener

private[api] final class Driver[H, A](
  binding:     Binding[H],
  libs:        Set[LuauLib],
  deadline:    Option[FiniteDuration],
  errorPolicy: ErrorPolicy,
  setup:       TaskWorld[H] => Unit,
  finish:      LuaState[H] => A,
  result:      TaskResultCell[A],
):
  private val timer = TaskTimer.create()
  private val pump  = Pump.start(() => drain())

  @volatile private var deadlineHit = false
  @volatile private var firstError: Option[LuaError] = None

  // Driver-thread-only state
  private var stateOpt: Option[H] = None
  private var scheduler: Scheduler[H] = null
  private var done = false
  private var deadlineCancel: Cancel = Cancel.noop

  def start(): Unit =
    deadline.foreach { d =>
      deadlineCancel = timer.schedule(d.toMillis / 1000.0) { () =>
        deadlineHit = true
        pump.wake()
      }
    }
    pump.wake()

  private def drain(): Unit =
    if done then return
    if stateOpt.isEmpty then
      try init()
      catch case t: Throwable => finishWith(Failure(t)); return
    var more = true
    while more && !done do
      if deadlineHit then
        failWorld(LuaError.runtime("withTasks: deadline exceeded"))
      else if firstError.isDefined then failWorld(firstError.get)
      else more = scheduler.runOneReady()
    if !done && firstError.isDefined then failWorld(firstError.get)
    if !done && deadlineHit then failWorld(LuaError.runtime("withTasks: deadline exceeded"))
    if !done && scheduler.isQuiescent then
      scheduler.cancelAbandoned() // bare-yield parks: cancelled so the result always completes
      finishWith(Try(finish(LuaState(binding, stateOpt.get))))

  private def init(): Unit =
    val state = binding.newState() // fills the caller's reservation
    stateOpt = Some(state)
    val policy: ErrorPolicy =
      if errorPolicy eq ErrorPolicy.failFast then
        (task, err) => { if firstError.isEmpty then firstError = Some(err) }
      else errorPolicy
    scheduler = Scheduler(binding, state, timer, () => pump.wake(), policy)
    StdlibOpener.open(binding, state, scheduler, libs) // openLibs + task.* + sandbox (Q7)
    setup(TaskWorld(LuaState(binding, state), scheduler))

  private def failWorld(err: LuaError): Unit =
    scheduler.cancelAll() // fires Cancel hooks; finish NEVER runs on a partial world (Q6)
    finishWith(Failure(err))

  private def finishWith(r: Try[A]): Unit =
    done = true
    deadlineCancel.cancel()
    if scheduler != null then scheduler.close()
    stateOpt match
      case Some(s) => binding.closeState(s)
      case None    => binding.releaseStateSlot()
    timer.shutdown()
    result.complete(r)
    pump.shutdown()
```

- [ ] **Step 8.3: `TaskWorld`**

```scala
// stdlib/src/luau/api/TaskWorld.scala
package luau.api

import language.experimental.captureChecking

import scala.util.{Failure, Success, Try}
import luau.core.*
import luau.core.codec.*
import luau.scheduler.{Scheduler, TaskHandle}

/** Phase-1 surface of withTasks (§2.3 item 4): the LuaState surface plus
  * spawn / spawnFn / defineAsync. Lives only inside setup (capability).
  */
final class TaskWorld[H] private[api] (
  private[api] val st: LuaState[H]^,
  private[api] val scheduler: Scheduler[H],
):
  export st.{
    eval, eval0, eval1, eval2, eval3, eval4, run,
    get, set, getFn, getTbl, defineGlobal, useRef, evalFn, coro,
  }
  // If the export trips capture checking on `st`, fall back to
  //   def state: LuaState[H]^{this} = st
  // and let setup call w.state.eval(...) — the spawn surface below is the
  // load-bearing part.

  /** Compile a chunk as a Task: queued now, runs when the Driver drains. */
  def spawn(source: String, chunkname: String = "=task"): Try[TaskHandle[H]] =
    scheduler.spawnChunk(source, chunkname).fold(Failure(_), Success(_))

  /** Queue a pinned function as a Task. The function VALUE is pushed onto the
    * task's thread immediately, so the task keeps it alive even after the
    * minting RefScope unpins it (the scheduler owns its pins, §2.3 item 4).
    */
  def spawnFn(fn: LuaFn[H]^, args: LuaArg*): TaskHandle[H] =
    val thread = scheduler.binding.newThread(scheduler.state)
    val threadRef = scheduler.binding.ref(scheduler.state)
    scheduler.binding.pushRef(thread, fn.ref.registryKey)
    args.foreach(a => a.pushTo[H](scheduler.binding, thread))
    scheduler.spawnReady(threadRef, thread, args.size)

  /** Host async seam: Lua calls `name(arg)`, the task parks; `start` returns
    * the register function whose Resume (fired from ANY thread) enqueues the
    * task back (ADR-0007: never resumes inline).
    */
  def defineAsync[Arg: LuauDecoder](name: String)(start: Arg => (Resume => Cancel)): Unit =
    st.installNative(name, (thread, nargs) =>
      scheduler.binding.decodeAt[Arg](thread, 1) match
        case Left(e)  =>
          scheduler.binding.pushString(thread, e.message)
          NativeFnResult.Fail
        case Right(a) => NativeFnResult.Suspend(start(a)))
```

In `LuaState.scala`, rename the private `install` to
`private[api] def installNative(name: String, fn: NativeFn[H]): Unit`
(same body; `defineGlobal`'s five call sites update).

- [ ] **Step 8.4: backend entries**

```scala
// panama/src/luau/panama/PanamaLuau.scala — add (withState still present until Task 10)
  def withTasks[A](
    libs:     Set[LuauLib] = LuauLib.Standard,
    deadline: Option[FiniteDuration] = None,
  )(setup: TaskWorld[MemorySegment]^ => Unit)(
    finish: LuaState[MemorySegment]^ => A
  ): TaskResult[A] =
    Tasks.withTasks(PanamaBinding.instance, libs, deadline)(setup)(finish)
```

```scala
// wasm/src/luau/wasm/WasmLuau.scala — add
  def withTasks[A](
    libs:     Set[LuauLib] = LuauLib.Standard,
    deadline: Option[FiniteDuration] = None,
  )(setup: TaskWorld[Int]^ => Unit)(finish: LuaState[Int]^ => A): TaskResult[A] =
    Tasks.withTasks(WasmBackend.createBinding(), libs, deadline)(setup)(finish)
```

build.mill: `panama.moduleDeps = super.moduleDeps ++ Seq(core.jvm, stdlib.jvm)`;
`wasm.moduleDeps = super.moduleDeps ++ Seq(core.js, stdlib.js)`.

- [ ] **Step 8.5: integration tests — plan 06's ITC-01, finally runnable**

```scala
// panama/test/src/luau/panama/WithTasksSuite.scala
package luau.panama

import munit.FunSuite
import scala.concurrent.duration.*
import scala.util.Success
import luau.api.*
import luau.core.{Cancel, LuaValue}

class WithTasksSuite extends FunSuite:

  test("WT-01 sync world: setup spawns nothing, finish reads"):
    val r = PanamaLuau.withTasks() { w => w.set("x", 21.0) } { st =>
      st.eval[Double]("return x * 2").get
    }
    assertEquals(r.await(10.seconds), Success(42.0))

  test("WT-02 ITC-01: Suspend parks; off-Driver completion resumes"):
    val r = PanamaLuau.withTasks() { w =>
      w.defineAsync[Double]("fetchAsync") { n => resume =>
        val t = new Thread(() => { Thread.sleep(50); resume.succeed(LuaValue.Number(n * 2)) })
        t.start()
        Cancel.noop
      }
      w.spawn("answer = fetchAsync(21)").get
    } { st => st.get[Double]("answer").get }
    assertEquals(r.await(10.seconds), Success(42.0))

  test("WT-03 task.wait round-trips through the timer seam"):
    val r = PanamaLuau.withTasks() { w =>
      w.spawn("local t = task.wait(0.05); waited = (t >= 0.04)").get
    } { st => st.get[Boolean]("waited").get }
    assertEquals(r.await(10.seconds), Success(true))

  test("WT-04 fail-fast: task error cancels the world, finish never runs"):
    var finishRan = false
    val r = PanamaLuau.withTasks() { w =>
      w.spawn("error('boom')").get
    } { _ => finishRan = true }
    assert(r.await(10.seconds).isFailure)
    assert(!finishRan)

  test("WT-05 deadline fails the result when a completion never arrives"):
    val r = PanamaLuau.withTasks(deadline = Some(200.millis)) { w =>
      w.defineAsync[Double]("never") { _ => _ => Cancel.noop }
      w.spawn("never(0)").get
    } { _ => () }
    val failure = r.await(10.seconds)
    assert(failure.isFailure)
    assert(failure.failed.get.getMessage.contains("deadline"))

  test("WT-06 second entry while live throws IllegalStateException"):
    val r = PanamaLuau.withTasks() { _ => () } { st =>
      intercept[IllegalStateException] {
        PanamaLuau.withTasks() { _ => () } { _ => () }
      }
      true
    }
    assertEquals(r.await(10.seconds), Success(true))

  test("WT-07 bare yield parks forever → cancelled at quiescence, result completes"):
    val r = PanamaLuau.withTasks() { w =>
      w.spawn("coroutine.yield(); unreached = true").get
    } { st => st.get[Boolean]("unreached").isFailure } // global never set
    assertEquals(r.await(10.seconds), Success(true))
```

The wasm twin (`WasmWithTasksSuite`) mirrors WT-01/03/04/06/07 with
`WasmLuau.withTasks` + `r.poll.getOrElse(fail("not complete")).get`-style
asserts (the JS trampoline completes sync worlds inline) and replaces WT-02's
`Thread` with an async munit test:

```scala
  test("WT-02-js Suspend parks; event-loop completion resumes"):
    import scala.concurrent.Promise
    import scala.scalajs.js.timers
    val p = Promise[Double]()
    WasmLuau.withTasks() { w =>
      w.defineAsync[Double]("fetchAsync") { n => resume =>
        timers.setTimeout(10) { resume.succeed(LuaValue.Number(n * 2)) }
        Cancel.noop
      }
      w.spawn("answer = fetchAsync(21)").get
    } { st => st.get[Double]("answer").get }
      .onComplete(p.complete)
    p.future.map(v => assertEquals(v, 42.0))
```

- [ ] **Step 8.6: green + commit**

```bash
./mill-launcher.sh panama.test + wasm.test + stdlib.jvm.test + stdlib.js.test \
  > "$MILL_LOG_DIR/t8-green-$RANDOM.log" 2>&1; echo "exit=$?"
git add -A && git commit -m "feat(api): withTasks(setup)(finish): TaskResult — Driver, TaskWorld, task.* auto-install"
```

---

### Task 9: one yield regime — the MaxResumes pump dies

**Files:**
- Modify: `core/src/luau/api/LuaState.scala`
- Modify: `core/test/src/luau/api/ApiSuite.scala` (tests that relied on the pump)

- [ ] **Step 9.1: find pump-dependent tests**

`rg -n 'yield' core/test/src/luau/api/ApiSuite.scala` — any test whose chunk
top-level-yields and still expects success relies on the auto-pump. Each
becomes either a `coro()`-stepped test or an assert-fails test (next step's
semantics).

- [ ] **Step 9.2: replace `runChunk`**

```scala
  /** Compile onto `thread`, resume ONCE. A chunk that parks (yields to the
    * host) is an error here — spawn it as a task (plan 10 §2.3 item 5).
    * Pure compute completes on the first resume, identical to before.
    */
  private def runChunk(thread: H, source: String, chunkname: String): Either[LuaError, Int] =
    binding.compileAndLoad(thread, source, chunkname) match
      case Left(e) => Left(e)
      case Right(()) =>
        binding.resume(thread, 0) match
          case ResumeResult.Returned(n) => Right(n)
          case ResumeResult.Error(e)    => Left(e)
          case ResumeResult.Yielded(_) =>
            binding.takePendingSuspend(thread) // a host fn may have armed a token; consume so it can't leak
            Left(LuaError.runtime(s"$chunkname suspended — spawn it as a task"))
```

Delete `object LuaState`'s `MaxResumes` and the `import LuaState.MaxResumes`.
(`coro()` / `LuaCoro.resume*` are untouched — manual host-stepped coroutines
on the Driver thread stay scheduler-invisible per ADR-0004.)

- [ ] **Step 9.3: pin the new semantics in ApiSuite**

```scala
  test("TC-API-37 eval on a parking chunk fails with the spawn hint"):
    withLuau() { st =>
      val r = st.eval[Double]("coroutine.yield(); return 1")
      assert(r.isFailure)
      assert(r.failed.get.getMessage.contains("spawn it as a task"))
    }
```

- [ ] **Step 9.4: green + commit**

```bash
./mill-launcher.sh core.jvm.test + core.js.test + panama.test + wasm.test \
  > "$MILL_LOG_DIR/t9-green-$RANDOM.log" 2>&1; echo "exit=$?"
git add -A && git commit -m "feat(api)!: one yield regime — eval/call fail on park, MaxResumes pump removed"
```

---

### Task 10: `withState` deleted; suites migrate to `withTasks`

**Files:**
- Delete: `core/src/luau/api/Luau.scala`
- Modify: `core/test/src/luau/api/ApiSuite.scala` (abstract `withLuau`)
- Modify: `panama/test/src/luau/panama/PanamaApiSuite.scala`,
  `wasm/test/src/luau/wasm/WasmApiSuite.scala`
- Modify: `panama/src/luau/panama/PanamaLuau.scala`,
  `wasm/src/luau/wasm/WasmLuau.scala` (drop `withState`)
- Modify: `core/test/src/luau/api/CcCompileSpec.scala` (recorded snippets)
- Modify: `build.mill` (`panama.test` deps += `stdlib.jvm.test`;
  `wasm.test` deps += `stdlib.js.test` — only if not already added in Task 8)

- [ ] **Step 10.1: ApiSuite goes entry-agnostic**

`ApiSuite` keeps compiling against core only — the entry becomes the
platform subclass's job:

```scala
abstract class ApiSuite[H] extends FunSuite:

  /** Platform hook: run f against a live state. Implementations route
    * through withTasks (plan 10 — withState is gone); f runs in the finish
    * phase, where sync eval/get/set are legal and the state is alive.
    */
  protected def withLuau[A](libs: Set[LuauLib] = LuauLib.Standard)(f: LuaState[H] => A): A
```

Delete `def withBinding` from `ApiSuite` (it was only consumed by the old
`withLuau`). Suites that need a raw binding (`SharedBackendSuite`) have their
own hooks and are unaffected.

- [ ] **Step 10.2: platform impls**

```scala
// panama/test/src/luau/panama/PanamaApiSuite.scala
class PanamaApiSuite extends ApiSuite[MemorySegment]:
  override protected def withLuau[A](libs: Set[LuauLib])(f: LuaState[MemorySegment] => A): A =
    PanamaLuau.withTasks(libs)(_ => ())(f).await(30.seconds).get
```

```scala
// wasm/test/src/luau/wasm/WasmApiSuite.scala
class WasmApiSuite extends ApiSuite[Int]:
  override protected def withLuau[A](libs: Set[LuauLib])(f: LuaState[Int] => A): A =
    WasmLuau.withTasks(libs)(_ => ())(f)
      .poll
      .getOrElse(throw new AssertionError("withTasks did not complete synchronously"))
      .get
```

- [ ] **Step 10.3: delete the old entries**

- Delete `core/src/luau/api/Luau.scala`.
- `PanamaLuau`: delete `withState`; `withTasks` remains the only member.
- `WasmLuau`: same.
- Sweep: `rg -n 'withState' --glob '*.scala' .` must return zero hits
  (comments included — update the `CcCompileSpec`/`ApiSuite` doc-comments'
  recorded snippets from `Luau.withState(b)(st => st)` to
  `Tasks.withTasks(b)(w => ())(st => st)` and re-verify the cc rejection
  manually, recording the new compiler message in the comment).

- [ ] **Step 10.4: full green + commit**

```bash
./mill-launcher.sh core.jvm.test + core.js.test + panama.test + wasm.test + \
  scheduler.jvm.test + scheduler.js.test + stdlib.jvm.test + stdlib.js.test \
  > "$MILL_LOG_DIR/t10-green-$RANDOM.log" 2>&1; echo "exit=$?"
git add -A && git commit -m "feat(api)!: delete withState — withTasks is the only entry; TC-API suite migrated"
```

---

### Task 11: acceptance sweep (§2.4) + plan close-out

**Files:**
- Modify: `panama/test/src/luau/panama/WithTasksSuite.scala`,
  `wasm/test/src/luau/wasm/WasmWithTasksSuite.scala`,
  `scheduler/test/src/luau/scheduler/SchedulerTests.scala`
- Modify: `docs/plans/10-backend-lifecycle.md` (status flip)

- [ ] **Step 11.1: tick §2.4 line-by-line** — each bullet must map to a
  passing test; add the missing ones:

| §2.4 item | Test |
|---|---|
| two sequential `withTasks` on one runtime | add WT-08: run WT-01's body twice in one test |
| second entry throws | WT-06 ✓ |
| bookkeeping empty after N states | LC-01 (panama) ✓ / LC-W-01 (wasm) ✓ — re-assert AFTER a withTasks that uses defineAsync + defineGlobal |
| one `lx_newstate` per entry | LC-02 ✓ extended: assert delta == 1 across one full `withTasks().await` |
| Suspend → park → off-Driver enqueue → resume | WT-02 / WT-02-js ✓ |
| cancel fires on close | add WT-09: defineAsync stores its Cancel into a `@volatile var fired`; deadline-fail the world; assert fired |
| double-resume no-op | scheduler TC-03 ✓ (binding-level) — add WT-10: defineAsync calls `resume.succeed` twice; world completes once, no crash |
| bare yield parks forever | WT-07 ✓ + scheduler TC-11 ✓ |
| `task.wait` through the timer seam, both platforms | WT-03 (jvm) ✓ + the wasm twin ✓ |

- [ ] **Step 11.2: full-matrix run**

```bash
./mill-launcher.sh core.jvm.test + core.js.test + panama.test + wasm.test + \
  scheduler.jvm.test + scheduler.js.test + stdlib.jvm.test + stdlib.js.test \
  > "$MILL_LOG_DIR/t11-final-$RANDOM.log" 2>&1; echo "exit=$?"
rg -n 'tests, .* failed|tasks failed' "$MILL_LOG_DIR"/t11-final-*.log
```

- [ ] **Step 11.3: close the plan**

Status line of this doc → `**Status:** Implemented (date) — …`, mirroring
plan 09's close-out; note the wasm wall-clock before/after from Task 4 and
any §5 deviations taken during execution. Commit:

```bash
git add -A && git commit -m "test: plan 10 acceptance sweep; docs(plan10): status -> implemented"
```

---

### §5 design notes locked during plan-writing

These are small decisions the writing plan had to make beyond §2; recorded
here so the executor doesn't re-derive them:

1. **Entry object is `luau.api.Tasks` in the `stdlib` module** (files in the
   stdlib module declare `package luau.api`, so `LuaState`'s `private[api]`
   members stay reachable). Core's `object Luau` dies with `withState`;
   users face `PanamaLuau.withTasks` / `WasmLuau.withTasks`.
2. **Failure resumes raise, not return.** `ResumeValues.Failure` goes through
   the new `Binding.resumeError` (shim `lx_resume_error`) so a failed Suspend
   raises at the yield point — observable by script `pcall`, and an unhandled
   raise becomes a task error feeding fail-fast. The old `(false, msg)` push
   convention (and `ResumeValues.Success`) is deleted.
3. **JS pump is a synchronous re-entrant trampoline**, not a microtask
   (amendment to Q5): on one thread a microtask buys nothing, and the
   trampoline lets fully-synchronous worlds complete before `withTasks`
   returns — the migrated ApiSuite depends on this (`poll.get`).
4. **Slot guard lives at `newState()` (runtime level) AND at the entry**
   (`reserveStateSlot()` on the caller thread) — the entry throw is
   synchronous; the runtime throw catches low-level misuse. Tests that held
   two live states (`TC-WASM-04`) are rewritten sequential.
5. **`task.wait` uses the scheduler-provided one-shot `resume`** instead of
   calling `enqueueResume` directly — the old path bypassed `wireSuspend`'s
   one-shot guard and the pending-completion accounting.
6. **`withTasks` exposes `errorPolicy: ErrorPolicy = ErrorPolicy.failFast`**
   as the Q6 opt-in seam; `failFast` is a sentinel the Driver pattern-matches
   (its body never runs).
7. **`spawnImmediate` wires suspends too** — the on-disk code parked without
   checking the pending suspend, so a host fn suspending during `task.spawn`'s
   immediate burst lost its completion.
