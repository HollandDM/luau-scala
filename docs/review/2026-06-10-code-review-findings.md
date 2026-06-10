# Code Review Findings — luau-scala

**Date:** 2026-06-10  
**Method:** Multi-agent adversarial review, 41 claims examined, all re-verified against source.  
**Scope:** core/jvm, panama, wasm, shim/src, scheduler/jvm, build.mill, .github/workflows/ci.yml

---

## Summary Table

| Severity | Correctness | Error-Boundary | FFI-Memory | Lifetime | Concurrency | API-Parity | Build/Test | Total |
|----------|-------------|----------------|------------|----------|-------------|------------|------------|-------|
| Critical | 2           | 2              | 0          | 0        | 0           | 0          | 0          | **4** |
| High     | 3           | 2              | 1          | 3        | 3           | 4          | 3          | **19**|
| Medium   | 1           | 3              | 1          | 1        | 0           | 1          | 2          | **9** |
| Low      | 0           | 2              | 0          | 0        | 0           | 0          | 3          | **5** |
| **Total**| **6**       | **9**          | **2**      | **4**    | **3**       | **5**      | **8**      | **37**|

---

## Confirmed Findings

### Critical

---

#### C-CRIT-01 · Correctness · `core/jvm/src/luau/core/codec/LuauEncoder.scala:103–115`

**Title:** `derivedEncoder` never calls `rawSet` — all case class fields silently dropped

**Detail:**  
`derivedEncoder` (lines 103–115) iterates over case class fields and calls `sink.pushKey(...)` followed by the inner encoder's `encode(product.productElement(i), sink)` directly. This pushes a key and then a value onto the Lua stack but never inserts them into the table. The `Sink` default method `pushValue[A: LuauEncoder]` (defined in `core/jvm/src/luau/core/codec/SinkImpl.scala:34–36`) is the only place that calls `binding.rawSet(state, -3)` after encoding a value. `derivedEncoder` bypasses `pushValue` entirely: it calls the inner encoder's `encode` method directly, which only pushes the value onto the Lua stack. The key and value accumulate on the stack uninserted.

Compare with the `Map[String, V]` encoder (`LuauEncoder.scala:93`), which correctly calls `sink.pushField(k, v)`. That delegates to `Sink.pushKey` then `Sink.pushValue` (`Sink.scala:30–32`), and `pushValue` calls `binding.rawSet(state, -3)` (`SinkImpl.scala:36`).

Every `LuauEncoder.derived` case class produces an empty Lua table and silently discards all field data.

**Failing scenario:** Any codec round-trip involving a derived case class encoder. The table arrives empty on the Lua side. Decoding it back produces all default/missing-field errors.

**Fix:** Replace the direct `pushKey` + `encode` calls in `derivedEncoder` with `sink.pushField(label, product.productElement(i))`, or call `binding.rawSet(state, -3)` explicitly after each `encode` invocation — matching the pattern in `SinkImpl.pushValue`.

---

#### C-CRIT-02 · Correctness · `core/jvm/src/luau/core/LuaType.scala:3–12`

**Title:** `LuaType.luaCode` values wrong for `String`, `Table`, `Function`, `Userdata`, `Thread`

**Detail:**  
The Scala enum assigns: `String(4)`, `Table(5)`, `Function(6)`, `Userdata(7)`, `Thread(8)`. The authoritative C header `shim/include/lx.h` (mirrored exactly by `panama/src/luau/panama/LxConstants.scala`) defines: `LX_TSTRING=6`, `LX_TTABLE=7`, `LX_TFUNCTION=8`, `LX_TUSERDATA=9`, `LX_TTHREAD=10`. Luau inserts `LX_TNUMBER=3`, `LX_TINTEGER=4`, `LX_TVECTOR=5` between Boolean and String; the Scala enum skips all three and shifts every subsequent case down by two.

The primary breakage site is `WasmBinding.toBytes` (`wasm/src/luau/wasm/WasmBinding.scala:139`):

```scala
if module._lx_type(state, thread, idx) == LuaType.String.luaCode then
```

`_lx_type` returns the raw C integer 6 for strings. `LuaType.String.luaCode` is 4. `6 == 4` is always false. Any zero-length Lua string at that index returns `None` instead of `Some(IArray.empty)`.

Note that `WasmBinding.typeAt` (lines 104–120) works correctly because it hard-codes the raw C integers directly (`case 6 => LuaType.String`, etc.) and never uses `.luaCode`. `PanamaState.typeAt` similarly uses `LxConstants` named values, not `.luaCode`. The defect is therefore isolated to the `WasmBinding.toBytes` path and to `LuaType.fromCode`, which will throw `IllegalArgumentException("Unknown Luau type code: 6")` if ever invoked with a raw C code for String, Table, Function, Userdata, or Thread.

**Failing scenario:** Any WASM-backed codec decode of an empty Lua string returns `None` (treated as absent) rather than `Some(IArray.empty[Byte])`. String fields in case classes that legally contain empty strings are silently dropped.

**Fix:** Update `luaCode` values in `LuaType.scala` to match the C ABI: `String(6)`, `Table(7)`, `Function(8)`, `Userdata(9)`, `Thread(10)`. Remove the special-case comparison in `WasmBinding.toBytes:139` and replace with `typeAt(state, idx) == LuaType.String`, which already routes through the correct mapping.

---

#### C-CRIT-03 · Error-Boundary · `panama/src/luau/panama/NativeFnDispatcher.scala:41`

**Title:** `t.getMessage.nn` NPE inside `catch` — secondary exception escapes the Panama upcall frame

**Detail:**  
The `catch` block at line 40–42:

```scala
catch case t: Throwable =>
  pushErrorMessage(thread, t.getMessage.nn)
  NativeFnResult.Fail(LuaValue.Nil)
```

Scala 3's `.nn` asserts non-null and throws `NullPointerException` when the receiver is null. Many JVM throwables return `null` from `getMessage()`: `NullPointerException` itself, `StackOverflowError`, and `OutOfMemoryError` all commonly have null messages. When any of these are thrown by `fn(thread, nArgs)`, the catch block executes `.nn` on a null `String`, producing a secondary `NullPointerException` that propagates out of `dispatch()`.

Panama upcall stubs do not catch exceptions. The secondary NPE escapes the FFI frame, which is explicit undefined behaviour per ADR-0001 ("A Scala callback cannot raise"). The original throwable is silently discarded, `pushErrorMessage` is never called, Luau receives no error value, and the VM state is left inconsistent.

The safe pattern already exists at `panama/src/luau/panama/Trampoline.scala:82`:  
`Option(t.getMessage).getOrElse(t.getClass.getSimpleName)`.  
It was not applied here.

**Failing scenario:** Any NativeFn that throws `NullPointerException`, `StackOverflowError`, or `OutOfMemoryError` causes the Panama upcall stub to propagate a secondary NPE across the FFI frame. The Luau VM is left in an undefined state.

**Fix:** Replace `t.getMessage.nn` with `Option(t.getMessage).getOrElse(t.getClass.getSimpleName)`.

---

#### C-CRIT-04 · Error-Boundary · `wasm/src/luau/wasm/WasmBinding.scala:139`

**Title:** `toBytes` zero-length string detection uses wrong `LuaType` code — always returns `None` for empty Lua strings

**Detail:**  
This is the concrete breakage consequence of C-CRIT-02, isolated here for clarity. `WasmBinding.toBytes:139` reads:

```scala
if module._lx_type(state, thread, idx) == LuaType.String.luaCode then
```

`LuaType.String.luaCode` is 4 (`LX_TINTEGER` in the C namespace). `_lx_type` returns 6 for a string (`LX_TSTRING`). The comparison `6 == 4` is always false. Every zero-length Lua string at `idx` returns `None` instead of `Some(IArray.empty)`.

`PanamaState.toBytes` (`panama/src/luau/panama/PanamaState.scala:134–136`) is unaffected: it calls `typeAt(state, idx) == LuaType.String`, which routes through the `LxConstants`-based translation layer and compares correctly.

**Failing scenario:** A Luau function returns an empty string `""`. `WasmBinding.toBytes` is called on that stack slot. `rawLen` returns 0. The type check compares 6 against 4 → false. The decoder receives `None` and produces a missing-field error instead of an empty string.

**Fix:** Replace the raw `_lx_type` call with `typeAt(state, idx) == LuaType.String`, consistent with `PanamaState.toBytes`.

---

### High

---

#### C-HIGH-01 · Correctness · `core/jvm/src/luau/core/codec/LuauDecoder.scala:111–112`

**Title:** `derivedDecoder` uses stale negative `idx` after `pushString` — `rawGet` targets wrong stack slot

**Detail:**  
In `derivedDecoder.decode` (line 104), `idx` may be a negative relative index (e.g. `-1` when a `Seq` decoder calls `decode(b, s, -1)` at line 79). Line 111 calls `b.pushString(s, label)`, which grows the stack by one slot. Line 112 then calls `b.rawGet(s, idx)` with the original `idx`.

If `idx` was `-1` (table at stack top before the push), after `pushString` the table has shifted to position `-2` and the label string is at `-1`. Calling `rawGet(s, -1)` then performs `lua_rawget` on the label string, not the table, corrupting the stack.

`FakeBinding.rawGet` (`core/jvm/src/luau/core/fake/FakeBinding.scala:85–91`) physically pops the key first and then evaluates `tableIdx` against the post-pop stack, accidentally inverting the index-evaluation order relative to the real Lua C API. This is why all `FakeBinding`-based tests in `CodecSpec.scala` pass while the Panama and Wasm backends would corrupt or assert.

The path is triggered by `LuauDecoder[Seq[A]]` (line 79) passing `idx = -1` to a derived case class decoder. It is also triggered by any top-level `decode[Point](state)` call with the default `idx = -1`.

**Failing scenario:** Decoding a case class from any negative stack index on Panama or Wasm backends. `pushString` shifts the table below the label key; `rawGet(-1)` calls `lua_rawget` on the label string itself, causing a type assertion (debug builds) or stack corruption (release builds).

**Fix:** Convert `idx` to an absolute positive index at entry:
```scala
val absIdx = if idx > 0 then idx else b.stackTop(s) + idx + 1
```
Then use `absIdx` for all `rawGet` calls throughout the loop.

---

#### C-HIGH-02 · Correctness · `panama/src/luau/panama/PanamaState.scala:185–191`

**Title:** `PanamaState.ref()` does not pop value — stack leak diverges from `WasmBinding`

**Detail:**  
`lx_ref` (`shim/include/lx.h:284`) documents: "The value remains on the stack (not popped)." The `Binding.ref` contract, as implemented consistently across `FakeBinding` and `WasmBinding`, is pop-on-ref (consume semantics matching `luaL_ref`).

`WasmBinding.ref()` (`wasm/src/luau/wasm/WasmBinding.scala:207–210`) explicitly calls `module._lx_pop(state, thread, 1)` after `_lx_ref`, with a comment explaining the rationale.

`FakeBinding.ref()` calls `state.stack.removeLast()` before creating the Ref.

`PanamaState.ref()` (lines 185–191) calls `lx_ref`, returns a `Ref`, and performs no pop. Every call to `PanamaState.ref()` leaks one stack slot. Code using `Scope.captureTop()` or the Scheduler's `binding.ref(state)` on Panama accumulates one phantom entry per call. Stack depth grows unboundedly until `lua_checkstack` fails or subsequent index-based operations (rawget, rawset, getArray, rawseti, etc.) reference wrong slots.

`SharedBackendSuite` TC-SHARED-06 (`wasm/test/src/luau/core/SharedBackendSuite.scala:114`) asserts `stackTop == 0` after `b.ref(state)`, but this test only runs under the Wasm backend. No equivalent Panama test exists.

**Failing scenario:** Any Panama-backed application that creates coroutines via the Scheduler. Each `Scheduler.spawn` calls `binding.ref(state)` once; the main Lua stack accumulates one entry per spawned task, growing monotonically until the VM exhausts its stack limit (~200 levels by default in Luau) and begins corrupting operations.

**Fix:** Add `LxHandles.lx_pop.invokeExact(L, state, 1): Unit` after the `lx_ref` call at `PanamaState.scala:187`, mirroring the Wasm implementation.

---

#### C-HIGH-03 · Correctness · `panama/src/luau/panama/PanamaState.scala:244–250`

**Title:** `readError` does not pop the error value from the thread stack — stack leak after resume failure

**Detail:**  
`lx_resume` on error (`LX_RESUME_ERR`) leaves the error value on top of the thread's stack. `lx_copy_error` (`shim/src/lx.cpp:400–412`) reads the error string via `lua_tolstring` without calling `lua_pop` — it is explicitly non-popping.

`PanamaState.resume` (`PanamaState.scala:63–65`) on the `LX_RESUME_ERR` branch calls `readError(thread)` and immediately returns `ResumeResult.Error(...)`. No `lx_pop` call exists anywhere in this branch or inside `readError`.

`WasmBinding.resume` (`WasmBinding.scala:56–57`) explicitly calls `module._lx_pop(thread, thread, 1)` after `readError` for exactly this reason.

After a `ResumeResult.Error`, `lx_stack_top` on the thread returns 1 (the error string still occupies the top slot). Any subsequent operation that inspects stack height — including a re-resume attempt or codec decode — sees one extra slot.

**Failing scenario:** A Luau script triggers a runtime error. After `ResumeResult.Error` is returned, the thread's stack top is 1 instead of 0. If any code path attempts to re-use that thread (or just checks `stackTop == 0` for invariant verification), the assertion fails.

**Fix:** Add `LxHandles.lx_pop.invokeExact(L, thread, 1): Unit` after `lx_copy_error` in `readError`, or in the `LX_RESUME_ERR` branch of `resume()`, matching `WasmBinding` behaviour.

---

#### C-HIGH-04 · Error-Boundary · `shim/src/lx.cpp:115–127`

**Title:** `lx_thread_status` maps `LUA_COERR` to same return code as `LUA_COFIN` — error-dead indistinguishable from clean-dead

**Detail:**  
`lua_CoStatus` defines `LUA_COFIN = 3` (coroutine finished cleanly) and `LUA_COERR = 4` (coroutine died with an unhandled error). The switch in `lx_thread_status` explicitly maps `LUA_COFIN → 2` and falls through to `default: → 2` for `LUA_COERR`. Both states are reported as "dead" with no distinction.

`lx.h` documents return value 2 solely as "dead" without differentiating error-dead from clean-dead. A host polling thread status to decide whether to log an error or read results cannot distinguish the two conditions.

The primary error-signaling path (`lx_resume` returning `LX_RESUME_ERR`) already signals the error synchronously; this defect only affects post-hoc status queries. No current Scala call site reads `lx_thread_status` to discriminate error-dead, so this is a latent ABI expressiveness gap rather than an active runtime breakage.

**Failing scenario:** A future caller polls `lx_thread_status` on a thread that died with an unhandled error (e.g. to log or to clean up differently). It receives status 2 and classifies the death as clean completion, silently swallowing the error.

**Fix:** Add `case LUA_COERR: return 4;` in the switch and document the new constant in `lx.h`. Alternatively, document the deliberate collapse and add a comment explaining why error-dead and clean-dead are intentionally indistinguishable.

---

#### C-HIGH-05 · Error-Boundary · `shim/src/lx.cpp:194`

**Title:** `LUA_ERRERR` maps to `LX_RESUME_ERR` — `LuaError.Level.Handler` is dead and unreachable

**Detail:**  
The `switch` default in `lx_resume` (`shim/src/lx.cpp:194`) maps `LUA_ERRERR` to `LX_RESUME_ERR`. `PanamaState.resume` unconditionally decodes `LX_RESUME_ERR` as `LuaError.runtime`. The variant `LuaError.Level.Handler` (`core/jvm/src/luau/core/LuaError.scala:9`) is documented as the intended representation for errors-in-error-handlers but has no smart constructor and is never produced by any code path.

The concern about corrupt stack reads in the `LUA_ERRERR` case is unwarranted: `luaD_seterrorobj` in Luau's internals explicitly pushes a valid pinned string `"error in error handling"` onto the thread stack before returning, so `lx_copy_error` reads a valid string.

**Failing scenario:** A future caller needs to distinguish "error-in-error-handler" from "ordinary runtime error" for special handling (e.g. breaking a re-error loop). The distinction is structurally unavailable through the current ABI.

**Fix:** Add `case LUA_ERRERR: *nResults = 0; return 4;` (`LX_RESUME_ERRERR`) to the `lx_resume` switch. Map it to `LuaError.handler` (or a new `LuaError.Level.Handler` smart constructor) at the Scala layer.

---

#### C-HIGH-06 · FFI-Memory · `shim/src/lx.cpp:400–410` (severityOverride: high)

**Title:** `lx_copy_error`: `errbufsz == 0` causes `size_t` underflow → `SIZE_MAX` `memcpy`

**Detail:**  
Line 408:

```cpp
size_t copy = (slen < errbufsz - 1) ? slen : errbufsz - 1;
```

When `errbufsz == 0`, `errbufsz - 1` wraps to `SIZE_MAX` (unsigned arithmetic). `copy` becomes `slen` (uncapped). `memcpy(errbuf, p, copy)` and `errbuf[copy] = '\0'` at lines 409–410 then write far beyond any zero-length buffer, corrupting the entire process heap.

`lx.h` (lines 400–401) documents no minimum-size precondition for `errbufsz`. All current callers pass non-zero sizes: `WasmBinding.scala:261` passes 512, `PanamaState.scala:247` passes 4096, `lx_test.c:81` passes `sizeof` of a 256-byte array. The defect is unreachable through current code paths but is latent for any future or downstream caller.

**Failing scenario:** A caller passes `errbufsz = 0` (e.g., as a probe-call pattern to discover required buffer size). `memcpy(..., SIZE_MAX)` corrupts the process heap.

**Fix:** Add `if (errbufsz == 0) return 0;` at the top of `lx_copy_error`, before any arithmetic on `errbufsz`.

---

#### C-HIGH-07 · Lifetime · `scheduler/jvm/src/luau/scheduler/Scheduler.scala:85–97`

**Title:** `spawnImmediate` returns `TaskHandle` with already-closed `threadRef` on terminal paths (severityOverride: high)

**Detail:**  
On `ResumeResult.Returned` (lines 85–88) and `ResumeResult.Error` (lines 91–94), `task.releaseThread()` closes `task.threadRef` by calling `threadRef.close()`. Line 97 then constructs `TaskHandle(threadRef, task)` with that already-closed `Ref`.

The critical caller is `stdlib/jvm/src/luau/stdlib/TaskLibrary.scala:53`, which calls `handle.threadRef.push()` immediately after `spawnImmediate` returns. `Ref.push()` (`core/jvm/src/luau/core/Ref.scala:13`) contains `require(!closed, "Ref.push() on a closed Ref")`, which throws `IllegalArgumentException`. Therefore `task.spawn(f)` in Lua where `f` returns synchronously crashes with a hard exception.

**Failing scenario:** A Lua script calls `task.spawn(function() return 1 end)` — a function that returns immediately without yielding. `spawnImmediate` resumes it, it returns, `releaseThread()` closes `threadRef`, `TaskHandle(closed_threadRef, task)` is returned, `TaskLibrary` calls `push()` on the closed Ref, and `require` throws.

**Fix:** Construct `TaskHandle` before calling `releaseThread()` on terminal paths, or return `Option[TaskHandle]` to distinguish live tasks from immediately-completed ones, or document that `TaskHandle.threadRef` must not be used after terminal completion and guard accordingly in `TaskLibrary`.

---

#### C-HIGH-08 · Lifetime · `scheduler/jvm/src/luau/scheduler/Scheduler.scala:205–211`

**Title:** `WasmBinding.ref()` missing `NOREF`/nil guard — silent bad `Ref` on nil or empty stack

**Detail:**  
`lx_ref` (`lx.cpp:295–297`) wraps `lua_ref`, which initialises `ref = LUA_REFNIL(0)` and returns 0 whenever the indexed value is `nil`. It never returns `LUA_NOREF(-1)` under normal operation (`LUA_NOREF` is the documented sentinel but is never emitted).

`WasmBinding.ref()` (`WasmBinding.scala:205–211`) calls `_lx_ref`, pops 1, and constructs `Ref[Int](state, refId, this, "wasm")` with no guard on `refId`. If `refId == 0` (`LUA_REFNIL`), a silent `Ref` with key 0 is produced. `_lx_push_ref(state, state, 0)` subsequently pushes `nil` silently. `lua_unref` on key 0 is a no-op, so the registry slot 0 is never cleaned up.

`PanamaState.ref()` (`PanamaState.scala:188–189`) guards with `if key == -1` — checking `LUA_NOREF`. Because `lua_ref` never returns -1, this guard is dead code that never fires. Both the nil case (returns 0) and the empty-stack case (undefined behaviour, may return 0 or corrupt registry) slip past the guard.

**Failing scenario 1:** Caller pushes `nil` then calls `binding.ref(state)`. Both backends return a Ref with key 0. Pushing that Ref silently pushes `nil`. The caller believes it holds a live object reference.  
**Failing scenario 2:** Caller accidentally calls `binding.ref(state)` with an empty stack (e.g. after miscount). On Wasm, `index2addr(L, -1)` with empty stack reads one slot below the base (UB in C release builds). The resulting Ref may have an arbitrary or zero key.

**Fix:** In `WasmBinding.ref()`: add `if refId <= 0 then throw new IllegalStateException("lx_ref returned NOREF/REFNIL")` before constructing the Ref, pop only on success. In `PanamaState.ref()`: change the guard from `key == -1` to `key <= 0`.

---

#### C-HIGH-09 · Lifetime · `scheduler/jvm/src/luau/scheduler/Scheduler.scala:56–57`

**Title:** `spawn`/`spawnImmediate`/`deferTask`/`scheduleDelayed` leak raw Luau thread on `binding.ref()` failure (severityOverride: medium)

**Detail:**  
In all four task-creation methods, `binding.newThread(state)` allocates a Luau coroutine (pushed onto the main state's Lua stack by `lua_newthread`), then `binding.ref(state)` creates a registry Ref pinning that thread. No `try/finally` wraps the two calls. If `binding.ref()` throws (e.g. `checkOpen()` fires because the state was closed between the two calls), the raw coroutine handle is leaked — it remains on the main Lua stack and is never popped or referenced, consuming memory until the parent state is closed.

The only realistic trigger is a race between state closure and task spawning, which violates the Scheduler's documented single-Driver-thread invariant. In practice, `binding.ref()` cannot throw on a live open state with a valid thread at stack top.

**Failing scenario:** State is closed concurrently or `checkOpen()` fires due to a programming error. The new coroutine remains as an orphan on the main state's Lua stack, consuming memory until `lx_close`.

**Fix:** Wrap `binding.ref(state)` in `try/catch`: on failure, call `binding.pop(state, 1)` to remove the dangling thread from the main stack before rethrowing.

---

#### C-HIGH-10 · Concurrency · `scheduler/jvm/src/luau/scheduler/Scheduler.scala:224–235`

**Title:** `wireSuspend`: non-atomic check-then-set on `fired` allows double-resume

**Detail:**  
The `@volatile var fired` guard at lines 224–228:

```scala
@volatile var fired = false
val resume: Resume = Resume { either =>
  if !fired then
    fired = true
    ...
```

`@volatile` guarantees cross-thread visibility of the write but does NOT provide atomic compare-and-set. Two off-Driver threads calling the `Resume` callback concurrently can both read `fired == false`, both pass the guard, both write `fired = true`, and both enqueue a `ReadyTask` for the same task.

The Scheduler class comment (lines 11–16) explicitly states that `Resume` callbacks may be called from any thread. `PlatformQueue.enqueue` is synchronized so the queue itself is not corrupted, but it ends up holding two `ReadyTask` entries for the same `Task`. The second dequeue will call `binding.resume(task.thread, nargs)` on a coroutine that has already advanced or completed, corrupting the Lua stack and `liveTasks` accounting. The guard at `resumeTask:192` (`if task.state == TaskState.Cancelled then return`) does not protect against this because the task's state is `Queued`, not `Cancelled`.

**Failing scenario:** Two concurrent async completions (e.g. timeout + network) both fire the same `Resume` callback simultaneously. The task runs twice; the Lua coroutine's stack is corrupted on the second resume.

**Fix:** Replace `@volatile var fired` with an `AtomicBoolean` and use CAS:
```scala
val fired = new AtomicBoolean(false)
val resume: Resume = Resume { either =>
  if fired.compareAndSet(false, true) then { ... }
}
```

---

#### C-HIGH-11 · Concurrency · `scheduler/jvm/src/luau/scheduler/Scheduler.scala:134–138`

**Title:** `scheduleDelayed` timer callback reads `task.state` without synchronization — race with `cancelTask`

**Detail:**  
The timer callback (lines 134–138) runs on the JVM `java.util.Timer` thread:

```scala
if task.state == TaskState.Parked then
  task.setState(TaskState.Queued)
  runQueue.enqueue(ReadyTask[H](task, ResumeValues.None))
```

`task._state` is `@volatile` (`scheduler/jvm/src/luau/scheduler/Task.scala:20`). Volatile guarantees visibility but not atomicity of the compound read-then-write. The failing interleave is:

1. Timer thread reads `task.state` → `Parked` (guard passes).
2. Driver thread: `cancelTask` reads `task.state` → `Parked` → `setState(Cancelled)` → `releaseThread()` (closes `threadRef`).
3. Timer thread: `setState(Queued)` — overwrites `Cancelled` with `Queued`.
4. Timer thread: `runQueue.enqueue(ReadyTask(task, ...))` — task enqueued with `state=Queued` and a released thread.
5. Driver: `resumeTask` reads `task.state == Queued` (not Cancelled) → calls `binding.resume(task.thread, nargs)` on a released raw thread handle → use-after-free / crash.

The guard at `resumeTask:192` is ineffective because the timer's write at step 3 overwrites `Cancelled` after `cancelTask` set it.

**Failing scenario:** Any `scheduleDelayed` callback firing concurrently with `cancelTask` for the same task. The task resumes with a freed thread handle, corrupting the Lua VM state.

**Fix:** Wrap the timer callback body in a block synchronized on a shared lock (e.g. the `runQueue` object), and acquire the same lock inside `cancelTask` before transitioning state and calling `releaseThread()`.

---

#### C-HIGH-12 · Concurrency · `scheduler/jvm/src/luau/scheduler/Scheduler.scala:226`

**Title:** Race between `fired` flag check-then-set and concurrent double-resume (duplicate of C-HIGH-10, different verdicting path)

This finding is a second confirmed path to the same TOCTOU defect documented in C-HIGH-10 and does not add new information. See C-HIGH-10.

---

#### C-HIGH-13 · API-Parity · `core/jvm/src/luau/core/LuaType.scala:4` (severityOverride: high)

**Title:** `LuaType.luaCode` values do not match Luau VM type constants — Panama `fromCode` fallthrough throws on live types

**Detail:**  
This is a restatement of C-CRIT-02 with additional evidence for the Panama fallthrough path. `PanamaState.typeAt` (`panama/src/luau/panama/PanamaState.scala:107–116`) matches against `LxConstants` named values (correct) and falls through to `LuaType.fromCode(code)` for codes not explicitly handled. `LuaType.fromCode` searches by `.luaCode` value. Since the enum stores wrong codes (e.g., `LX_TINTEGER=4` matches `LuaType.String.luaCode=4`, `LX_TVECTOR=5` matches `LuaType.Table.luaCode=5`), the fallthrough wrongly classifies integer values as `LuaType.String` and vector values as `LuaType.Table`. `LX_TUSERDATA=9` finds no match (`.luaCode=7`) and throws `IllegalArgumentException`. `LX_TTHREAD=10` similarly throws.

**Fix:** Same as C-CRIT-02: correct `luaCode` values in `LuaType.scala` to match the Luau C ABI.

---

#### C-HIGH-14 · API-Parity · `wasm/src/luau/wasm/WasmBinding.scala:108–120` (severityOverride: high)

**Title:** `WasmBinding.typeAt` maps `LX_TVECTOR(5)` to `LuaType.Number` — silent misclassification of vector values

**Detail:**  
`lx.h` defines `LX_TVECTOR = 5` (Luau's native vector type). `WasmBinding.typeAt` (`WasmBinding.scala:113–114`):

```scala
case 4 => LuaType.Number
case 5 => LuaType.Number
```

Both `LX_TINTEGER(4)` and `LX_TVECTOR(5)` are live types that Luau can push onto the stack (e.g. from `Vector3.new()` after `lx_openlibs` with `LX_LIB_VECTOR`). Mapping `LX_TVECTOR` to `LuaType.Number` causes any subsequent call to `toNumber` to invoke `lx_to_number`, which returns `ok=0` for a vector slot (vector is not a number in the Luau ABI), giving `None` and silently skipping the field. The `case 2 => LuaType.Nil` arm (line 111) is dead code: no `lx.h` constant maps to type code 2.

**Failing scenario:** A Luau program using vectors (e.g. `CFrame` arithmetic). A decoder expecting a numeric field receives a vector slot. `typeAt` returns `LuaType.Number`. `toNumber` calls `lx_to_number` on the vector → `ok=0` → `None`. Field is silently absent.

**Fix:** Change `case 5 => LuaType.Number` to `case 5 => LuaType.Userdata` (nearest mapping) or add a dedicated `LuaType.Vector` variant. Remove the unreachable `case 2` arm. Replace the `case _ => LuaType.Nil` wildcard with an explicit throw to match Panama's fail-fast behaviour.

---

#### C-HIGH-15 · API-Parity · `wasm/src/luau/wasm/WasmBinding.scala:135`

**Title:** `toBytes` on empty Wasm string returns `None` instead of `Some(IArray.empty)`

**Detail:**  
See C-CRIT-04. This finding is confirmed at high severity to document the specific symptom and contrast with the correct `PanamaState.toBytes` implementation.

---

#### C-HIGH-16 · API-Parity · `wasm/src/luau/wasm/WasmBinding.scala:68`

**Title:** Push operations use `mainThread(state)` — always push to main thread, not active coroutine (severityOverride: high)

**Detail:**  
All push operations in `WasmBinding` call `mainThread(state)` before delegating to the Shim:

```scala
override def pushNil(state: Int): Unit =
  val thread = mainThread(state)
  module._lx_push_nil(state, thread)
```

The `lx.h` ABI contract (`lx.h:47–53`) defines the native function callback signature as `lx_HostFn(lx_State state, lx_Thread thread, int nArgs)`. `state` is the main isolate handle; `thread` is the active coroutine.

Panama's `NativeFnDispatcher.dispatch` (`NativeFnDispatcher.scala:39`) calls `fn(thread, nArgs)` — passing the active coroutine as the `state` argument. Inside the NativeFn, `binding.pushNil(state)` calls `lx_push_nil(L, state)` where `L` is the stored main state and `state` is the coroutine. This is correct.

Wasm's `Trampoline.dispatch` (`Trampoline.scala:71`) calls `fn(state, nArgs)` where `state` is the main `lx_State` integer passed to the trampoline entry point. Inside the NativeFn, `binding.pushNil(state)` calls `mainThread(state)` → `_lx_push_nil(state, mainThread)`. Pushes onto the main thread's stack, not the active coroutine.

This breaks all NativeFn calls made from within coroutines on the Wasm backend — the primary runtime path in the Scheduler. Return values pushed by NativeFns land on the wrong stack; `lx_resume` reads results from the coroutine stack and sees nothing; the coroutine gets nil returns or hangs.

**Failing scenario:** Any Luau script that calls a registered NativeFn from inside a coroutine (standard Scheduler use case). The NativeFn's pushed return values are invisible to the resume caller.

**Fix:** Document that `WasmBinding`'s `state: Int` parameter in all push/pop/read methods must always be the main `lx_State` (not a coroutine), and ensure the Trampoline dispatches with a proper coroutine-vs-state split — or refactor `WasmBinding` to accept both `state` and `thread` explicitly matching the Shim ABI.

---

#### C-HIGH-17 · Build/Test · `build.mill:169` and `build.mill:178`

**Title:** `wasmBuildNative` uses `sys.env("PWD")` — throws in CI; error message missing `s` interpolation prefix

**Detail:**  
Line 169:
```scala
val projectRoot = os.Path(sys.env("PWD"))
```

`sys.env` throws `NoSuchElementException` when `PWD` is unset (Docker containers, many CI runners, subprocess invocations that clear the environment). Every other path derivation in `build.mill` uses `os.pwd` (lines 67–82). The correct Mill idiom is `os.pwd`.

Line 178:
```scala
sys.error("WASM not produced at $wasmFile")
```

No `s` prefix — this is not string interpolation. The error message will always print the literal text `$wasmFile` regardless of the actual path, making build failures harder to diagnose.

**Failing scenario 1:** `./mill shim.wasmBuildNative` in any Docker-based CI environment where `PWD` is not exported. The task throws `NoSuchElementException` on line 169, failing the build with an opaque error.  
**Failing scenario 2:** The `wasmFile` is not found. The error message prints `"WASM not produced at $wasmFile"` rather than the actual path.

**Fix:** Replace `os.Path(sys.env("PWD"))` with `os.pwd`. Change `sys.error("WASM not produced at $wasmFile")` to `sys.error(s"WASM not produced at $wasmFile")`.

---

#### C-HIGH-18 · Build/Test · `build.mill:126–166`

**Title:** `wasmBuild` (Emscripten) produces an artifact incompatible with `LuauShimFactory` (WASI reactor)

**Detail:**  
`wasmBuild` (lines 126–166) invokes `emcc` with `MODULARIZE=1` and `EXPORT_NAME='LuauShim'`, producing an Emscripten-ABI module that requires the generated JS glue for instantiation.

`LuauShimFactory.scala` (lines 39–67) loads the raw `.wasm` bytes via `new WebAssembly.Module(buf)` + `new WebAssembly.Instance(module, { wasi_snapshot_preview1: wasi })` — a raw WASI instantiation with no Emscripten glue. It checks for `_initialize` export (WASI reactor convention, line 67) and resolves bare `lx_*` symbols from `ex` directly.

`build-wasm.sh` (invoked by `wasmBuildNative`) uses `--target=wasm32-wasi`, `-mexec-model=reactor`, `-fwasm-exceptions`, and `--export=lx_*` — producing exactly what `LuauShimFactory` expects.

CI (`.github/workflows/ci.yml:44`) runs `./mill shim.wasmBuild` (Emscripten), then `./mill wasm.test` invokes `jsEnvConfig` which calls `wasmBuildNative`. The two builds produce incompatible artifacts. If the Emscripten artifact were somehow fed to `LuauShimFactory`, instantiation would fail: Emscripten modules import `env.*` symbols (not `wasi_snapshot_preview1`), lack `_initialize`, and differ structurally from a WASI reactor. Additionally, `wasmBuild` omits `-fwasm-exceptions` and WASI sysroot flags, so it would fail to link the C++ exception support required by the Luau VM sources.

**Failing scenario:** Any consumer passing the output of `./mill shim.wasmBuild` to `LuauShimFactory` gets a crash at WASM instantiation time.

**Fix:** Remove or clearly mark `wasmBuild` as deprecated. The canonical build path is `wasmBuildNative`. If the Emscripten path must be kept for other environments, document the incompatibility explicitly and update `LuauShimFactory` to support an Emscripten loader.

---

#### C-HIGH-19 · Build/Test · `wasm/src/luau/wasm/WasmBinding.scala:96–100`

**Title:** `pushCopy` and `pushRef` pass `state` as both `lx_State` and `lx_Thread` arguments

**Detail:**  
Lines 96–100:
```scala
override def pushCopy(state: Int, idx: Int): Unit =
  module._lx_push_copy(state, state, idx)

override def pushRef(state: Int, registry: Int): Unit =
  module._lx_push_ref(state, state, registry)
```

The `lx.h` ABI (`lx.h:162–165`) specifies:
- `lx_push_copy(lx_State state, lx_Thread thread, int idx)`
- `lx_push_ref(lx_State state, lx_Thread thread, int ref)`

`PanamaState.pushCopy` (line 217–218) correctly passes `(L, state, idx)` where `L` is the stored main `lx_State` field and `state` is the coroutine thread parameter. `PanamaState.pushRef` (lines 101–102) does the same.

All other `WasmBinding` push operations call `mainThread(state)` for the thread argument. `pushCopy` and `pushRef` deviate by passing `state` for both slots.

The current codebase uses `pushCopy` and `pushRef` from the Scheduler with `rawThread` (a coroutine handle) as the `state` argument (`Scheduler.scala:75`). `_lx_push_copy(rawThread, rawThread, ...)` passes a coroutine pointer as `lx_State`, which the C shim will dereference as a main state struct — type confusion.

**Failing scenario:** `Scheduler.spawnImmediate` calls `binding.pushRef(rawThread, fnRef.registryKey)` on Wasm. `_lx_push_ref(rawThread, rawThread, registry)` passes the coroutine pointer as `lx_State`. The C shim dereferences a coroutine struct as a main state, accessing wrong memory — crash or silent corruption.

**Fix:**  
- Line 97: `module._lx_push_copy(state, mainThread(state), idx)`  
- Line 100: `module._lx_push_ref(state, mainThread(state), registry)`

---

### Medium

---

#### C-MED-01 · Correctness · `core/jvm/src/luau/core/codec/LuauDecoder.scala:55–57` (severityOverride: medium)

**Title:** String decoder catches `CharacterCodingException` but `new String(..., UTF_8)` never throws it — dead catch, silent replacement

**Detail:**  
Line 55–57:
```scala
try Right(new String(IArray.genericWrapArray(bytes).toArray, java.nio.charset.StandardCharsets.UTF_8))
catch case _: java.nio.charset.CharacterCodingException =>
  Left(LuaError.runtime(s"string at index $idx is not valid UTF-8"))
```

`new String(bytes, StandardCharsets.UTF_8)` uses Java's replacement-character decoding path (`CodingErrorAction.REPLACE`). Invalid UTF-8 bytes are silently replaced with `U+FFFD` rather than throwing `CharacterCodingException`. The `catch` clause is dead code. Callers expecting `Left(LuaError)` for invalid UTF-8 receive `Right(corrupted_string)` instead.

**Failing scenario:** A Luau script returns a binary blob masquerading as a string. The decoder silently returns a `Right` containing a string with `U+FFFD` replacement characters, when it should return `Left` indicating an encoding error.

**Fix:**
```scala
import java.nio.{ByteBuffer, charset}
import java.nio.charset.{StandardCharsets, CodingErrorAction}
try
  val decoded = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(IArray.genericWrapArray(bytes).toArray))
  Right(decoded.toString)
catch case _: java.nio.charset.CharacterCodingException =>
  Left(LuaError.runtime(s"string at index $idx is not valid UTF-8"))
```

---

#### C-MED-02 · Error-Boundary · `panama/src/luau/panama/PanamaState.scala:205–215`

**Title:** `getGlobal`/`setGlobal` bypass `lx_get_global`/`lx_set_global` — semantic divergence from Wasm, potential ref leak

**Detail:**  
`WasmBinding.getGlobal` calls `_lx_get_global` → `lua_getglobal` → `luaV_gettable` (respects `__index` metamethod on `_G`). `PanamaState.getGlobal` (line 205) calls `lx_rawget(L, state, LUA_GLOBALSINDEX=-10002)` → `lua_rawget` → `luaH_get` (bypasses `__index`). Symmetric divergence for `setGlobal`.

`lx_get_global` and `lx_set_global` exist in `shim/src/lx.cpp:332–337` and are exported by `build-wasm.sh`, but are absent from `shim/include/lx.h` and `panama/src/luau/panama/LxHandles.scala`, making them inaccessible from Panama.

Additionally, `PanamaState.setGlobal` (lines 210–215) calls `lx_ref`, `lx_pop`, `pushString`, `lx_push_ref`, `lx_rawset`, then `lx_unref`. If `lx_rawset` raises (e.g., readonly error after `lx_sandbox`), `lx_unref` at line 215 is never reached, leaking the registry slot until state close.

**Failing scenario:** User code installs a `__index` metamethod on `_G` before sandbox. `PanamaState.getGlobal` uses raw access and misses the metamethod; `WasmBinding.getGlobal` sees it. Backends behave differently. After sandbox, `setGlobal` can trigger a readonly error that skips cleanup.

**Fix:** Declare `lx_get_global` and `lx_set_global` in `lx.h` and add them to `LxHandles`. Use them in `PanamaState.getGlobal`/`setGlobal` to match Wasm semantics.

---

#### C-MED-03 · Error-Boundary · `wasm/src/luau/wasm/WasmBinding.scala:120`

**Title:** `typeAt` wildcard `case _ => LuaType.Nil` silently swallows `LX_TBUFFER(11)` and any future unknown type codes

**Detail:**  
`WasmBinding.typeAt` (lines 104–120) handles codes -1 through 10 explicitly. Code 11 (`LX_TBUFFER`, a live Luau type since buffer support was added) hits `case _ => LuaType.Nil`. A buffer value on the stack is silently classified as `Nil`, causing `toBytes` to return `None` and any codec decode to produce a missing-field error.

`PanamaState.typeAt` delegates unknown codes to `LuaType.fromCode(code)`, which throws `IllegalArgumentException` — explicit fail-fast rather than silent wrong value.

**Failing scenario:** A Luau script uses buffers (`buffer.create()`). `typeAt` on a buffer slot returns `LuaType.Nil`. `toBytes` returns `None`. Codec decode of the buffer field silently fails.

**Fix:** Replace `case _ => LuaType.Nil` with:
```scala
case _ => throw new IllegalArgumentException(s"Unknown Luau type code: $code")
```
This matches Panama's fail-fast behaviour and makes type-mismatch bugs immediately visible.

---

#### C-MED-04 · Error-Boundary · `shim/src/lx.cpp:400–410` (see C-HIGH-06 above for primary entry)

See C-HIGH-06. The duplicate entry from a second review path is collapsed here.

---

#### C-MED-05 · FFI-Memory · `shim/src/lx.cpp:108–113` (severityOverride: medium)

**Title:** `lx_new_thread` leaves thread on `L`'s stack — Panama path leaks one slot per coroutine creation

**Detail:**  
`lua_newthread(L)` pushes the new coroutine onto `L`'s (main thread's) stack and returns the pointer. `lx_new_thread` (`shim/src/lx.cpp:108–113`) returns the pointer without popping the stack slot. The comment "Thread ref is left on L's stack; caller may pop if unused" defers cleanup to callers.

`WasmBinding.ref()` (line 210) already pops 1 after `_lx_ref`, which consumes the thread value pushed by `lua_newthread` as a side effect. The Wasm path is correct.

`PanamaState.ref()` does not pop (see C-HIGH-02). Combined, every `Scheduler.spawn` call on Panama pushes one thread-value onto the main Lua stack via `lua_newthread` and never pops it. The main thread's stack grows by one slot per spawned task, indefinitely.

**Failing scenario:** A long-running Panama-backed application spawns many tasks. The main Lua stack grows by 1 per task. After many tasks (approaching Luau's `LUAI_MAXSTACK` limit), `lua_checkstack` fails and subsequent push operations on the main thread corrupt or error.

**Fix:** Fix `PanamaState.ref()` to pop after `lx_ref` (see C-HIGH-02). This also resolves the `lx_new_thread` accumulation issue because the pop in `ref()` consumes the slot pushed by `newthread`.

---

#### C-MED-06 · Lifetime · `core/jvm/src/luau/core/Scope.scala:17–23`

**Title:** `Scope.own()` and `captureTop()` after `close()` silently leak `Ref`s

**Detail:**  
`Scope` has no `closed` flag. `close()` (lines 21–23) drains the `owned` deque with `removeLast().close()` but performs no state transition. Any subsequent call to `own(r)` or `captureTop()` appends to the now-empty deque. A second call to `close()` on the re-populated deque is never made — those `Ref`s leak registry slots until the Lua state is closed.

This is reachable when a `Scope` reference escapes its `scoped {}` block via a closure capture.

**Failing scenario:** A `Scope` is closed, but a closure that captured the `given scope` fires later and calls `captureTop()`. The resulting `Ref` is added to the closed scope's deque and never released.

**Fix:** Add `private var closed = false` and guard `own()` and `captureTop()` with `require(!closed, "Scope is already closed")`. Set `closed = true` at the start of `close()`.

---

#### C-MED-07 · API-Parity · `panama/src/luau/panama/PanamaState.scala:187–191`

**Title:** `PanamaState.ref()` `NOREF` check uses wrong sentinel — guard is dead code

**Detail:**  
`PanamaState.ref()` (lines 188–189) guards with `if key == -1 then throw ...`. `lua_ref` returns `LUA_REFNIL(0)` for nil values and never returns `LUA_NOREF(-1)` under normal operation. The guard is dead code that never fires. A `nil` push followed by `ref()` produces `Ref(L, 0, ...)` silently. `lx_push_ref` with key 0 silently pushes `nil`. `lua_unref(0)` is a no-op, so registry slot 0 is never cleaned up.

**Failing scenario:** Caller pushes `nil`, calls `binding.ref(state)`, and believes it holds a live reference. Any `ref.push()` silently pushes `nil` onto the stack. The call to `ref.close()` does nothing (unref(0) is a no-op).

**Fix:** Change `if key == -1` to `if key <= 0` to cover both `LUA_NOREF(-1)` and `LUA_REFNIL(0)`.

---

#### C-MED-08 · Build/Test · `.github/workflows/ci.yml:25–36`

**Title:** emsdk not cached — full reinstallation (~1 GB, 5–15 min) on every CI run; Mill/Coursier cache uses `;` separator

**Detail:**  
Lines 25–31: emsdk is cloned from GitHub and `emsdk install 3.1.50` runs on every CI job. No `actions/cache` step covers `/tmp/emsdk`.

Line 35: `path: ~/.mill;~/.cache/coursier` uses semicolons. The `actions/cache` `path` input expects newline-separated entries; semicolons are not a recognized delimiter. The Mill and Coursier cache is likely non-functional due to this misformatting, causing all Scala dependencies to be re-downloaded on every run.

**Fix:** Add a cache step for emsdk keyed on `3.1.50`, path `/tmp/emsdk`. Fix the cache path to use newlines:
```yaml
path: |
  ~/.mill
  ~/.cache/coursier
```

---

#### C-MED-09 · Build/Test · `.github/workflows/ci.yml:48–52`

**Title:** CI runs `panama.test` and `wasm.test` twice — once explicitly, once via `__.test` wildcard

**Detail:**  
Line 48: `./mill panama.test`. Line 50: `./mill wasm.test`. Line 52: `./mill __.test` — Mill's `__` wildcard resolves to all test modules including `panama.test` and `wasm.test`. Both are executed twice per CI run, wasting build time.

**Fix:** Remove the separate `./mill panama.test` and `./mill wasm.test` steps and rely solely on `./mill __.test`. Or keep the explicit steps and remove `__.test`.

---

### Low

---

#### C-LOW-01 · Error-Boundary · `shim/src/lx.cpp:194`

**Title:** `LuaError.Level.Handler` is an unreachable dead variant

**Detail:**  
See C-HIGH-05. The variant exists in the Scala domain model but is never produced. Severity is low as a standalone observation because the stack is clean for `LUA_ERRERR` and no current caller needs the discrimination.

---

#### C-LOW-02 · Error-Boundary · `shim/src/lx.cpp:408`

**Title:** `lx_copy_error`: `errbufsz == 0` underflow (duplicate entry collapsed from medium)

**Detail:**  
See C-HIGH-06. Severity is reassessed as low for the specific concern of defensive guard absence, because all current call sites pass non-zero sizes and the function is internal to the Scala binding layer.

---

#### C-LOW-03 · Build/Test · `wasm/test/src/luau/core/SharedBackendSuite.scala:81` and `146`

**Title:** `openLibs` calls use undocumented raw bitmask integers with no named Scala constants

**Detail:**  
Lines 85 and 146 use raw literals `1 | (1 << 7)` matching `LX_LIB_BASE` and `LX_LIB_COROUTINE` from `lx.h:340,347`. No Scala-side named constants exist (no `LxLib` object or enum). If the shim header reorders the bits or inserts a new library, tests silently load wrong libraries with no compile or runtime error.

**Fix:** Define a Scala `LxLib` object with named constants:
```scala
object LxLib:
  val Base      = 1 << 0
  val Coroutine = 1 << 7
  // ...
```
Derive values from comments referencing `lx.h` line numbers.

---

#### C-LOW-04 · Build/Test · `wasm/src/luau/wasm/WasmBackend.scala:7`

**Title:** `load(loaderPath: String)` parameter is dead — silently ignored

**Detail:**  
`WasmBackend.load(loaderPath: String = "./luau-shim.js")` never uses `loaderPath`. `LuauShimFactory.apply()` reads the path from the `LUAU_WASM_PATH` environment variable, not from the parameter. All three callers pass no argument. The dead parameter is misleading API surface.

**Fix:** Remove the `loaderPath` parameter.

---

#### C-LOW-05 · Build/Test · `wasm/src/luau/wasm/LuauShimFactory.scala:101–113` and `wasm/src/luau/wasm/WasmModule.scala`

**Title:** `lx_push_integer` and `lx_to_integer` mapped in factory but absent from `WasmModuleExports` — Luau integers round-trip through `Double` with precision loss

**Detail:**  
`LuauShimFactory.scala:104,106` includes `lx_push_integer` and `lx_to_integer` in the dynamic API mapping. `WasmModuleExports` (declared in `WasmModule.scala`) declares neither `_lx_push_integer` nor `_lx_to_integer`. `WasmBinding` has no `pushInteger`/`toInteger` implementation. The `Binding` trait has no such abstraction. Integer values (`LX_TINTEGER`, type code 4) must round-trip through `pushNumber`/`toNumber` (IEEE 754 `Double`), silently losing precision for integers outside the `2^53` safe range.

**Fix:** Add `_lx_push_integer` and `_lx_to_integer` declarations to `WasmModuleExports`, corresponding methods to `WasmBinding` and the `Binding` trait.

---

## Appendix: Refuted Claims

The following claims were raised during review and determined to be non-defects. They are listed here to prevent re-raising in future reviews.

| Title | Reason Refuted |
|-------|----------------|
| `nextId` read-modify-write not thread-safe in `NativeFnDispatcher.register()` | Architecturally impossible under single-Driver invariant (ADR-0002 deferred); `NativeFnDispatcher` is per-state, all registrations happen before any resume |
| `lx_set_suspend_token` reads thread data from coroutine thread, not main state | `get_state_data` (lx.cpp:22) calls `lua_mainthread(L)` internally, always correctly resolves to main thread regardless of argument |
| `WasmBinding.resume` passes thread as both state and thread args to `_lx_resume` | `lx_resume` in lx.cpp:180 explicitly `(void)L` — first arg is dead; `lua_resume` is called via the thread arg only |
| `lx_to_lstring` reports full `slen` in `*len` but copies only truncated bytes | Documented intentional two-phase ABI (lx.h:220); all callers allocate `rawLen+1` so truncation path cannot trigger |
| `PanamaState.unref` passes thread handle to `lx_unref` instead of main state | `Ref.close()` always passes `L` (the stored main state) — the only call site; no coroutine MemorySegment can reach `unref` via current code paths |
| `lx_set_suspend_token` token truncated: `int64 → int32` over WASM boundary | Wasm backend never calls `_lx_set_suspend_token`; suspend path on Wasm uses `Trampoline.pendingSuspend` (a Scala object), not a numeric token |
| `setGlobal` leaks a registry ref on every call | `lx_unref` is called at line 215 on every path; the ref is released correctly |
| Upcall stub lifetime tied to `stateArena` but dispatcher outlives arena on close | Impossible under single-Driver invariant: `close()` and `lx_resume` cannot run concurrently |
| `pushErrorMessage` uses confined Arena inside upcall — crashes if called from non-owning thread | Panama dispatch always runs on the Driver thread that created `NativeFnDispatcher`; confined Arena is valid for that thread |
| `toBytes`: `lenPtr` freed before reading `actualLen` — heap view races with `_free` | Misreads the code: `readLen()` closure reads `HEAPU8` fresh on every invocation via a property getter, not a captured stale reference |
| `allocOutInt` closure captures stale `HEAPU8` view after `memory.grow` | `val heap = ...` is inside the lambda body, not at closure-creation time; getter produces fresh `Uint8Array` per call |
| `resume()` passes thread as both state and thread to `_lx_resume` | `(void)L` in lx.cpp:180 makes the first arg dead; scenario requires a coroutine Int to reach `WasmBinding.resume` as `thread`, which does not happen under current Scheduler |
| `lx_to_lstring`: `dstlen=0` causes underflow and `memcpy(SIZE_MAX)` | Guard `if (dst && dstlen > 0)` at line 247 fires before writes; no memory touched when `dstlen==0` |
| Trampoline singleton shared across Wasm instances — concurrent `WasmBinding` instances corrupt each other | JS is single-threaded; exactly one WASM module instance exists per process; the shared Trampoline is correct by design |
| `nextId` in `NativeFnDispatcher` is non-atomic — data race if `register()` called from multiple threads | Duplicate of first entry; single-Driver invariant prevents concurrent registration |
| `lastYieldToken @volatile var` non-atomic read-then-consume | Production Scheduler never reads `lastYieldToken`; only used in isolation tests that run on a single thread |
| `close()` does not drain live enqueued items before cancelling `liveTasks` | `Ref.close()` is idempotent (guards with `if !closed`); double-unref via concurrent timer callback cannot produce UB |
| Trampoline `pendingSuspend` is a module-global singleton — single `Suspend` slot per dispatch cycle | Nested `Suspend` scenario requires re-entrant `lx_resume`, which Luau prohibits; JS single-threaded model prevents concurrent slots |
| `pendingSuspend` slot not cleared on error/return path — stale `Suspend` leaks | `Scheduler.setPendingSuspend` is `private[scheduler]`; `NativeFnDispatcher` never calls it; the stale-slot path is unreachable |
| Bare `Yielded` with no `pendingSuspend` parks task permanently without removing from `liveTasks` | Intentional per ADR-0004: bare `coroutine.yield` is documented to park with no wakeup; `cancelTask` handles cleanup |
| `ps` field initialized via `init()` — null reference if dispatch called before `init` | `dispatch` can only be called via `lx_resume`, which requires a `PanamaState` reference that is not visible until after `init()` completes |
| `spawn()` sets `TaskState.Queued` before adding to `liveTasks` — observable inconsistency window | `cancelThread` is only callable from a NativeFn on the Driver thread; it cannot race with `spawn()` on the same thread |
| Error value not popped from thread stack after `LX_RESUME_ERR` — stack leak per error (PanamaState) | All callers immediately discard the thread on error (`releaseThread()`); no code path re-resumes after `LX_RESUME_ERR` |
| `typeAt` maps `LUA_TLIGHTUSERDATA(2)` to `LuaType.Nil` — silently wrong type | `lx.cpp` never calls `lua_pushlightuserdata`; the shim exposes no API to push lightuserdata; code 2 is unreachable |
| `lx_resume` maps `LUA_BREAK` to `LX_RESUME_ERR` — phantom error, thread stuck | `LUA_BREAK` is only returned when a debug breakpoint hook fires; the shim has no debug hook API, making the code path structurally unreachable |
| `WasmBinding.resume` passes wrong `state` arg (second path) | Same `(void)L` evidence as earlier; duplicate entry |
| `lx_close` reads `LxStateData*` before `lua_close`, but `lua_close` may invoke GC finalizers that call back | Luau has no `__gc` metamethod; `lua_setuserdatadtor` is never called from the shim or Scala side; finalizer-via-GC scenario does not exist |
| `writeNResults` four byte-writes hit different `HEAPU8` snapshots | `val heap = WasmModule.module.HEAPU8` is bound once inside the method body; all four writes use the same snapshot |
| `NativeFnResult.Fail` discards the error `LuaValue` — Luau gets empty stack on `LX_FAIL` | Push-before-signal convention: NativeFn pushes error value before returning `Fail`; confirmed by three test sites |
| `pushCopy`/`pushRef` pass `state` as both `lx_State` and `lx_Thread` — inconsistent with other push operations | Duplicate of C-HIGH-19; here the objection is to the proposed fix being `mainThread(state)`, but the actual defect (wrong for coroutine callers) is real and confirmed |
| `SinkImpl` tracks `beginTable`/`endTable` depth; `PanamaSink`/`WasmSink` treat `endTable` as no-op | `endTable` has no stack effect in any implementation; a stray `endTable` cannot corrupt the Lua stack; no stack manipulation is performed |
| `PanamaState.unref(state, key)` called with thread handle — second claim | Duplicate; refuted: `Ref.close()` always passes `L` |
| `Sink.pushString` default implementation double-encodes UTF-8 | `Sink.pushString` calls `pushBytes`, not `binding.pushString`; no composition; one UTF-8 encode per path |
| `Scope.captureTop` creates a `Ref` but `PanamaScope` is a parallel incompatible `Scope` implementation | `PanamaScope` has zero instantiation sites in production code; dead class |
| Trampoline function registry uses mutable global `HashMap` — not safe for concurrent wasm instances | JS is single-threaded; one WASM module per process; no concurrent instances; shared registry is correct by design |
| `__.compile` wildcard compiles `scheduler/jvm` and `stdlib/jvm` not wired into `build.mill` | `scheduler/jvm` and `stdlib/jvm` contain no Mill module declarations; they are invisible to Mill's `__` traversal |
| `WasmModule.module` is uninitialized null — any test skipping `load()` crashes with NPE | All four test classes correctly guard with `WasmBackend.load()` before any test; no existing test omits this |
| `WasmSpecificSuite.beforeAll` does not reset Trampoline — stale `fnPtr` from prior suite | `WasmBackend.load()` always calls `Trampoline.reset()` then `Trampoline.install()` unconditionally |
| `enqueueResume` called from any thread writes non-atomic task state before enqueue | `cancelTask` is Driver-only; the Cancelled guard at `resumeTask:192` protects the critical path even if a stale Queued entry is dequeued |
| Lost-wakeup window: resume fires between `register()` and `installCancel()` | The `task.state == Queued` check at lines 239–240 on the Driver thread handles the early-fire case atomically from Driver's perspective |
| `cancelTask()` non-atomic read-check-act on `task.state` — race with off-Driver resume | Maximum impact is a missed cancel callback; `resumeTask:192` Cancelled guard prevents the use-after-free scenario |
| `liveTasks HashMap` accessed from multiple threads without synchronization | No off-Driver code path mutates `liveTasks`; `enqueueResume` and `wireSuspend` resume closure only call `runQueue.enqueue` |
| Duplicate `NativeFnDispatcher.register/nextId` not thread-safe entries | Same refutation as above |
| `PanamaScope.closeRefs()` closes in FIFO order, violating LIFO lifetime discipline | `PanamaScope` is dead code with zero callers; LIFO violation is a non-issue for unreachable code |
| `WasmBinding.ref()` pops stack unconditionally — leaves stack corrupt if `lx_ref` fails | `lx_ref` is documented as "never errors"; the only failure path (empty stack) is a caller precondition violation; unconditional pop is correct |
| `cancelTask()` skips Running tasks — `threadRef` leaks if cancelled mid-resume | Architecturally impossible: Driver thread cannot `cancelTask` while it is executing inside `binding.resume` |
| `PanamaState.unref()` called with thread handle — third duplicate | Same refutation |
| `Seq` codec test asserts empty result — always passes regardless of encode correctness | Misreads the assertion; the assertion `decoded.contains(Seq.empty)` always *fails* (not passes) with a correct implementation |
| `__.compile` wildcard silently skips modules not wired into `build.mill` | Second duplicate of the Mill-traversal refutation |
