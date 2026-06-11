# Plan 10 — Backend Lifecycle + Scheduler/Driver

**Status:** Grilled (2026-06-11) — all open questions resolved (§4); nothing
implemented yet.
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
