# Ported-test gaps: what we could not port, why, and what to implement

Status: research note (2026-06-11); **Tier 1 implemented 2026-06-12** — see
the strikethrough notes per entry. Source: the Lune/Zune/Lute/upstream-conformance
porting pass (`stdlib/test/resources/ported/`, `PortedTaskSuiteBase`,
`ConformanceManifest`). Each entry names the capability gap, what it blocked,
and what an implementation needs. Ranked by impact × feasibility.

The wasm shim rebuilds from source via cached Mill tasks (`shim.wasiSysroot`
→ wasm object tasks → `shim.wasmBuildNative`, build.mill — wasi-sdk-31
sysroot with wasm exceptions, system clang, no emcc). New `lx_*` exports are
therefore unblocked: add to `lx.h`/`lx.cpp`, the `shim.wasmExports` list in
build.mill, and `LxHandles`/`WasmModule` — only the shim object and the link
rerun.

## Tier 1 — should implement — **DONE (2026-06-12)**

All five entries below are implemented and their dropped tests restored:
`lx_to_thread`/`lx_reset_thread` shim exports + `Binding.toThreadAt`/
`resetThread`/`sameThread`; adopted-thread spawn variants
(`Scheduler.spawnImmediateAdopted`/`deferAdopted`/`delayAdopted`) +
thread-arg `task.spawn`/`defer`/`delay`/`cancel`; `cancelTask` resets the
coroutine to dead; `warn` registered by `StdlibOpener`; the adapted
`pcall.luau` runs as `ported/conformance/pcall.luau`; `TaskHandle.results`
replaced the `__result` sentinel protocol in all ported files.

### 1. Thread-handle extraction: `lx_to_thread(thread, idx) -> lx_Thread`
- Blocked: zune `describe("thread")` block (6 sub-tests: task.spawn/defer/delay
  with `coroutine.create` threads, thread identity, cancel-on-raw-thread);
  lune spawn.luau thread variant. Markers: `-- dropped:` in
  `stdlib/test/resources/ported/{zune/task.luau,lune/spawn.luau}`.
- Root cause: TaskLibrary cannot turn a stack thread VALUE into an `H` to
  resume, so `task.*` is function-only. Roblox accepts `fn | thread`.
- Implementation: shim `lua_tothread` wrapper; `Binding.toThreadAt(state, idx):
  Option[H]` across panama/wasm/fake/test bindings; TaskLibrary accepts
  `LuaType.Thread` first arg; scheduler adopts pre-created coroutines
  (allocTask over an existing thread + ref).
- Bonus: retires the Lua-table identity probe in `Scheduler.cancelThread`
  (direct handle comparison instead).

### 2. Coroutine reset on cancel: `lx_reset_thread` (`lua_resetthread`)
- Blocked: 3 zune asserts (`coroutine.status == "dead"` after `task.cancel`),
  dropped with markers in `ported/zune/task.luau`.
- Root cause: `cancelTask` deschedules but leaves the coroutine `suspended`.
  Roblox/Zune reset it to dead. Beyond tests: cancelled-thread stacks stay
  live until state close (GC pressure) and `coroutine.status` misreports
  cancellation to user code.
- Implementation: shim export + `Binding.resetThread(thread)`;
  `Scheduler.cancelTask` resets after fireCancel/releaseThread. Same shim
  batch as #1.

### 3. `warn` global
- Blocked: lune `globals/warn.luau` (not ported at all).
- Implementation: pure Scala, no shim — StdlibOpener registers `warn`
  (print-to-stderr semantics). Roblox/Lune both ship it; ported ecosystem
  scripts call it.

### 4. Adapted `pcall.luau` conformance port
- Blocked: upstream `pcall.luau` (317 lines, pcall/xpcall semantics) excluded
  in `ConformanceManifest` for `cxxthrow` + `resumeerror` globals + OOM
  allocator sections.
- Plan: adapted copy in `ported/` tree. `cxxthrow` shims as pure Lua
  (`function() error("oops") end`). `resumeerror(co, msg)` needs #1 (native fn
  receives the coroutine as a stack value, must extract `H` to call
  `binding.resumeError`). Drop OOM/limitedstack sections with markers.
- Value: core error-propagation semantics currently have zero upstream
  coverage; our scheduler leans on `resumeError` heavily.

### 5. Task return values on `TaskHandle`
- Friction, not a dropped test: chunk return values are unobservable through
  `spawn`, which forced the `__result = "OK"` sentinel protocol into all 10
  ported files (`PortedTaskSuiteBase` doc explains it).
- Implementation: pure Scala — `handleResumeResult` captures `Returned(n)`
  values into refs/LuaValues on the Task; `TaskHandle.results` exposes them.
  Kills the sentinel hack; API users get task results.

## Tier 2 — later, when the need is real

### 6. `task.wait` inside raw (non-scheduler) coroutines — ADR-0004
- Blocked: a lune `globals/coroutine.luau` section (dropped with marker);
  `stdlib/jvm/test/resources/luau/task-raw-coroutine-wrinkle.luau` documents
  the same wrinkle.
- Hard: with Lua-side `coroutine.resume` in the loop, the yield + suspend
  token land in the Lua caller, not the scheduler's `binding.resume` —
  fixing it means dispatcher-level interception. Accepted divergence until
  user code hits it.

### 7. Fast-flag control: `lx_set_fflag(name, value)`
- Blocked: conformance `classes.luau`, `integers.luau` (and part of
  `types.luau`).
- Cheap shim export (`Luau::FValue` registry walk). Matters increasingly:
  Luau ships language features behind FFlags; without it the conformance set
  ages.

### 8. Adapted `gc.luau` conformance port — **DONE (2026-06-12)**
- Blocked: excluded only for `setblockallocations` (custom allocator hook).
- Plan: adapted copy minus allocator sections — most of its 407 lines are
  plain GC behavior. No new exports needed.
- Done differently: the UNMODIFIED upstream file now runs in the conformance
  suite (it needs lx_conformance_setup's collectgarbage fixture, so the
  ported/ tree was the wrong home) with a no-op `setblockallocations`
  stand-in in `ConformanceManifest.luaPrelude`. The OOM shrink sections
  still run their shrink paths, just without allocation-failure injection.
  Full fidelity (blockable allocator + `lx_set_block_allocations` export)
  remains a follow-up.

### 9. `require` / module system
- Blocked: nothing critical today (lune `fcheck` was inlined), but every
  multi-file test corpus assumes it. Product-level plan, not a test fix.

## Tier 3 — won't implement (by design)

- Native codegen asserts (`native.luau`, `native_types.luau`,
  `integers_regspill.luau`): interpreter-only embedding; wasm cannot codegen.
- C++ userdata fixtures (`userdata.luau`, `udata_direct.luau`,
  `native_userdata.luau`): pointless until the binding exposes a userdata API
  at all; revisit with that feature.
- Debugger/coverage hooks (`debugger.luau`, `coverage.luau`), RTTI
  (`types.luau` needs Luau::Frontend), C continuation helpers
  (`cyield.luau`): heavy infrastructure for paths we do not expose.
- Host-runtime libs (fs/net/process/stdio/datetime/serde — killed most of
  Lune's corpus and all of Lute's): the embedding contract says the host
  supplies these via `defineGlobal`/`defineAsync`.
- Nonstandard task extensions (zune `task.count`, lute
  `task.resume`/`task.deferSelf`): off-spec; `Scheduler.liveTasks` already
  provides introspection Scala-side.
- Runtime-identity asserts (`_VERSION`, `_G` emptiness, oversized-delay
  clamp observability): deliberate divergences.

## Suggested order

1. Rebuild sysroot + wasm (done if this note is committed — see the
   `shim.wasiSysroot` Mill task in build.mill).
2. Shim batch: `lx_to_thread` + `lx_reset_thread` (+ `lx_set_fflag` if cheap
   in the same pass) → #1, #2, then #4.
3. Pure-Scala pair anytime: #3 (`warn`), #5 (TaskHandle results — then strip
   the sentinel protocol from ported files).
