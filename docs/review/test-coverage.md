# Test Coverage Report

**Date:** 2026-06-10  
**Scope:** All test modules in `luau-scala` — `core.jvm`, `core.js`, `panama`, `wasm`, `scheduler.jvm`, `stdlib.jvm`  
**Purpose:** Inventory every test suite and what it covers; identify the JS-vs-JVM parity matrix; enumerate untested ABI functions and error paths; list missing property and stress tests; and provide a concrete prioritized backlog of tests to add.

---

## 1. Test Suite Inventory

### 1.1 `core.jvm` — three suites, all run today without ignored tests

**Location:** `core/jvm/test/src/luau/core/`

#### `NativeFnResultSpec`

File: `core/jvm/test/src/luau/core/NativeFnResultSpec.scala`

Five tests, all active (no `.ignore`):

| Test | What it covers |
|------|----------------|
| `NativeFnResult.Return holds nResults` | Value constructor for `Return(n)` |
| `NativeFnResult.Fail holds value` | Value constructor for `Fail(v)` |
| `NativeFnResult.Suspend register is called` | `Suspend` lambda is invoked; `Resume.succeed` populates `Right`; `Cancel` fires callback |
| `Resume.succeed produces Right(value)` | `Resume` opaque extension method |
| `Resume.fail produces Left(LuaError)` | `Resume.fail` extension method |

Coverage note: these tests exercise `Async.scala` and `NativeFnResult.scala` entirely via pure Scala values — no `Binding` call is made. The `Cancel.noop` constant is not exercised directly.

#### `RefScopeSpec`

File: `core/jvm/test/src/luau/core/RefScopeSpec.scala`

Six tests, all active:

| Test | What it covers |
|------|----------------|
| `Ref.close releases registry slot` | `Ref.close()` removes entry from `FakeState.registry` |
| `Ref.close is idempotent` | Double `close()` does not raise |
| `Ref.push restores value to stack` | `Ref.push()` → `FakeBinding.pushRef` → stack grows by 1 |
| `Scope closes all owned Refs in LIFO order` | `Scope.captureTop()` twice, `close()` closes both; LIFO not explicitly verified by assertion order, but closure completeness is checked |
| `Ref.close after state close is no-op` | `FakeBinding.closeState` marks `isClosed`; subsequent `unref` is a no-op per `FakeBinding.unref:128` |
| `Using.resource closes Ref on exit` | `AutoCloseable` integration |

Coverage note: `Ref.push()` on a closed `Ref` (should throw `require` failure at `Ref.scala:13`) is not tested. `Scope.own()` method is not tested — only `captureTop()` is exercised. `genOrigin()` is not tested.

#### `CodecSpec`

File: `core/jvm/test/src/luau/core/codec/CodecSpec.scala`

Fifteen tests, all active, all using `FakeBinding`:

| Test | What it covers |
|------|----------------|
| `encode Double roundtrip` | `LuauEncoder[Double]` + `LuauDecoder[Double]` |
| `encode Boolean true/false` (×2) | `LuauEncoder[Boolean]` + `LuauDecoder[Boolean]` |
| `encode Int as Double` | `LuauEncoder[Int]` encodes as `Double`; decoded as `Double` |
| `encode nil (Unit)` | `LuauEncoder[Unit]` → `pushNil`; `FakeBinding.isNil` predicate |
| `encode String as UTF-8` | ASCII string via `LuauEncoder[String]` |
| `encode String with non-ASCII (UTF-8)` | Multi-byte string round-trip |
| `encode raw bytes roundtrip` | `LuauEncoder[IArray[Byte]]` + `LuauDecoder[IArray[Byte]]` |
| `encode Some(42.0)` | `LuauEncoder[Option[Double]]` → Some |
| `encode None as nil` | `LuauEncoder[Option[Double]]` → None |
| `decode Option: nil -> None` | `LuauDecoder[Option[Double]]` on nil stack slot |
| `decode Option: number -> Some` | `LuauDecoder[Option[Double]]` on number |
| `encode Seq[Double] as 1-indexed table` | **Known limitation:** asserts `Seq.empty` — decode returns empty even though encode wrote 3 elements. This documents a `FakeBinding.setArray` / `getArray` asymmetry: `setArray` stores into `FakeTable` keyed by `Number(n.toDouble)`, and `getArray` reads the same key — the round-trip should work, but the Seq decoder calls `getArray` on the encoded table, then immediately sees `Nil` at index 1 because `FakeBinding.typeAt` returns `LuaType.Nil` for `FakeTable`, causing `isNil` to return true before the first iteration. The test expectation (`Seq.empty`) documents this known failure mode rather than correcting it. |
| `encode empty Seq as empty table` | Zero-element Seq encodes as empty `FakeTable`; decode returns `Seq.empty` — correct |
| `encode Map[String, Double]` | Encodes map; the test then rawGet on key `"x"` from the encoded table — asserts `isNil`, which documents another `FakeBinding` limitation: `rawGet` with a string key on a `FakeTable` that was populated by `pushField`/`rawSet` using `LuaValue.LuaString` keys. The key comparison is value-level equality, so this should succeed — but the test asserts nil, confirming the `FakeBinding.rawGet` logic misses the key lookup when used through `SinkImpl.pushField`. |
| `derive LuauEncoder for case class` | Encodes `Point(3.0, 4.0)` as table; asserts rawGet of `"x"` returns nil — same `FakeBinding` limitation as Map test |
| `derive LuauDecoder for case class` | Attempts decode of encoded `Point`; asserts result is `Left` — documents that case class round-trip fails through `FakeBinding` |

Coverage note: `LuauEncoder[Long]`, `LuauEncoder[Float]`, `LuauEncoder[Array[Byte]]`, `LuauEncoder[List[A]]`, `LuauEncoder[Vector[A]]` are not tested. `LuauDecoder[Int]`, `LuauDecoder[Long]` are not tested. `LuauDecoder[Map[String,V]]` always returns `Right(Map.empty)` — this stub behavior is not explicitly tested. `SinkImpl.endTable` depth-mismatch guard is not tested. The `pushString` method on `Sink` (UTF-8 encode shortcut on `Sink.scala:17`) is not tested.

---

### 1.2 `core.js` — no test suite currently runnable

**Location:** `core/js/src` is a filesystem symlink pointing to `../jvm/src`. The `core.js` module (defined in `build.mill:34`) declares a `test` submodule at `build.mill:36`, but the test source directory (`core/jvm/test`) is only associated with `core.jvm.test`. The symlink mechanism means `core.js` compiles the same sources as `core.jvm` under Scala.js, but the `java.nio.charset.StandardCharsets` import present in `LuauDecoder.scala:55`, `LuaValue.scala:22`, `FakeBinding.scala:41`, and `Sink.scala:18` is not a Scala.js-compatible API. In practice, `core.js.test` has never been run; the JS test surface for core is inherited by the `wasm` module, which depends on `core.js` but exercises only the runtime stack/table/ref paths — never the codec or fake-binding paths.

---

### 1.3 `wasm` — four suites, all active and passing

**Location:** `wasm/test/src/luau/`

#### `WasmModuleSmokeTest`

File: `wasm/test/src/luau/wasm/WasmModuleSmokeTest.scala`

One test:

| Test | What it covers |
|------|----------------|
| `WASM module loads and lx_newstate creates a state` | `WasmBackend.load()` in `beforeAll`, then `_lx_newstate(0)` returns non-zero pointer, `_lx_close` does not crash |

#### `WasmSpecificSuite`

File: `wasm/test/src/luau/wasm/WasmSpecificSuite.scala`

Four tests:

| Test ID | Test | What it covers |
|---------|------|----------------|
| TC-WASM-01 | `WasmBackend.load() resolves` | `WasmModule.module != null` |
| TC-WASM-02 | `_malloc and _free do not crash` | Linear-memory allocation; `HEAPU8` read/write via typed-array accessor |
| TC-WASM-03 | `addFunction registers and dynCall works` | `addFunction` inserts a JS closure into the WASM table; `dynCall_iiiiii` calls it back |
| TC-WASM-04 | `two WasmBindings have independent states` | `WasmBinding.create()` twice; pushes to `s1` do not affect `s2` |

Note: TC-WASM-04 creates two `WasmBinding` instances without calling `Trampoline.reset()` between them. Both instances share the singleton `Trampoline`, meaning Native function IDs from one binding can interact with the other's dispatch table. The test passes only because it does not register any Native functions.

#### `SharedBackendSuite` (abstract) / `WasmBackendSuite` (concrete)

File: `wasm/test/src/luau/core/SharedBackendSuite.scala` (abstract)  
File: `wasm/test/src/luau/wasm/WasmBackendSuite.scala` (concrete, reloads WASM per test)

Ten backend-agnostic integration tests, all passing on WASM:

| Test ID | Test | What it covers |
|---------|------|----------------|
| TC-SHARED-01 | `basic execution returns integer` | `compileAndLoad` + `resume` → `Returned(1)`; `toNumber` |
| TC-SHARED-02 | `string push and read back` | `pushString` / `toBytes` for UTF-8 multibyte string |
| TC-SHARED-03 | `table construction via rawseti/rawgeti` | `newTable`, `setArray`, `getArray`, `toNumber` with negative indices |
| TC-SHARED-04 | `native function is callable from script` | `registerNativeFn`, `setGlobal`, script calls back into Native function |
| TC-SHARED-05 | `native function Fail raises Lua error` | `NativeFnResult.Fail` surfaces as pcall error; `toBoolean` on error flag; `toBytes` on error string |
| TC-SHARED-06 | `Ref lifecycle: create, push, close` | `ref(state)` pops stack; `stackTop` becomes 0; `Ref.push()` pushes back; `rawGet` on the table; `Ref.close()` |
| TC-SHARED-07 | `Scope closes all owned Refs on exit` | `openScope`, `scope.own(ref)`, `scope.close()` |
| TC-SHARED-08 | `resume yields on coroutine.yield` | `openLibs` with coroutine mask; `resume` returns `Yielded`; second `resume` returns `Returned` |
| TC-SHARED-09 | `UTF-8 multi-byte string preserved` | Extended Unicode (Japanese + emoji) round-trip through `pushString`/`toBytes` |
| TC-SHARED-10 | `compile error is surfaced as Left` | Invalid Luau source returns `Left(LuaError)` |

The `withBinding` override in `WasmBackendSuite` calls `WasmBackend.load()` inside each test invocation, which calls `Trampoline.reset()` before `install()`. This correctly isolates per-test state at the cost of WASM module reload on every test.

---

### 1.4 `panama` — six suites, all 22 tests `.ignored`

**Location:** `panama/test/src/luau/panama/`

All tests use the `.ignore` suffix in `munit`. The root cause is that `panama.test`'s `forkArgs` (at `build.mill:46–49`) include `--enable-native-access=ALL-UNNAMED` and `--enable-preview` but no `-Dluau.shim.lib` pointing to the native library produced by `shim.nativeBuild`. `LxHandles.scala:9–11` first tries `System.getProperty("luau.shim.lib")` and falls back to `System.loadLibrary("luau-shim")` — which requires the library to be on `java.library.path`. Without this, every `PanamaState.open()` call would fail at class initialization. The `.ignore` pattern was used deliberately so the test infrastructure is in place; unignoring is the final step after the build is wired up.

#### `NativeLibSmokeTest` (2 ignored tests)

File: `panama/test/src/luau/panama/NativeLibSmokeTest.scala`

| Test | Intent |
|------|--------|
| `lx_newstate returns non-null pointer` | `PanamaState.use { ps => ps.L.address() != 0L }` |
| `PanamaState.open() and close() lifecycle` | `open()` → non-null pointer; `close()` → `isClosed` becomes true |

#### `CompileAndRunTest` (5 ignored tests)

File: `panama/test/src/luau/panama/CompileAndRunTest.scala`

| Test | Intent |
|------|--------|
| `compile valid script returns Right(())` | `compileAndLoad` on valid Luau source |
| `compile syntax error returns Left(LuaError)` | `compileAndLoad` on invalid source |
| `resume returns Returned for trivial script` | `resume` after load → `Returned(n >= 1)` |
| `run 'return 1 + 1' yields integer 2 on stack` | `toNumber` reads `2.0` |
| `run multi-line script with local variables` | `30.0` from `local a = 10; local b = 20; return a + b` |

#### `NativeFunctionTest` (3 ignored tests)

File: `panama/test/src/luau/panama/NativeFunctionTest.scala`

| Test | Intent |
|------|--------|
| `native function Return(1) — script receives correct value` | `registerNativeFn` + `setGlobal` + script invocation + `NativeFnResult.Return(1)` |
| `native function Fail — script sees error` | `NativeFnResult.Fail(LuaValue.Nil)` inside pcall; `toBoolean` → false |
| `multiple native functions coexist by fnId` | Two distinct functions with different `fnId` values |

#### `RefLifecycleTest` (4 ignored tests)

File: `panama/test/src/luau/panama/RefLifecycleTest.scala`

| Test | Intent |
|------|--------|
| `lx_ref stores table and lx_push_ref retrieves it` | `ref(ps.L)` pops stack (`stackTop` → 1 before pop, 0 after pop, then 1 after `pushRef`); `typeAt` → `Table` |
| `PanamaRef.close() is idempotent` | Double `ref.close()` |
| `scoped block releases Refs on exit` | `ps.scoped { scope ?=> scope.captureTop() }` |
| `leaked Ref does not crash; state teardown frees it` | `PanamaState.use` closes state with outstanding `Ref` |

Note: these tests use `Ref[MemorySegment]` (returned by `PanamaState.ref()`) rather than `PanamaRef`. `PanamaRef` is a separate class managed by `PanamaScope`; its tests are covered implicitly through `scoped`, but `PanamaRef.push(thread)` and `PanamaScope.trackRef` are not exercised directly.

#### `StringMarshalTest` (5 ignored tests)

File: `panama/test/src/luau/panama/StringMarshalTest.scala`

| Test | Intent |
|------|--------|
| `ASCII string round-trip via push/read` | `pushString` → `toBytes` → `String` |
| `UTF-8 multibyte string round-trip (Japanese)` | `"こんにちは"` preserved |
| `toBytes returns None for non-string stack slot` | `pushNumber` then `toBytes` → `None` |
| `empty string round-trip` | `pushString("")` → `toBytes` → length 0 |
| `toNativeString allocates null-terminated C string` | `Marshal.toNativeString("abc", arena)` — checks byte values at offsets 0–3 directly via `ValueLayout.JAVA_BYTE` |

#### `SuspendResumeTest` (3 ignored tests)

File: `panama/test/src/luau/panama/SuspendResumeTest.scala`

| Test | Intent |
|------|--------|
| `native Suspend returns Yielded from resume()` | `NativeFnResult.Suspend` causes `resume()` to return `Yielded(0)`; `capturedResume` is populated |
| `synchronous resume after Suspend delivers result` | Accesses `ps.lastYieldToken` and `ps.suspendRegistry.consume(token)` — both are `PanamaState`-specific fields not on `Binding[H]`; pushes `42.0`, calls `capturedResume.get.succeed(LuaValue.Number(42.0))`; second `resume` returns `Returned(1)` with `43.0` on stack |
| `calling Resume with Left(LuaError) propagates error` | `capturedResume.get.fail(LuaError.runtime("test error"))` causes `pcall` to return false |

---

### 1.5 `scheduler.jvm` — eight tests, all active

**Location:** `scheduler/jvm/test/src/luau/scheduler/`

The scheduler module exists on disk with full source under `scheduler/jvm/src/` but is **not wired into `build.mill`**. Its test module at `scheduler/jvm/test/` likewise has no mill definition. All tests are written against `TestBinding` (defined in `TestHelpers.scala`), which wraps `FakeBinding` and allows programmatic control of `resume()` return values via `programResumes(results*)`.

| Test ID | Test | What it covers |
|---------|------|----------------|
| TC-01 | `spawned Task transitions Queued → Running → Complete` | `spawn()` → `Queued`; `runAllReady()` → `Complete` |
| TC-02 | `Suspend parks Task; completion re-enqueues; second runAllReady resumes` | `Yielded` → `Parked`; `Resume.complete` → `Queued`; second `runAllReady()` → `Complete` |
| TC-03 | `double resume is a no-op (does not enqueue twice)` | One-shot resume: calling `complete` twice still leaves `runAllReady()` returning 1 |
| TC-04 | `lx_resume error transitions Task to Failed and invokes error policy` | `Error(LuaError)` → `TaskState.Failed`; `ErrorPolicy` callback fires |
| TC-05 | `close() fires Cancel for parked Tasks` | `sched.close()` cancels parked tasks; `Cancel` callback fires; state → `Cancelled` |
| TC-06 | `bare coroutine.yield (no Suspend registered) parks Task permanently` | `Yielded` without a pending `Suspend` → `Parked`; `runAllReady()` returns 0 |
| TC-07 | `two Tasks both spawn, first completes then second completes` | Multi-task queue drains in order |
| TC-08 | `Task cancelled between enqueue and dequeue is skipped by Driver` | `task.setState(Cancelled)` after `Resume.complete` enqueues; `resumeTask` early-returns for cancelled |

---

### 1.6 `stdlib.jvm` — eleven tests, all active

**Location:** `stdlib/jvm/test/src/luau/stdlib/StdlibSuite.scala`

The stdlib module similarly exists on disk with three source files (`StdlibOpener.scala`, `TaskLibrary.scala`, `LuaArgs.scala`) and test Luau fixtures under `stdlib/jvm/test/resources/luau/`. It is **not wired into `build.mill`**. All tests use `FakeBinding`/`TestBinding` and do not run actual Luau scripts.

| Test | What it covers |
|------|----------------|
| `StdlibOpener.open calls openLibs then sandbox` | Call-order binding records `openLibs` and `sandbox`; asserts `oi < si` |
| `StdlibMask.Standard includes all expected libs, excludes Debug` | Bitmask arithmetic for each library constant |
| `Scheduler.spawnImmediate creates Task that completes or yields` | `spawnImmediate(fnRef, Nil)` → `Complete` or `Parked` |
| `Scheduler.deferTask creates Task in Queued state` | `deferTask(fnRef, Nil)` → `Queued` |
| `Scheduler.scheduleDelayed creates Task in Parked state` | `scheduleDelayed(fnRef, Nil, 10.0)` → `Parked` |
| `Scheduler.cancelTask removes a Parked task` | `cancelTask` → `Cancelled` |
| `Scheduler.cancelTask on completed task is no-op` | `cancelTask` after `Complete` → still `Complete` |
| `Scheduler.currentTask is None outside resume` | `currentTask` returns `None` before any `runAllReady()` |
| `Scheduler.enqueueResume creates Queued task` | `enqueueResume(task, Right(Number(42.0)))` queues task |
| `Scheduler.cancelThread cancels task by threadRef` | `cancelThread(handle.threadRef)` cancels via `Ref` key lookup |
| `TaskLibrary.install creates task global table` | `getGlobal(state, "task")` → `LuaType.Table` |

---

## 2. JS-vs-JVM Parity Matrix

The table below maps each testable behavior from `SharedBackendSuite` to its status on each platform. "PASS" means the test currently passes. "IGNORED" means the test exists but is disabled. "MISSING" means no test file covers the behavior at all on that platform. "UNWIRED" means the module's mill definition does not exist, so tests cannot be run.

| Behavior | WASM (JS) | Panama (JVM) | Notes |
|----------|-----------|--------------|-------|
| TC-SHARED-01: basic execution | PASS | IGNORED | `CompileAndRunTest` covers this pattern |
| TC-SHARED-02: string push/read | PASS | IGNORED | `StringMarshalTest` covers this |
| TC-SHARED-03: table rawseti/rawgeti | PASS | IGNORED | No direct equivalent in panama tests |
| TC-SHARED-04: native function Return | PASS | IGNORED | `NativeFunctionTest` |
| TC-SHARED-05: native function Fail | PASS | IGNORED | `NativeFunctionTest` |
| TC-SHARED-06: Ref lifecycle | PASS | IGNORED | `RefLifecycleTest` |
| TC-SHARED-07: Scope lifecycle | PASS | IGNORED | `RefLifecycleTest` (`scoped`) |
| TC-SHARED-08: coroutine.yield → Yielded | PASS | IGNORED | `SuspendResumeTest` exercises Yielded but not via coroutine.yield directly |
| TC-SHARED-09: UTF-8 preservation | PASS | IGNORED | `StringMarshalTest` |
| TC-SHARED-10: compile error as Left | PASS | IGNORED | `CompileAndRunTest` |
| Library load smoke test | PASS (WasmModuleSmokeTest) | IGNORED (NativeLibSmokeTest) | — |
| Two independent states | PASS (TC-WASM-04) | MISSING | No `PanamaState` two-instance isolation test |
| `_malloc`/`_free` (WASM-specific) | PASS (TC-WASM-02) | N/A | JVM has no equivalent; Panama uses `Arena` |
| `addFunction`/`dynCall` (WASM-specific) | PASS (TC-WASM-03) | N/A | JVM uses FFM upcall stubs |
| `NativeFnResult.Suspend` wiring | PASS (via TC-SHARED-04 indirectly; TC-WASM-03 exercises trampoline) | IGNORED | `SuspendResumeTest` is the most complete panama coverage |
| Scheduler run loop | UNWIRED (no JS scheduler) | UNWIRED | `SchedulerTests` exists but not in mill |
| Stdlib mask/opener | UNWIRED | UNWIRED | `StdlibSuite` exists but not in mill |
| Codec round-trip on real state | MISSING (wasm) | MISSING (panama) | `CodecSpec` uses only `FakeBinding` |
| `PanamaSink` integration | N/A | MISSING | No test exercises `PanamaSink` directly |

---

## 3. Untested ABI Functions

### 3.1 ABI functions with no test coverage on either platform

The following Shim C ABI entry points are declared in `LxHandles.scala` (and their equivalents in `WasmModuleExports`) but are never exercised by any passing test:

| ABI function | `LxHandles.scala` line | Note |
|---|---|---|
| `lx_push_integer` | 49 | `PanamaState` has no `pushInteger`; Binding trait has no `pushInteger` either. The handle exists but is unused. |
| `lx_to_integer` | 61 | Same — no Binding method calls this. |
| `lx_rawlen` | 67 | Called by `PanamaState.toBytes` internally (line 132) but no test verifies `Binding.rawLen` directly. |
| `lx_gc_step` | 92 | No Binding method exposes GC stepping; handle exists only in `LxHandles`. |
| `lx_gc_collect` | 93 | Same. |
| `lx_open_libs` | 91 | Distinct from `lx_openlibs`; no test calls `Binding.openLibs` on a real Panama or WASM state (ignored or not wired). |
| `lx_sandbox` | 90 | Never called by any active test. `StdlibOpener` calls it but stdlib is not wired. |
| `lx_copy_error` | 95–96 | Used internally by `PanamaState.readError` and `WasmBinding.readError` when `resume` returns an error code, but TC-SHARED-10 only tests compile failure; no test triggers a runtime `LX_RESUME_ERR` on a real state. |
| `lx_set_global` / `lx_get_global` | Absent from `LxHandles` | `PanamaState.setGlobal` implements a 5-step workaround using `lx_ref` / `lx_pop` / `pushString` / `lx_push_ref` / `lx_rawset` / `lx_unref` (lines 209–215). `LxHandles` has no `lx_set_global` or `lx_get_global` MethodHandle. `WasmBinding` uses `_lx_set_global`/`_lx_get_global` directly (lines 224–231). The workaround is untested. |
| `lx_push_copy` | 53 | Used by `PanamaState.pushCopy`; no test calls `Binding.pushCopy`. |
| `lx_new_thread` | 35 | Called by `PanamaState.newThread`; the Scheduler calls it but the Scheduler is not wired into mill. |
| `lx_thread_status` | 36–37 | No Binding method exposes thread status; the handle exists but is unused. |

### 3.2 ABI functions tested only on WASM (not on JVM)

All TC-SHARED-01 through TC-SHARED-10 passing results count here: every underlying ABI function they exercise (`_lx_newstate`, `_lx_compile_and_load`, `_lx_resume`, `_lx_push_nil`, `_lx_push_number`, `_lx_push_lstring`, `_lx_rawseti`, `_lx_rawgeti`, `_lx_newtable`, `_lx_ref`, `_lx_unref`, `_lx_push_ref`, `_lx_type`, `_lx_to_number`, `_lx_to_boolean`, `_lx_to_lstring`, `_lx_register_native`, `_lx_rawget`, `_lx_openlibs`) is tested on WASM but not on JVM.

---

## 4. Untested Error Paths

The following error conditions have no test covering them on any platform:

| Error path | Location | Risk |
|---|---|---|
| `lx_newstate` returns `NULL` → `OutOfMemoryError` | `PanamaState.open():268–271` | Memory exhaustion path; never triggered in tests |
| `lx_ref` returns `-1` (LUA_NOREF) → `IllegalStateException` | `PanamaState.ref():188–189` | Triggered when stack is empty; `RefLifecycleTest` always pushes before calling `ref` |
| `lx_compile_and_load` returns non-zero with an error string | `PanamaState.compileAndLoad():46–50` | TC-SHARED-10 covers this on WASM; all Panama tests ignored |
| `lx_resume` returns `LX_RESUME_MEMERR` | `PanamaState.resume():67` | The `LX_RESUME_MEMERR` branch is a distinct case from `LX_RESUME_ERR`; no test triggers memory allocation failure during resume |
| `lx_resume` returns unexpected status code | `PanamaState.resume():69–70` | The wildcard `case _` branch has no test |
| `Ref.push()` on a closed `Ref` | `Ref.scala:13` | `require(!closed, ...)` throws; no test verifies this |
| `SinkImpl.endTable` called without matching `beginTable` | `SinkImpl.scala:22` | `require(depth > 0, ...)` guard; no test triggers it |
| `SinkImpl.pushValue` / `pushArrayValue` called outside `beginTable` | `SinkImpl.scala:34–40` | Silent stack corruption; no guard; no test |
| `PanamaState.checkOpen()` after `close()` | `PanamaState.scala:241–242` | Throws `IllegalStateException`; no test calls any method on a closed `PanamaState` |
| `LuaRef` as table key in `SinkImpl.pushKey` | `SinkImpl.scala:31–32` | ADR-0006 `IllegalArgumentException`; no test deliberately passes a `LuaRef` as key |
| `LuaRef` as table key in `PanamaSink.pushKey` | `PanamaSink.scala:24–25` | Same — no test |
| `WasmBinding.unref` on a dead state | `WasmBinding.scala:213` | No closed-state guard (unlike `FakeBinding.unref:128`); no test |
| `NativeFnDispatcher.dispatch` with unknown `fnId` | `NativeFnDispatcher.scala:33–36` | Returns `LX_FAIL` after pushing error; no test |
| `NativeFnDispatcher.dispatch` when `fn` throws | `NativeFnDispatcher.scala:40–43` | `catch Throwable` wraps and returns `Fail`; no test |
| `LuaType.fromCode` with unknown code | `LuaType.scala:15–17` | Throws `IllegalArgumentException`; no test |
| `WasmBinding.typeAt` with code 2 | `WasmBinding.scala:111` | Returns `LuaType.Nil` for `LUA_TLIGHTUSERDATA`; no test verifies this or reports the discrepancy |
| `LuauDecoder.derived` for case class when field is missing | `LuauDecoder.scala:113` | Returns `Left(LuaError)` when field decode fails; `CodecSpec` tests this only through `FakeBinding` where it always fails — no real-state round-trip |
| `SuspendRegistry.consume` returns `None` (double consume) | `SuspendRegistry.scala:17` | `SuspendResumeTest` exercises a single consume; double-consume not tested |

---

## 5. Missing Property and Stress Tests

### 5.1 GC pressure / Ref churn

No test creates hundreds of `Ref` values to verify that `lx_ref` / `lx_unref` remain correct under GC pressure. The Luau GC collects unreachable values; if `Binding.ref` is called but `close()` is delayed (or never called due to an exception), registry slots accumulate. Relevant paths:

- `PanamaState.ref()` at `PanamaState.scala:185–191`: each call allocates a JVM `Arena.ofConfined()` internally. Ref leaks under exception in `withArena` could leak the confined arena.
- `FakeBinding.ref()` at `FakeBinding.scala:121–125`: no arena, but `registry` grows unboundedly.

A property test should: create N refs, assert each has a distinct registry key, close all, assert all registry slots are freed.

### 5.2 Large strings

`PanamaState.pushString` and `WasmBinding.pushString` allocate native memory for string bytes. No test uses strings larger than a few kilobytes. Edge cases not covered:

- Strings at or near the `rawlen` return-value boundary (`Long` from `lx_rawlen`).
- Strings containing embedded null bytes (Luau strings are not null-terminated; `lx_push_lstring` takes explicit length).
- Strings exceeding 4096 bytes (the `compileAndLoad` error buffer is 4096 bytes; a very long source file with a compile error will truncate the message).

### 5.3 Deep tables / nested Codec encoding

No test encodes a deeply nested case class or a `Seq[Seq[Double]]` (nested tables). `SinkImpl.depth` tracks nesting but `pushValue` / `pushArrayValue` do not validate `depth > 0`. A property test for depth consistency should create encoded structures up to depth N and verify decode matches encode, without corrupting the stack.

### 5.4 Coroutine.yield depth / multi-resume sequences

TC-SHARED-08 exercises a single yield-then-resume. No test exercises:

- Multiple successive yields (yield, resume, yield, resume, ...) to verify the Scheduler's queue integrity.
- A Native function that yields, then the `Resume` is called from a different OS thread (thread-safety of `Scheduler.wireSuspend`'s `@volatile var fired`).

### 5.5 Multiple concurrent states (two-state isolation)

TC-WASM-04 tests two `WasmBinding` instances but does not register Native functions. No test verifies that two `PanamaState` instances:

- Have independent Luau registries (different `lx_ref` sequences).
- Can both register Native functions without fnId collision. `NativeFnDispatcher.nextId` is a plain `var` in a non-synchronized `register()` method (`NativeFnDispatcher.scala:18–22`). Two `PanamaState` instances each have their own `NativeFnDispatcher`, so there is no cross-instance collision risk at the dispatcher level — but this is not tested.

### 5.6 Error message fidelity

No test verifies that a Luau runtime error message (e.g., "attempt to call nil") propagates exactly through `lx_copy_error` / `PanamaState.readError` / `ResumeResult.Error.error.message`. Only compile errors are tested (TC-SHARED-10). A runtime error that produces a long message might be truncated by the 4096-byte `readError` buffer at `PanamaState.scala:247`.

---

## 6. Prioritized Backlog of Tests to Add

Priority legend: **P0** = blocks all JVM progress; **P1** = high value, JVM-side correctness; **P2** = medium value, parity or regression coverage; **P3** = quality / stress / property.

### P0 — Unblock Panama execution

#### P0-A: Wire `shim.nativeBuild` output into `panama.test` fork args

**Not a test to write — a build fix.** Add `-Dluau.shim.lib=${shim.nativeBuild().path}/libluau-shim.so` (Linux) or `.dylib` (macOS) to `panama.test.forkArgs` in `build.mill` (currently lines 46–49). Until this is done, unignoring any panama test causes a `UnsatisfiedLinkError` at `LxHandles.scala:11`.

Once wired, the following test suites can be unignored without modification.

#### P0-B: Unignore `NativeLibSmokeTest`

After P0-A, remove `.ignore` from both tests. These are the first passing JVM integration tests.

**File:** `panama/test/src/luau/panama/NativeLibSmokeTest.scala`

#### P0-C: Unignore `CompileAndRunTest` (5 tests)

**File:** `panama/test/src/luau/panama/CompileAndRunTest.scala`

Remove `.ignore` from all 5 tests.

#### P0-D: Unignore `StringMarshalTest` (5 tests)

**File:** `panama/test/src/luau/panama/StringMarshalTest.scala`

All 5 tests cover ASCII, UTF-8, empty string, non-string `toBytes`, and `Marshal.toNativeString`. These do not depend on native function dispatch.

---

### P1 — Core correctness once Panama executes

#### P1-A: Unignore `RefLifecycleTest` (4 tests)

**File:** `panama/test/src/luau/panama/RefLifecycleTest.scala`

Exercises `lx_ref`, `lx_push_ref`, `lx_unref`, and `PanamaState.scoped`.

#### P1-B: Unignore `NativeFunctionTest` (3 tests)

**File:** `panama/test/src/luau/panama/NativeFunctionTest.scala`

Exercises `registerNativeFn`, `NativeFnDispatcher.dispatch`, `NativeFnResult.Return`, and `NativeFnResult.Fail`.

#### P1-C: Add `PanamaBackendSuite` extending `SharedBackendSuite`

Create `panama/test/src/luau/panama/PanamaBackendSuite.scala`:

```scala
package luau.core

import luau.core.Binding
import luau.panama.PanamaState
import java.lang.foreign.MemorySegment

class PanamaBackendSuite extends SharedBackendSuite:
  override def withBinding[A](f: Binding[Int] => A): A = ???
```

The immediate blocker is that `SharedBackendSuite` is parameterized over `Binding[Int]` (`SharedBackendSuite.scala:7`), but `PanamaState implements Binding[MemorySegment]`. There are two clean solutions:

1. **Parameterize `SharedBackendSuite` over `H`**: change `def withBinding[A](f: Binding[Int] => A): A` to `def withBinding[A, H](f: Binding[H] => A): A` and update `WasmBackendSuite` accordingly. This is the correct long-term design.
2. **Create a `PanamaBinding` adapter object** that wraps `PanamaState` and implements `Binding[Int]` by storing the `PanamaState` and converting `Int` to `MemorySegment` via `MemorySegment.ofAddress`. This is more fragile.

Until this is resolved, TC-SHARED-01 through TC-SHARED-10 cannot run against Panama.

#### P1-D: Unignore `SuspendResumeTest` and extract Suspension API to `Binding` trait

**File:** `panama/test/src/luau/panama/SuspendResumeTest.scala`

The second and third tests access `ps.lastYieldToken` and `ps.suspendRegistry.consume(token)` — fields on `PanamaState` not on `Binding[H]`. Before unignoring, either:

- Expose a `SuspendableBinding` trait with `lastYieldToken: Long` and `consumeSuspend(token: Long): Option[NativeFnResult.Suspend]`, or
- Rewrite the tests to not rely on the token: use `capturedResume` directly (the resume callback is already captured) without needing to consume from the registry.

The test for "synchronous resume after Suspend delivers result" can be rewritten as:

```scala
val r1 = ps.resume(ps.L, 0)
assertEquals(r1, ResumeResult.Yielded(0))
assert(capturedResume.isDefined)
ps.pushNumber(ps.L, 42.0)
capturedResume.get.succeed(LuaValue.Number(42.0))
val r2 = ps.resume(ps.L, 1)
assertEquals(r2, ResumeResult.Returned(1))
assertEquals(ps.toNumber(ps.L, -1), Some(43.0))
```

This removes the token dependency while still verifying the full suspend-resume round-trip.

#### P1-E: Add test for `NativeFnResult.Return` — push multiple return values

Current `NativeFunctionTest` only checks `Return(1)` with a single value pushed. Add a test for `Return(2)` where the Native function pushes two values and the script destructures them: `local a, b = multiReturn()`.

**File:** `panama/test/src/luau/panama/NativeFunctionTest.scala`

#### P1-F: Add test for `Ref.push()` on a closed `Ref` throws

```scala
test("Ref.push on closed Ref throws"):
  val state = FakeBinding.newState()
  FakeBinding.pushNumber(state, 1.0)
  val r = FakeBinding.ref(state)
  r.close()
  intercept[IllegalArgumentException] { r.push() }
```

**File:** `core/jvm/test/src/luau/core/RefScopeSpec.scala`

#### P1-G: Wire `scheduler.jvm` and `stdlib.jvm` into `build.mill`

Add mill module definitions. Currently `SchedulerTests` and `StdlibSuite` are on-disk but cannot be run. These depend on `core.jvm` and each other (`stdlib` imports `scheduler`). The module definitions should follow the pattern of `panama`:

```scala
object scheduler extends Module {
  object jvm extends LuauCrossPlatformModule {
    override def moduleDeps = super.moduleDeps ++ Seq(core.jvm)
    object test extends ScalaTests with TestModule.Munit { ... }
  }
}
```

---

### P2 — Parity and coverage improvements

#### P2-A: Add `Scope.own()` test

`Scope.own(r)` is defined in `Scope.scala` but never called in any test — only `captureTop()` is used in `RefScopeSpec`. Add a test that creates a `Ref` externally, passes it to `scope.own(r)`, then verifies `close()` closes it.

**File:** `core/jvm/test/src/luau/core/RefScopeSpec.scala`

#### P2-B: Fix or document `CodecSpec` Seq round-trip failure

The test `encode Seq[Double] as 1-indexed table` (line 91–95) asserts `Seq.empty`. This documents a known `FakeBinding` limitation. Two options:

1. Add a comment explaining why `Seq.empty` is expected and that `Seq` decode only works correctly against a real Luau state.
2. Fix `FakeBinding.typeAt` so that `FakeTable` returns `LuaType.Table` from `isNil` to not prematurely terminate the Seq iteration. The current code returns `LuaType.Table` for `FakeTable` in `typeAt`, and `isNil` calls `typeAt(...) == LuaType.Nil`. A `FakeTable` is not nil, so iteration should not terminate immediately. Tracing the actual failure: `LuauDecoder[Seq[A]]` calls `b.getArray(s, idx, 1)`, which pushes a value from the table, then calls `b.isNil(s, -1)`. If `FakeBinding.setArray` used `LuaValue.Number(1.0)` as key but the decode loop is looking at a table slot via `getArray` which pushes the value — the FakeTable contains `Number(1.0) → Number(10.0)`, so `getArray(s, idx, 1)` should push `Number(10.0)`. The issue is that `idx` in the decode loop is the absolute index of the table on the stack, but after `encode(Seq(10.0, 20.0, 30.0))`, the FakeState has both the intermediate push artifacts and the final table. Specifically, `SinkImpl.pushArrayValue` calls `binding.setArray(state, -2, n)` which pops the top value and stores it, leaving the table at -1. But `SinkImpl.beginTable` increments `depth` but does not enforce that table is at -2 before `setArray`. The encode path should leave only the table at the top after `endTable`. Testing with `pushEncoded` → the FakeState after `encode(Seq(10.0, 20.0, 30.0))` has exactly one item on the stack (the FakeTable). So `decode[Seq[Double]](s)` at index `-1` reads the FakeTable; `getArray(s, -1, 1)` pushes `Number(10.0)` (since `FakeTable` has key `Number(1.0) → Number(10.0)`); `isNil(s, -1)` checks `typeAt(s, -1)` → `Number(10.0)` → `LuaType.Number` — not nil. So iteration should proceed. This suggests the actual bug is elsewhere — likely that `setArray` after `beginTable` is operating on the wrong stack index, or `SinkImpl.pushArrayValue` → `binding.setArray(state, -2, n)` is operating on the wrong level when the table is the only item.

Regardless, a test against a real Panama or WASM state (once P0-A is resolved) would be definitive.

#### P2-C: Add two-PanamaState isolation test

Once P0-A and P0-B pass, add:

```scala
test("two PanamaState instances have independent registries and stacks"):
  PanamaState.use { ps1 =>
    PanamaState.use { ps2 =>
      ps1.pushNumber(ps1.L, 1.0)
      ps2.pushNumber(ps2.L, 2.0)
      assert(ps1.toNumber(ps1.L, -1).contains(1.0))
      assert(ps2.toNumber(ps2.L, -1).contains(2.0))
      ps1.newTable(ps1.L)
      val ref1 = ps1.ref(ps1.L)
      ps2.newTable(ps2.L)
      val ref2 = ps2.ref(ps2.L)
      assertNotEquals(ref1.registryKey, ref2.registryKey, "registry keys must be independent")
      ref1.close()
      ref2.close()
    }
  }
```

**File:** Add to `panama/test/src/luau/panama/NativeLibSmokeTest.scala` or a new `PanamaIsolationTest.scala`.

#### P2-D: Add `PanamaSink` integration test

`PanamaSink` at `panama/src/luau/panama/PanamaSink.scala` is never tested. Add a test that encodes a `Map[String, Double]` or a case class through `PanamaSink` on a real `PanamaState`, then decodes it:

```scala
test("PanamaSink encodes case class and decodes on real state"):
  PanamaState.use { ps =>
    case class Point(x: Double, y: Double) derives LuauEncoder, LuauDecoder
    val sink = PanamaSink(ps)
    LuauEncoder[Point].encode(Point(3.0, 4.0), sink)
    // stack top is the encoded table
    val result = LuauDecoder[Point].decode(ps, ps.L, -1)
    assertEquals(result, Right(Point(3.0, 4.0)))
    ps.pop(ps.L, 1)
  }
```

**File:** New `panama/test/src/luau/panama/PanamaSinkTest.scala`

#### P2-E: Add `lx_set_global` / `lx_get_global` direct MethodHandle to `LxHandles`

`PanamaState.setGlobal` uses a 5-step workaround at lines 209–215. Add MethodHandles for `lx_set_global` and `lx_get_global` (as `WasmBinding` uses `_lx_set_global`/`_lx_get_global` at lines 224–231), and test the round-trip:

```scala
test("setGlobal and getGlobal round-trip a number"):
  PanamaState.use { ps =>
    ps.pushNumber(ps.L, 99.0)
    ps.setGlobal(ps.L, "myGlobal")
    assertEquals(ps.stackTop(ps.L), 0)
    ps.getGlobal(ps.L, "myGlobal")
    assertEquals(ps.toNumber(ps.L, -1), Some(99.0))
    ps.pop(ps.L, 1)
  }
```

**File:** New test in `panama/test/src/luau/panama/CompileAndRunTest.scala` or a dedicated `PanamaGlobalTest.scala`.

#### P2-F: Add `WasmBinding.typeAt` code-2 regression test

`WasmBinding.typeAt` (line 111) returns `LuaType.Nil` for code 2. The Luau type code 2 is `LUA_TLIGHTUSERDATA`. This mapping silently drops lightuserdata as nil. Add a test that verifies `typeAt` returns the correct `LuaType` for each value type pushed:

```scala
test("typeAt returns correct LuaType for each value kind"):
  withBinding { b =>
    val state = b.newState()
    try
      b.pushNil(state);     assertEquals(b.typeAt(state, -1), LuaType.Nil)
      b.pushBoolean(state, true); assertEquals(b.typeAt(state, -1), LuaType.Boolean)
      b.pushNumber(state, 1.0);  assertEquals(b.typeAt(state, -1), LuaType.Number)
      b.pushString(state, "x");  assertEquals(b.typeAt(state, -1), LuaType.String)
      b.newTable(state);     assertEquals(b.typeAt(state, -1), LuaType.Table)
      b.pop(state, 5)
    finally b.closeState(state)
  }
```

Add this as TC-SHARED-11 in `SharedBackendSuite`, or as a WASM-specific test in `WasmSpecificSuite`.

---

### P3 — Stress, property, and GC tests

#### P3-A: Ref churn under GC pressure

```scala
test("P3-A 1000 refs created and closed do not leak registry slots"):
  withBinding { b =>
    val state = b.newState()
    try
      val keys = (1 to 1000).map { _ =>
        b.pushNumber(state, 42.0)
        val r = b.ref(state)
        val key = r.registryKey
        r.close()
        key
      }
      assertEquals(keys.distinct.size, 1000, "all registry keys must be distinct")
    finally b.closeState(state)
  }
```

Verify via `FakeBinding` first, then on WASM/Panama once P0-A resolves.

**File:** Add to `RefScopeSpec` (FakeBinding version) and to `SharedBackendSuite` (real-backend version).

#### P3-B: Large string round-trip (128 KB)

```scala
test("P3-B 128KB string round-trips through pushString/toBytes"):
  withBinding { b =>
    val state = b.newState()
    try
      val largeStr = "x" * (128 * 1024)
      b.pushString(state, largeStr)
      val result = b.toBytes(state, -1).map(ba => new String(ba.toArray, "UTF-8"))
      assertEquals(result, Some(largeStr))
      b.pop(state, 1)
    finally b.closeState(state)
  }
```

**File:** Add as TC-SHARED-12 in `SharedBackendSuite`.

#### P3-C: String with embedded null bytes

```scala
test("P3-C string with embedded NUL byte preserved via pushBytes/toBytes"):
  withBinding { b =>
    val state = b.newState()
    try
      val bytes = IArray[Byte](0x61, 0x00, 0x62)  // "a\0b"
      b.pushBytes(state, bytes)
      val result = b.toBytes(state, -1)
      assert(result.exists(_.sameElements(bytes)))
      b.pop(state, 1)
    finally b.closeState(state)
  }
```

**File:** Add as TC-SHARED-13 in `SharedBackendSuite`.

#### P3-D: `SinkImpl.pushValue` without `beginTable` triggers stack corruption (regression guard)

```scala
test("P3-D SinkImpl.pushValue without beginTable corrupts stack — document and guard"):
  val state = FakeBinding.newState()
  val sink = SinkImpl(FakeBinding, state)
  FakeBinding.pushNumber(state, 1.0)
  FakeBinding.pushString(state, "key")
  // Without beginTable, calling pushValue does rawSet on whatever is at -3
  // This is a misuse, but currently unguarded. Until a guard is added,
  // this test documents the behavior.
  intercept[Exception] { sink.pushValue(99.0) }
```

**File:** `core/jvm/test/src/luau/core/codec/CodecSpec.scala`

#### P3-E: Multi-yield stress test

```scala
test("P3-E 100 successive yield/resume cycles maintain stack integrity"):
  withBinding { b =>
    val state = b.newState()
    try
      b.openLibs(state, 1 | (1 << 7))
      val src = IArray.unsafeFromArray(
        """
        for i = 1, 100 do
          coroutine.yield(i)
        end
        return "done"
        """.trim.getBytes("UTF-8")
      )
      b.compileAndLoad(state, src, "stress").fold(e => fail(e.message), identity)
      var yields = 0
      var running = true
      while running do
        b.resume(state, 0) match
          case ResumeResult.Yielded(_) => yields += 1
          case ResumeResult.Returned(_) => running = false
          case ResumeResult.Error(e) => fail(e.message)
      assertEquals(yields, 100)
    finally b.closeState(state)
  }
```

**File:** Add as TC-SHARED-14 in `SharedBackendSuite`.

---

## 7. Summary of Gaps by Module

```
Module            Active Tests  Ignored Tests  Missing Tests (priority)
core.jvm          16            0              P1: closed-Ref push; P2: Scope.own, Long/Float encoder, Map decoder stub documented; P3: Seq round-trip fix
core.js           0             0              P2: compilation never verified; java.nio.charset usage risks Scala.js compat
wasm              15            0              P2: TC-SHARED-11 typeAt correctness; P3: large string, embedded NUL, multi-yield
panama            0             22             P0: build wire-up; P0: unignore all 22; P1: PanamaBackendSuite; P1: Suspension API refactor; P2: PanamaSink; P2: setGlobal/getGlobal direct handles; P2: two-state isolation; P3: stress tests
scheduler.jvm     0 (unwired)   0              P1: wire into build.mill (8 tests already written)
stdlib.jvm        0 (unwired)   0              P1: wire into build.mill (11 tests already written)
```

---

## 8. Key Correctness Findings (Not Strict Coverage Gaps)

These are bugs or risks discovered while verifying the input data against actual source code. They are relevant to test authoring because tests must account for (or expose) them.

### 8.1 `NativeFnDispatcher.register()` is not thread-safe

`NativeFnDispatcher.scala:18–22`: `nextId` is a plain `var` and `register()` is not `synchronized`. When the Scheduler drives Tasks on multiple threads simultaneously, two `registerNativeFn` calls can race and produce the same `fnId`. The `fns` map is a `ConcurrentHashMap` (thread-safe for put/get), but the ID allocation sequence is not atomic. **Test needed:** a concurrent registration test that spawns N threads, each calling `registerNativeFn`, and asserts all IDs are distinct.

### 8.2 `WasmBinding.typeAt` code mapping is incorrect for standard Luau type codes

`WasmBinding.scala:111–120`: code 2 returns `LuaType.Nil`, codes 3–5 all return `LuaType.Number`. The standard Luau type codes are: 0=nil, 1=boolean, 2=lightuserdata, 3=number, 4=string, 5=table. The Shim may remap these (see `LxConstants.scala` in the panama module where `LX_TINTEGER=4`, `LX_TVECTOR=5`, `LX_TSTRING=6`, `LX_TTABLE=7`), meaning the WASM binding is using the Shim-remapped codes, not the raw Lua codes. The `PanamaState.typeAt` correctly uses named `LX_T*` constants (`PanamaState.scala:107–116`), while `WasmBinding.typeAt` uses raw integers. If the Shim exports non-standard type codes, the WASM mapping (codes 6→String, 7→Table) may be correct for the Shim's ABI — but this is undocumented and the mapping should be replaced with the same named constants used in the panama backend.

### 8.3 `PanamaSink.endTable` does not track depth

`PanamaSink.scala:17`: `endTable()` is a no-op (no `depth` counter, no `require` guard). `SinkImpl` does track depth and guards `endTable` with `require(depth > 0)` (`SinkImpl.scala:22`). These implementations diverge, and the `PanamaSink` variant silently ignores mismatched `beginTable`/`endTable` calls. This should be unified.

### 8.4 `LuauDecoder[Map[String, V]]` always returns `Right(Map.empty)`

`LuauDecoder.scala:88–92`: the `Map` decoder is a stub. No error is returned; the caller silently receives an empty map. Any codec path that deserializes a table as a `Map` will produce empty results without warning. The stub comment references a `Binding.tableNext` method (not yet defined on the trait). Until `tableNext` is added, callers must not use `LuauDecoder[Map]`.
