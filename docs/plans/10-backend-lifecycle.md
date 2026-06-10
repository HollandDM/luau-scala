# Plan 10 — Backend Lifecycle: one runtime, cheap states

**Status:** Draft for discussion — nothing here is implemented.
**Depends on:** the shipped `luau.api` facade; plan 09 is independent.

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

The back-reference is also a latent correctness bug: suspend tokens from VMs
minted via `newState()` all land in the *binding's* registry keyed by nothing
VM-specific — concurrent suspends on two VMs of one binding would collide.

### 1.2 wasm: full module reload per state

`WasmBackend.load()` instantiates a complete wasm module (MBs, plus
`Trampoline.reset()`) and the test suites call it **per test**, because of
this admitted papering-over (WasmBackendSuite):

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

## 2. Proposal

### 2.1 Panama: split `PanamaRuntime` from VM identity

```scala
/** Process-level: arena, dispatcher, upcall stub. Owns NO VM. */
final class PanamaRuntime private (...):
  def newBinding(): PanamaBinding   // or the runtime IS the Binding — see Q1

/** Binding[MemorySegment]: mints/closes VMs via the runtime's stub. */
```

- Per-VM dispatcher state moves out of the singleton: `suspendRegistry` and
  `lastYieldToken` become keyed by state address (a `ConcurrentHashMap
  [Long, VmData]` populated on `newState`, removed on `closeState`), killing
  the `dispatcher.init(ps)` back-reference and the token-collision bug in the
  same move.
- `PanamaLuau.withState` becomes: lazy process-wide runtime → `newState()` →
  one VM total per call.
- `PanamaState.use` and the anchor-VM constructor survive only as a
  deprecated shim over the new shape until the test suites migrate (they use
  `ps.L` heavily).

### 2.2 wasm: root-cause, then one module instance for N states

Phase 1 — **reproduce and bisect** (no fix yet):
single `load()`, then sequentially `withState` twice; then two interleaved
states. Capture which op aborts and on which state. Each suspect above gives
a distinguishable signature (stale-fnId crash fires inside an upcall;
LxStateData use-after-free fires in `lx_*` on the second state; heap-view
staleness fires only after memory growth).

Phase 2 — fix accordingly. Expected shape regardless of which suspect wins:

- `WasmBinding.closeState` unregisters that state's fnIds (requires tracking
  fnId→state at registration — `Trampoline.register(state, fn)`).
- `Trampoline` stops being a process-global mutable singleton: its table is
  keyed per wasm instance, owned by `WasmModule`.
- Tests drop reload-per-test; `load()` happens once per suite.

Phase 3 — measure: wasm suite wall-clock before/after (today every test pays
module instantiation).

### 2.3 Shared acceptance

New `ApiSuite` cases (both backends): two sequential `withState` on one
runtime; two interleaved states alive at once, each runs host fns + handles;
state A closed while state B keeps working. Panama-only: assert exactly one
`lx_newstate` per `withState` (countable via a test dispatcher hook).

## 3. Non-goals

- Multi-threaded access to one VM (ADR-0002 territory, still deferred).
- Wasm multi-module concurrency (two instances side by side) — keep
  supported-by-accident at best.
- Pooling/reusing VMs across `withState` calls.

## 4. Open questions (grill here)

1. **Runtime/Binding shape (panama):** is `Binding[H]` the runtime itself
   (one binding, many VMs — what `newState()` already implies), or does a
   runtime mint one binding per VM? Current trait reads as the former; the
   facade assumes the former. Confirming this kills the `PanamaState.use`
   anchor pattern permanently.
2. **Process-wide runtime singleton vs per-`withState` runtime?** Singleton
   = cheap calls, but upcall stub + arena live forever; per-call = clean but
   re-pays stub allocation. Proposal: lazy singleton, `close()` never (JVM
   exit cleans up). Objections?
3. **wasm `LuauShimFactory` per `load()`** — after the fix, do we keep the
   ability to reload at all (useful for leak isolation in tests), or hard-pin
   one instance per process?
4. How much of `PanamaState`'s public surface (`ps.L`, direct ops) is worth
   keeping once the facade + runtime exist? Candidate: demote to
   `private[panama]` test utility.
