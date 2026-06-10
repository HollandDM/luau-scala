# WASM Backend — Deep Reference

**Date:** 2026-06-10  
**Scope:** `wasm/` module (`luau.wasm` package), `shim/` build scripts, and the ABI they share.

---

## 1. Overview

The WASM backend is the Scala.js implementation of the `Binding[Int]` trait that drives
`luau-shim.wasm` — a native WASI reactor binary compiled with clang from the same Shim C++ source
as the JVM's native library. All Luau state handles (`lua_State*`, `lx_Thread*`) are
represented as `Int` (i32 opaque pointers into WASM linear memory).

The subsystem has nine production files and four test files:

| File | Role |
|------|------|
| `wasm/src/luau/wasm/WasmModule.scala` | `@js.native` facade over WASM exports; global singleton |
| `wasm/src/luau/wasm/LuauShimFactory.scala` | Synchronous WASM instantiation, WASI stubs, `addFunction` |
| `wasm/src/luau/wasm/WasmBackend.scala` | Entry point: `load()` + `createBinding()` |
| `wasm/src/luau/wasm/Trampoline.scala` | Global upcall singleton, `NativeFn` dispatch, suspend-token staging |
| `wasm/src/luau/wasm/WasmBinding.scala` | Core `Binding[Int]` implementation |
| `wasm/src/luau/wasm/WasmMarshal.scala` | Host↔linear-memory copy operations |
| `wasm/src/luau/wasm/WasmScope.scala` | `Scope[Int]` — Ref-owning region |
| `wasm/src/luau/wasm/WasmSink.scala` | `Sink[Int]` — Codec push target |
| `wasm/src/luau/wasm/LuauWasmLoader.scala` | Legacy loader (superseded, see §11) |

---

## 2. Module Load Flow

```
WasmBackend.load()
  └─ LuauShimFactory.apply()
       ├─ fs.readFileSync(LUAU_WASM_PATH | "luau-shim.wasm")
       ├─ WebAssembly.Module(bytes)
       ├─ WebAssembly.Instance(module, { wasi_snapshot_preview1: stubs })
       ├─ ex._initialize()           ← Reactor model: run C++ static ctors once
       ├─ Object.defineProperty(api, "HEAPU8", { get: () => new Uint8Array(mem.buffer) })
       ├─ Object.defineProperty(api, "HEAP32", { get: () => new Int32Array(mem.buffer) })
       ├─ api._malloc / api._free    ← forwarded from ex.malloc / ex.free
       ├─ api.addFunction            ← tbl.grow(1) + cachedWrapMod slot
       ├─ api.removeFunction
       ├─ api.dynCall_iiiiii
       └─ api._lx_*                  ← all lx_* exports forwarded by name loop
  └─ WasmModule.set(exports)
  └─ Trampoline.reset()             ← clear stale fnPtr/table/pendingSuspend
  └─ Trampoline.install()           ← grow table once, register upcall JS closure
```

The WASM path to the binary is read from the `LUAU_WASM_PATH` environment variable; if
absent, the default `"luau-shim.wasm"` is used. This happens inside `LuauShimFactory.apply()`
— the `loaderPath` parameter accepted by `WasmBackend.load()` is **not forwarded** and has no
effect (`wasm/src/luau/wasm/WasmBackend.scala:7–9`).

### 2.1 Reactor Model and `_initialize()`

WASM binaries can be built as either a *command* (runs `main()` once) or a *reactor* (library
model: exports remain callable across many invocations). The Shim is linked as a reactor
(`-mexec-model=reactor`). A reactor binary exports `_initialize()` to run C++ static
constructors once at startup — analogous to `DllMain` on Windows or `.init_array` on ELF.

`LuauShimFactory` calls `_initialize()` on the freshly created instance before any other
export (`LuauShimFactory.scala:67`). This boots the Luau runtime's global state. Under
the older *command* model (pre-`0720429`), every export invocation wrapped itself with
`ctors-on-entry / dtors-on-exit`, tearing down global state after each call and corrupting
the embedded Runtime.

### 2.2 Fresh Heap-View Getters

WASM linear memory can grow via `memory.grow`. Each growth operation **detaches** the
`ArrayBuffer` that backs every existing `Uint8Array` / `Int32Array` typed-array view. Any
code holding a cached view will read `undefined` bytes or throw
`"TypedArray.prototype.set on a detached ArrayBuffer"`.

The fix (commit `f8c590d`) defines `HEAPU8` and `HEAP32` as JavaScript property getters
via `Object.defineProperty`, so every access rebuilds the typed-array over the current
`mem.buffer`:

```javascript
// wasm/src/luau/wasm/LuauShimFactory.scala:76-83
Object.defineProperty(api, "HEAPU8", {
  get: () => new Uint8Array(mem.buffer)
});
Object.defineProperty(api, "HEAP32", {
  get: () => new Int32Array(mem.buffer)
});
```

`WasmModule.scala:9-10` declares them as `def` (not `val`) in the `@js.native` trait,
ensuring every Scala access goes through the getter. `WasmMarshal` never caches
`WasmModule.module.HEAPU8` across a `_malloc` call — it re-fetches immediately before
writing (`WasmMarshal.scala:17-19`, `WasmMarshal.scala:43-47`).

### 2.3 `addFunction` and the Growable Indirect Function Table

The Shim's trampoline mechanism relies on Luau's `call_indirect` instruction to call back
into JavaScript. When the Host registers a JS closure as a WASM function, it must be placed
in the module's `__indirect_function_table`.

The pre-`0720429` implementation overwrote slots starting at index 0, clobbering Luau's own
in-use `call_indirect` targets and causing trap failures. The fix links with
`-Wl,--growable-table` (`shim/build-wasm.sh:105`) and grows the table by exactly one slot
per registration:

```scala
// wasm/src/luau/wasm/LuauShimFactory.scala:90-93
api("addFunction") = { (fn: js.Function, sig: String) =>
  val i = tbl.grow(1).asInstanceOf[Int]   // returns old length = new last index
  val w = js.Dynamic.newInstance(WA.Instance)(cachedWrapMod,
            js.Dynamic.literal(env = js.Dynamic.literal(f = fn)))
  tbl.set(i, w.exports.w)
  i
}: js.Function
```

`cachedWrapMod` is a `lazy val` holding a pre-compiled micro WASM module (5-arg i32→i32
forwarding trampoline) whose sole import is the JS closure. A new instance wraps each
registered function. The table never shrinks; removed slots are nulled but the index is not
recycled, preventing accidental clobbering of the Shim's own entries.

### 2.4 WASI Stubs

`luau-shim.wasm` imports `wasi_snapshot_preview1` because the WASI sysroot provides POSIX
I/O primitives. The Shim does not use file I/O at runtime, but the linker emits the imports.
`LuauShimFactory` stubs all eleven required functions as no-ops (`LuauShimFactory.scala:46-58`).

The critical fix (commit `0720429`) was typing i64 WASM arguments as `js.BigInt` rather than
`Int`. WASM i64 values cross the JS boundary as JavaScript `BigInt` objects; if Scala.js
expects an `Int`, unboxing throws `ClassCastException`. The stubs for `fd_seek` and
`clock_time_get` carry `js.BigInt` parameters:

```scala
fd_seek = { (_: Int, _: js.BigInt, _: Int, _: Int) => 0 }: js.Function,
clock_time_get = { (_: Int, _: js.BigInt, _: Int) => 0 }: js.Function,
```

---

## 3. `WasmModule` — The Export Facade

`wasm/src/luau/wasm/WasmModule.scala` defines the `@js.native` trait `WasmModuleExports`,
which is the Scala.js type for the synthesised API object built by `LuauShimFactory`. Key
declarations:

```scala
@js.native
trait WasmModuleExports extends js.Object:
  def HEAPU8: Uint8Array                  // getter — fresh view every call
  def HEAP32: js.typedarray.Int32Array    // getter — fresh view every call
  def _malloc(size: Int): Int
  def _free(ptr: Int): Unit
  def addFunction(fn: js.Function, sig: String): Int
  def dynCall_iiiiii(fnPtr: Int, a0: Int, a1: Int, a2: Int, a3: Int, a4: Int): Int
  def _lx_newstate(upcall: Int): Int
  // ... all lx_* exports typed as Int (i32)
  def _lx_set_suspend_token(state: Int, thread: Int, token: Int): Unit  // ← see §10.3
  def _lx_get_suspend_token(state: Int, thread: Int): Int               // ← see §10.3
```

The global singleton is held in `WasmModule`:

```scala
// wasm/src/luau/wasm/WasmModule.scala:73-76
object WasmModule:
  private var _module: WasmModuleExports = scala.compiletime.uninitialized
  private[wasm] def set(m: WasmModuleExports): Unit = _module = m
  def module: WasmModuleExports = _module
```

`WasmModule.set()` is package-private; only `WasmBackend.load()` and `LuauWasmLoader.load()`
call it. All other code reads `WasmModule.module` directly.

---

## 4. Trampoline

The Trampoline is the single bridge between WASM (Luau) and Scala.js (Host) for every
Native function call. It is a global singleton (`object Trampoline`).

### 4.1 Design

When the Host registers a Native function, the Shim installs a C closure (`lx_trampoline`)
in the Luau state. That closure stores a numeric `fnId` as its sole upvalue. When Luau
calls the function, `lx_trampoline` runs inside `lua_resume`'s `setjmp` frame and invokes
the `lx_HostFn` upcall — the single JS function registered in the WASM function table.

The JS-side upcall is:

```scala
// wasm/src/luau/wasm/Trampoline.scala:34-37
val upcall: js.Function5[Int, Int, Int, Int, Int, Int] =
  (state: Int, thread: Int, fnId: Int, nArgs: Int, nResultsPtr: Int) =>
    dispatch(state, thread, fnId, nArgs, nResultsPtr)
fnPtr = WasmModule.module.addFunction(upcall, "iiiiii")
```

The signature `"iiiiii"` means 5× i32 arguments + 1× i32 return value, matching the
`lx_HostFn` C typedef (`shim/include/lx.h:47-53`).

### 4.2 Dispatch and Tri-state Result

`Trampoline.dispatch` looks up the `fnId` in a `HashMap[Int, NativeFn]`, calls the Scala
function, and encodes its `NativeFnResult` as an integer return value to the Shim
(`wasm/src/luau/wasm/Trampoline.scala:63-86`):

| `NativeFnResult` | Action | Return to Shim |
|---|---|---|
| `Return(n)` | Write `n` as little-endian i32 into `nResultsPtr` via `HEAPU8` | `LxReturn.Return = 0` |
| `Fail(_)` | (caller already pushed error string before returning `Fail`) | `LxReturn.Fail = 1` |
| `Suspend(reg)` | Store `reg` in `pendingSuspend` | `LxReturn.Suspend = 2` |
| `Throwable` caught | Encode message, push as string | `LxReturn.Fail = 1` |

`LxReturn` values match the Shim's `LX_RETURN` / `LX_FAIL` / `LX_SUSPEND` constants
(`lx.h:26-28`). The Shim's `lx_trampoline` interprets the return value and takes the
appropriate action in pure C, inside the `setjmp` frame.

**No Scala/JS exception escapes the WASM/JS boundary.** Every `Throwable` is caught in the
`dispatch` method's `try/catch` block (`Trampoline.scala:80-86`), formatted to a string,
pushed as a Luau string, and returned as `LxReturn.Fail`.

### 4.3 The `reset()` / `install()` Contract

Each `WasmBackend.load()` creates a brand-new WASM instance with its own function table.
`Trampoline.reset()` must be called **before** `Trampoline.install()` for the new instance:

```scala
// wasm/src/luau/wasm/WasmBackend.scala:13-14
Trampoline.reset()
Trampoline.install()
```

`reset()` clears `fnPtr` (to `-1`), `pendingSuspend`, the entire `table`, and resets
`nextId` to `1` (`Trampoline.scala:48-52`). The guard `if fnPtr == -1` in `install()`
prevents double-registration within the same instance, but would silently reuse a stale
`fnPtr` if `reset()` were skipped — which is exactly the hazard in `LuauWasmLoader`
(see §11).

### 4.4 Suspension Staging (`pendingSuspend`)

When a Native function returns `Suspend(reg)`, the `reg: Resume => Cancel` closure cannot
be passed back through the integer WASM ABI. Instead, it is staged in
`Trampoline.pendingSuspend`. The Scheduler must call `Trampoline.consumePendingSuspend()`
**immediately** after the enclosing `_lx_resume` returns `LxStatus.Yield`, before any other
Native function can fire and overwrite the slot:

```scala
// wasm/src/luau/wasm/Trampoline.scala:27-30
def consumePendingSuspend(): Option[Resume => Cancel] =
  val r = pendingSuspend
  pendingSuspend = None
  r
```

The Scheduler is not yet implemented; this coupling is currently undocumented in the public API.

---

## 5. `WasmBinding` — Core `Binding[Int]`

`WasmBinding` is a `final class` implementing `Binding[Int]`, where `H = Int` represents
opaque WASM linear-memory pointers.

### 5.1 State Lifecycle

```scala
// wasm/src/luau/wasm/WasmBinding.scala:12-16
override def newState(): Int =
  module._lx_newstate(Trampoline.install())

override def closeState(state: Int): Unit =
  module._lx_close(state)
```

`_lx_newstate` takes the function-table index of the upcall closure as its `upcall`
argument (an i32 pointer into the WASM function table, not a JS function pointer). The
Host allocates a new Luau Isolate and registers the single Trampoline closure as the
`lx_HostFn` for all Native functions in that state.

### 5.2 Main Thread Resolution

All stack-push and stack-read operations on `WasmBinding` resolve the main thread first:

```scala
// wasm/src/luau/wasm/WasmBinding.scala:249-250
private def mainThread(state: Int): Int =
  module._lx_main_thread(state)
```

This wraps `lua_mainthread(L)` in the Shim (`shim/src/lx.cpp:103-106`). Most operations
pass `(state, mainThread)` as the two-argument form of the Shim ABI, which reflects the
design principle that Host-driven pushes/reads operate on the main Luau thread.

### 5.3 Resume Boundary

```scala
// wasm/src/luau/wasm/WasmBinding.scala:45-60
override def resume(thread: Int, nargs: Int): ResumeResult =
  val (nresultsPtr, readNResults) = WasmMarshal.allocOutInt()
  try
    val status = module._lx_resume(thread, thread, nargs, nresultsPtr)
    status match
      case LxStatus.Ok    => ResumeResult.Returned(readNResults())
      case LxStatus.Yield => ResumeResult.Yielded(readNResults())
      case _ =>
        val errMsg = readError(thread)
        if errMsg.nonEmpty then module._lx_pop(thread, thread, 1)
        ResumeResult.Error(LuaError.runtime(errMsg))
  finally
    module._free(nresultsPtr)
```

Note that both the `state` and `thread` arguments to `_lx_resume` are set to `thread`. For
the main thread (where `lx_main_thread(state) == state`), this is correct. For a Coroutine
thread that differs from the main state, passing the Coroutine handle as `state` is
technically incorrect per the Shim ABI — though the Shim's `lx_resume` implementation
ignores its `state` argument (it only uses `thread` via the `co` cast, `lx.cpp:179-182`),
so this does not cause a runtime fault today.

### 5.4 Ref Lifecycle

`lx_ref` pins the value at the given index **without popping it** (per `lx.h:282-286`):

```scala
// wasm/src/luau/wasm/WasmBinding.scala:205-211
override def ref(state: Int): Ref[Int] =
  val thread = mainThread(state)
  val refId = module._lx_ref(state, thread, -1)
  // lx_ref pins by index without popping; the Ref now owns the value,
  // so consume it off the stack to match luaL_ref semantics.
  module._lx_pop(state, thread, 1)
  Ref[Int](state, refId, this, "wasm")
```

The explicit `_lx_pop` was added in commit `f649a577` to fix TC-SHARED-06, where
`stackTop` remained non-zero after `ref()`.

### 5.5 Native Function Registration

```scala
// wasm/src/luau/wasm/WasmBinding.scala:218-220
override def registerNativeFn(state: Int, fn: NativeFn[Int]): Unit =
  val fnId = Trampoline.register(fn)
  module._lx_register_native(state, fnId, 0)
```

`Trampoline.register` assigns the next available integer identifier and stores the function
in the `HashMap`. `_lx_register_native` installs a C closure (`lx_trampoline`) with
`fnId` as its upvalue and pushes it onto the Luau main thread's stack
(`shim/src/lx.cpp:308-312`). The caller is responsible for consuming this value (e.g., via
`setGlobal`).

### 5.6 `typeAt` and the Type Code Mapping

The Shim's `lx_type` returns raw Luau type codes (`lx.h:187-198`). `WasmBinding.typeAt`
hand-maps these to the `LuaType` enum (`WasmBinding.scala:104-120`):

| Raw code | `LuaType` | Notes |
|---|---|---|
| -1 | `None` | `LX_TNONE` |
| 0  | `Nil` | `LX_TNIL` |
| 1  | `Boolean` | `LX_TBOOLEAN` |
| **2** | **`Nil`** | **No type 2 in lx.h — dead-code defensive fallback** |
| 3  | `Number` | `LX_TNUMBER` |
| 4  | `Number` | `LX_TINTEGER` — Luau integer subtype, collapsed to Number |
| 5  | `Number` | `LX_TVECTOR` — Luau vector, collapsed to Number |
| 6  | `String` | `LX_TSTRING` |
| 7  | `Table` | `LX_TTABLE` |
| 8  | `Function` | `LX_TFUNCTION` |
| 9  | `Userdata` | `LX_TUSERDATA` |
| 10 | `Thread` | `LX_TTHREAD` |

The `LuaType` enum in `core` uses a **different** `luaCode` numbering (defined in
`core/jvm/src/luau/core/LuaType.scala:3-11`): `String.luaCode = 4`, `Table.luaCode = 5`,
etc. This is the core enum's internal representation and does **not** match the raw Shim
type codes. The `typeAt` method's case match correctly uses literal integers from `lx.h`,
not `LuaType.*.luaCode` values.

---

## 6. `WasmMarshal` — Linear Memory I/O

All Host↔WASM data copying is centralised in `WasmMarshal`. The three invariants it
enforces are:

1. **Copy-only.** No Lua heap pointer escapes to the Host; the Shim copies data before
   returning.
2. **Alloc–write–call–free.** Every operation that needs linear memory allocates, uses, and
   frees within a single call frame.
3. **No cached views.** `HEAPU8` is always re-fetched from the getter, never held across a
   `_malloc` (which may trigger `memory.grow`).

### 6.1 String Encoding

```scala
// wasm/src/luau/wasm/WasmMarshal.scala:11-22
def withString[A](s: String)(f: (Int, Int) => A): A =
  val bytes: Uint8Array = utf8Encoder.encode(s).asInstanceOf[Uint8Array]
  val len = bytes.length
  val ptr = WasmModule.module._malloc(len + 1)
  try
    val heap = WasmModule.module.HEAPU8  // fresh view after malloc
    heap.set(bytes, ptr)
    heap(ptr + len) = 0                  // NUL terminator
    f(ptr, len)
  finally
    WasmModule.module._free(ptr)
```

`TextEncoder` (cached as `utf8Encoder`) produces a `Uint8Array` of UTF-8 bytes. The buffer
is one byte longer than the string for the NUL terminator. Caller receives `(ptr, len)` and
must not hold either past the lambda.

### 6.2 Byte Array Copies

`withIArrayBytes` and `withBytes` operate identically but accept `IArray[Byte]` and
`Array[Byte]` respectively. They do not add a NUL terminator because byte arrays are not
expected to be NUL-terminated strings.

### 6.3 Integer Out-Parameters

Many Shim functions return values via `int*` out-parameters. `allocOutInt` allocates 4 bytes
and returns a closure that reads them as a little-endian i32:

```scala
// wasm/src/luau/wasm/WasmMarshal.scala:59-68
def allocOutInt(): (Int, () => Int) =
  val ptr = WasmModule.module._malloc(4)
  val read = () =>
    val heap = WasmModule.module.HEAPU8
    (heap(ptr).toInt & 0xff) | ((heap(ptr+1).toInt & 0xff) << 8) |
    ((heap(ptr+2).toInt & 0xff) << 16) | ((heap(ptr+3).toInt & 0xff) << 24)
  (ptr, read)
```

The caller must `_free(ptr)` when done. `WasmBinding.toNumber` and `WasmBinding.resume`
both use `allocOutInt` and free in a `finally` block.

---

## 7. `WasmScope` and `WasmSink`

### 7.1 `WasmScope`

`wasm/src/luau/wasm/WasmScope.scala:5` is a one-liner:

```scala
final class WasmScope(binding: WasmBinding, L: Int) extends Scope[Int](binding, L) {}
```

All Ref-ownership logic is inherited from `core.Scope`. No WASM-specific state.

### 7.2 `WasmSink`

`WasmSink` implements the Codec `Sink[Int]` for the WASM backend
(`wasm/src/luau/wasm/WasmSink.scala`). Table construction:

- `beginTable()` calls `binding.newTable(state)`, which always uses `narr=0, nrec=0`
  (no preallocation hint is passed through).
- `endTable()` is a no-op; the table value stays on the Luau stack per Sink protocol.
- `pushValue[A]` encodes the value onto the stack and calls `binding.rawSet(state, -3)`,
  consuming key and value, leaving the table at -2→-1 after the pop.
- `pushArrayValue[A]` encodes the value and calls `binding.setArray(state, -2, n)`, which
  maps to `_lx_rawseti` and **pops** the value.

---

## 8. Exception Handling (EH) Story

### 8.1 The Problem

Luau's error model uses native C++ exceptions internally. The `lua_error` path calls
`throw lua_longjmp(...)`, and `lua_resume` catches it via a surrounding `try/catch` in
Luau's C++ runtime. The released `wasi-sdk` artifacts ship a `-fno-exceptions` sysroot:
`libc++abi` has `__cxa_throw` stubbed to `abort()`. This caused every Luau runtime error,
`pcall`, and `coroutine.yield` to trigger `RuntimeError: unreachable` in WASM.

### 8.2 The Fix: EH Sysroot

`shim/build-eh-sysroot.sh` builds a replacement WASI sysroot from `wasi-sdk-31` sources
with `-DWASI_SDK_EXCEPTIONS=ON`, reusing the system clang (LLVM 22) rather than rebuilding
LLVM:

```bash
cmake ... -DWASI_SDK_EXCEPTIONS=ON -DWASI_SDK_TARGETS=wasm32-wasi ...
ninja -C build/sysroot install
```

This produces `libc++`, `libc++abi`, and `libunwind` with real `__cxa_throw` and the
WebAssembly new-EH encoding (LLVM 22 / Node.js 26 compatible). The resource directory is
merged by symlinking: system clang's `include/` + the new sysroot's `lib/wasm32-wasi/`
compiler-rt builtins.

### 8.3 Uniform EH Encoding Flags

**Every** translation unit — all Luau VM/Compiler/Ast/Bytecode/Common sources plus `lx.cpp`
— is compiled with the same flags (`shim/build-wasm.sh:39-41`):

```
-fwasm-exceptions
-mllvm -wasm-use-legacy-eh=false
```

Mixing legacy SjLj-encoded EH objects with new-EH objects in the same link corrupts
unwinding. The `build-wasm.sh` script comments on this explicitly at line 114. There is no
special-casing of the Luau Compiler module.

### 8.4 The `__cpp_exception` Tag (`cpp_exception_tag.s`)

The WebAssembly new-EH proposal requires a *tag* definition for C++ exceptions
(`__cpp_exception`). LLVM/clang emits references to this tag for every `throw`/`catch`,
and `libc++abi`/`libunwind` import it, but **wasm-ld (LLVM 22) does not synthesize the tag
itself** (wasi-sdk issue #565). Without a canonical definition, the link either fails or
exceptions abort.

`shim/src/cpp_exception_tag.s` provides the single definition:

```asm
    .tagtype    __cpp_exception i32
    .globl      __cpp_exception
__cpp_exception:
```

This assembly file is compiled separately and linked into every WASM binary
(`shim/build-wasm.sh:131-133`). The `.tagtype` directive is a wasm-ld extension specific
to LLVM's assembler; it is **not portable** to other linkers.

### 8.5 Link Parameters Summary

```
-mexec-model=reactor           # Reactor model: _initialize() once
-lc++abi -lunwind              # EH runtime
-fwasm-exceptions              # New EH encoding
-mllvm -wasm-use-legacy-eh=false
-Wl,--export-table             # Export __indirect_function_table
-Wl,--growable-table           # Allow tbl.grow(1) in addFunction
-Wl,-z,stack-size=1048576      # 1 MB Lua C stack
-Wl,--max-memory=33554432      # 32 MB cap
```

---

## 9. Test Architecture

### 9.1 `SharedBackendSuite`

`wasm/test/src/luau/core/SharedBackendSuite.scala` is an abstract `munit.FunSuite` with
10 tests (TC-SHARED-01 through TC-SHARED-10). Each test receives a `Binding[Int]` via the
abstract `withBinding[A](f: Binding[Int] => A): A`. The suite exercises:

| Test | Behaviour |
|---|---|
| TC-SHARED-01 | Basic compilation and resume, integer return |
| TC-SHARED-02 | UTF-8 string push and read-back (`hello, 世界`) |
| TC-SHARED-03 | Table construction via `rawseti` / `rawgeti` |
| TC-SHARED-04 | Native function callable from script (`hostAdd`) |
| TC-SHARED-05 | Native function `Fail` result raises a Lua error through `pcall` |
| TC-SHARED-06 | Ref lifecycle: create, push, stack height invariant |
| TC-SHARED-07 | Scope closes all owned Refs on exit |
| TC-SHARED-08 | Coroutine yield/resume round-trip |
| TC-SHARED-09 | Multi-byte UTF-8 (Japanese + emoji) preserved |
| TC-SHARED-10 | Compile error surfaces as `Left` |

### 9.2 `WasmBackendSuite`

`WasmBackendSuite` extends `SharedBackendSuite`:

```scala
// wasm/test/src/luau/wasm/WasmBackendSuite.scala:10-12
override def withBinding[A](f: Binding[Int] => A): A =
  WasmBackend.load()
  f(WasmBackend.createBinding())
```

Each test reloads the entire WASM instance. This was introduced in commit `c3a0b4ca` after
discovering that a prior state's `closeState` left the shared heap and function table dirty
enough to crash subsequent tests (`lua_rawgeti` assertion failures). The cost is a full WASM
instantiation per test (~100ms each), but correctness requires it.

### 9.3 WASM-Specific Tests

`wasm/test/src/luau/wasm/WasmSpecificSuite.scala` exercises:

- **TC-WASM-01**: Module loads, `WasmModule.module != null`.
- **TC-WASM-02**: `_malloc` / `_free` do not crash; write and read a byte.
- **TC-WASM-03**: `addFunction` + `dynCall_iiiiii` round-trip.
- **TC-WASM-04**: Two `WasmBinding` instances on the same WASM module have independent
  Luau states (independent stacks, no cross-contamination).

`wasm/test/src/luau/wasm/WasmModuleSmokeTest.scala` bypasses the Trampoline entirely and
calls `_lx_newstate(0)` directly (upcall=0) to validate raw WASM export wiring.

---

## 10. Known Issues and Risks

### 10.1 `toBytes` Empty-String Detection Bug

In `WasmBinding.toBytes` (`WasmBinding.scala:135-141`):

```scala
if rawLen <= 0 then
  if module._lx_type(state, thread, idx) == LuaType.String.luaCode then
    Some(IArray.empty[Byte])
  else None
```

`LuaType.String.luaCode` is `4` (the core enum's internal code, `LuaType.scala:8`), but
`lx.h` defines `LX_TSTRING = 6`. The `_lx_type` call returns `6` for a string slot.
The comparison `6 == 4` is always false, so an empty Luau string at a stack slot returns
`None` instead of `Some(IArray.empty)`. Non-empty strings are not affected because
`rawLen > 0` takes the `else` branch.

### 10.2 `typeAt` Code 2 Maps to `Nil`

`WasmBinding.typeAt` maps raw code `2` to `LuaType.Nil` (`WasmBinding.scala:111`). The
Shim's `lx.h` has no type 2 — there is a gap between `LX_TBOOLEAN = 1` and
`LX_TNUMBER = 3`. This is likely dead code; Luau's `lua_type` should never return 2. If it
ever does, the silent mapping to `Nil` would be wrong.

### 10.3 Suspend Token Width Mismatch (Latent)

`WasmModuleExports` declares `_lx_set_suspend_token` and `_lx_get_suspend_token` with `Int`
parameters (`WasmModule.scala:61-62`), but `lx.h:325-333` defines them with `int64_t`
(`token: int64_t`). The WASM i64 type crosses the JS boundary as a JavaScript `BigInt`.

Critically, **the Wasm backend currently never calls either function**. The suspend path on
the Wasm side uses `Trampoline.pendingSuspend` — a Scala `Option[Resume => Cancel]` slot set
inside `Trampoline.dispatch` (`Trampoline.scala:78`) — rather than a numeric token. The
`_lx_set_suspend_token` and `_lx_get_suspend_token` exports are wired up in
`build-wasm.sh:90-91` and declared in `WasmModule.scala:61-62` but are dead code from the
Scala side.

If these functions were ever called, the failure modes differ by direction:

- **`_lx_set_suspend_token` (Scala → WASM):** passing a Scala `Int` for an i64 parameter
  causes **silent truncation** — the upper 32 bits of the token are zeroed, corrupting any
  token value larger than 32 bits. No exception is thrown.
- **`_lx_get_suspend_token` (WASM → Scala):** the WASM function returns an i64, which
  arrives at the JS boundary as a `BigInt`. Typing the return as `Int` would cause a JS
  `TypeError` or Scala.js `ClassCastException` at the point of use (the same class of bug
  fixed in the WASI stubs by commit `0720429`).

Because neither function is called today, both failure modes are latent. If the suspend-token
API is ever activated, both declarations must be corrected to use `js.BigInt` (matching the
pattern established in the WASI stubs).

### 10.4 Global Trampoline Singleton

`Trampoline` is a global `object` shared by all `WasmBinding` instances. The single
`pendingSuspend` slot means two concurrent Luau states firing Native functions that both
return `Suspend` would overwrite each other's `register` closures. In single-threaded
JavaScript this is safe in practice (only one Luau execution runs at a time), but the
architecture prevents multi-Isolate WASM deployments where two states could interleave
resumes. If the Scheduler ever creates multiple Isolates on one WASM instance, this must
be refactored to per-binding state.

### 10.5 `resume` Passes `thread` as Both `state` and `thread` Args

`WasmBinding.resume(thread, nargs)` calls `module._lx_resume(thread, thread, ...)`. The
first argument is meant to be the `lx_State` (the Isolate's main state). For the main
thread this is correct, since `lx_main_thread(state) == state`. However, the `Binding`
trait's `resume` method takes only a `thread: Int` argument; there is no way to recover
the originating `state` from it. `lx_resume` in the Shim currently ignores its `state`
argument (`lx.cpp:180` casts only `thread` to `co`), so this does not cause a runtime
fault — but it is a latent mismatch.

---

## 11. `LuauWasmLoader` — Legacy Loader

`wasm/src/luau/wasm/LuauWasmLoader.scala` is a legacy entry point that calls
`LuauShimFactory`, sets `WasmModule`, and calls `Trampoline.install()` — but does **not**
call `Trampoline.reset()` first:

```scala
// wasm/src/luau/wasm/LuauWasmLoader.scala:7-10
def load(): Unit =
  val exports = LuauShimFactory(js.Dynamic.literal())
  WasmModule.set(exports)
  Trampoline.install()  // no reset()!
```

If `WasmBackend.load()` has been called first, `fnPtr` is already set to a non-`-1` value.
The `if fnPtr == -1` guard in `install()` prevents re-registration, so the stale `fnPtr`
from the previous instance's function table is returned — pointing into the new instance's
table at a different address. This will call the wrong function or trap.

`LuauWasmLoader` is not called by any test and is superseded by `WasmBackend` for all
production use. It should be removed to eliminate the hazard.

---

## 12. Architecture Invariants

The following invariants must hold for the WASM backend to function correctly:

1. **Reactor initialization.** `_initialize()` is called exactly once per WASM instance,
   before any `_lx_*` export. Violating this leaves C++ static constructors un-run and
   global Luau state uninitialised.

2. **Fresh heap views.** `HEAPU8` and `HEAP32` are never cached across a `_malloc` call.
   WASM `memory.grow` detaches the `ArrayBuffer`; a cached `Uint8Array` becomes a detached
   buffer and throws on access.

3. **Single active WASM instance.** `Trampoline` is a global singleton. Only one WASM
   instance may be active at a time. `WasmBackend.load()` calls `Trampoline.reset()` before
   `install()` to clear all state from the prior instance.

4. **Function table only grows.** `addFunction` uses `tbl.grow(1)` and never reuses a
   previously occupied slot. This prevents overwriting Shim-owned `call_indirect` targets.

5. **No exception crosses the WASM/JS boundary.** All `Throwable`s in `Trampoline.dispatch`
   are caught, serialised, and returned as `LxReturn.Fail`. No Scala exception propagates
   into C.

6. **`lx_ref` does not pop; WasmBinding pops manually.** `lx_ref(idx)` pins the value at
   `idx` without removing it from the stack (`lx.h:282-286`). `WasmBinding.ref` explicitly
   pops one value after the call to match `luaL_ref` semantics and preserve the stack height
   invariant.

7. **`lx_unref` requires the Driver.** `_lx_unref` must be called on the Driver thread that
   owns the Isolate. The WASM backend is single-threaded JavaScript, so this is trivially
   satisfied.

8. **Resume is the only Luau entry point.** `_lx_resume` is the sole function that executes
   Luau code. No `lua_pcall` is used anywhere in the Shim. All errors and yields surface as
   integer status codes.

---

## 13. Lessons Learned from Recent Fixes

The five commits between `0720429` and `a10109f` contain the full debugging history of
bringing the WASM backend from non-functional to all 15 tests passing. The root causes, in
sequence:

### 13.1 WASM Command vs. Reactor Model (`0720429`)

**Symptom:** Load succeeded but the first `_lx_*` call crashed. Luau's runtime-internal
global state was corrupted.

**Root cause:** `build-wasm.sh` produced a *command* binary. Every exported function was
wrapped with `__wasm_call_ctors` on entry and `__wasm_call_dtors` on exit. The dtors tore
down C++ global state (including Luau's heap) after each call. The first call to
`_lx_newstate` successfully set up global state; the wrapper's dtor destroyed it; subsequent
calls operated on uninitialised memory.

**Fix:** Build as a reactor (`-mexec-model=reactor`). Call `_initialize()` once in
`LuauShimFactory`.

### 13.2 Non-Growable Function Table (`0720429`)

**Symptom:** `addFunction` with an index near 0 clobbered Luau internals; `call_indirect`
trapped.

**Root cause:** `addFunction` started slot allocation at index 0 and incremented into
in-use slots that Luau's own compiled Luau functions occupied.

**Fix:** Link with `-Wl,--growable-table` and grow the table with `tbl.grow(1)` for each
new function, using the fresh slot returned by `grow`.

### 13.3 WASI i64 Args as `js.BigInt` (`0720429`)

**Symptom:** `ClassCastException` during WASM instantiation when the stub functions were
called during reactor initialisation.

**Root cause:** WASM i64 parameters arrive at JS as `BigInt`. The WASI stubs for `fd_seek`
and `clock_time_get` were typed `Int`, so Scala.js unboxed the `BigInt` to `Int` and threw.

**Fix:** Type i64 parameters in WASI stubs as `js.BigInt`.

### 13.4 Detached Heap Views on Memory Growth (`f8c590d`)

**Symptom:** `"TypedArray.prototype.set on a detached ArrayBuffer"` and silent byte
reads returning `undefined`, converting to `0` when cast to `Short`.

**Root cause:** The original loader captured `HEAPU8` once as a `val` at load time. After
`_malloc` triggered `memory.grow`, the `ArrayBuffer` was detached. All subsequent writes
via the cached `Uint8Array` were no-ops or threw.

**Fix:** Define `HEAPU8`/`HEAP32` as `Object.defineProperty` getters that rebuild the
typed-array over `mem.buffer` on every call.

### 13.5 `lx_ref` Leaves Value on Stack (`f649a577`)

**Symptom:** TC-SHARED-06 failed: `stackTop` was non-zero after `b.ref(state)` was called.

**Root cause:** `lx_ref` pins the value at its index without popping it (matching `lua_ref`
semantics in Luau). `WasmBinding.ref` did not pop after the call, leaving the value on the
stack.

**Fix:** Add explicit `_lx_pop(state, thread, 1)` after `_lx_ref`.

### 13.6 Per-Test WASM Instance Isolation (`c3a0b4ca`)

**Symptom:** Tests TC-SHARED-03+ crashed with `lua_rawgeti` assertion trap even after all
earlier fixes.

**Root cause:** `WasmBackendSuite` loaded the WASM module once in `beforeAll` and reused one
`WasmBinding` across all 10 tests. Each test that opened and closed an `lx_State` left
residue in the shared WASM heap (Luau's global string table, the registry, the function
table) that caused assertion failures in subsequent tests.

**Fix:** Call `WasmBackend.load()` inside `withBinding` so each test gets a fresh WASM
instance with a clean heap.

### 13.7 Native C++ Exceptions (`a10109f`)

**Symptom:** TC-SHARED-05 (pcall wrapping a failing Native function) and TC-SHARED-08
(coroutine yield) both trapped with `RuntimeError: unreachable`.

**Root cause:** Released `wasi-sdk` stubs `__cxa_throw` to `abort()`. Any Luau runtime
error — including the `lua_error` call in `lx_trampoline`'s `LX_FAIL` path and the
`lua_yield` call in the `LX_SUSPEND` path — aborted instead of unwinding.

**Fix:** Build a custom EH-enabled WASI sysroot via `build-eh-sysroot.sh` and link with
`-fwasm-exceptions -mllvm -wasm-use-legacy-eh=false`. Provide the canonical
`__cpp_exception` tag definition in `cpp_exception_tag.s`.

---

## 14. Public API Summary

```
WasmBackend.load(): Unit
  Synchronously load luau-shim.wasm, reset+install Trampoline, set WasmModule global.
  Must be called before WasmBinding.create(). The loaderPath parameter is unused.

WasmBackend.createBinding(): WasmBinding
  Return a new WasmBinding; caller uses it to create Isolates via newState().

WasmBinding.create(): WasmBinding
  Factory. Caller must call WasmBackend.load() first.

WasmBinding extends Binding[Int]
  Full Binding[Int] surface: newState/closeState/compileAndLoad/resume/newThread/
  push*/typeAt/toNumber/toBoolean/toBytes/stackTop/setStackTop/newTable/rawGet/
  rawSet/setArray/getArray/rawLen/ref/unref/registerNativeFn/getGlobal/setGlobal/
  openScope/openLibs/sandbox.

Trampoline.register(fn: NativeFn): Int
  Register a NativeFn; return fnId for use with _lx_register_native.

Trampoline.unregister(fnId: Int): Unit
  Remove a NativeFn from the dispatch table.

Trampoline.consumePendingSuspend(): Option[Resume => Cancel]
  Called by the Scheduler after _lx_resume returns Yield; returns and clears the
  staged register closure.

WasmModule.module: WasmModuleExports
  Direct access to all WASM exports after load().

WasmMarshal.withString[A](s: String)(f: (Int, Int) => A): A
  UTF-8-encode, malloc, write, NUL-terminate, call f(ptr, len), free.

WasmMarshal.withIArrayBytes[A](bytes: IArray[Byte])(f: (Int, Int) => A): A
  malloc, copy, call f(ptr, len), free.

WasmMarshal.allocOutInt(): (Int, () => Int)
  Allocate a 4-byte i32 out-param slot; return (ptr, reader). Caller must free ptr.

WasmSink(binding: WasmBinding, state: Int) extends Sink[Int]
  Codec Sink for the WASM backend.

WasmScope extends Scope[Int]
  Ref-owning region; all Refs closed on scope exit.
```

---

## 15. Open Questions

1. **`consumePendingSuspend` caller.** Who calls `Trampoline.consumePendingSuspend()` in
   the actual Suspend flow? `WasmBinding.resume` returns `ResumeResult.Yielded` but does not
   attach the `register` closure. The Scheduler must consume it immediately. This coupling is
   undocumented.

2. **Trampoline as instance vs. singleton.** Should `Trampoline` be per-`WasmBinding` (or
   per-Isolate) rather than a global `object`? The current design blocks multi-Isolate WASM
   use and makes the `pendingSuspend` slot shared across all states.

3. **Suspend token i64 type fix.** When the Scheduler is implemented, will it use
   `_lx_set/get_suspend_token`? If yes, the `Int` declarations in `WasmModuleExports` for
   these two functions must be changed to `js.BigInt` before any Suspend path is exercised.

4. **`LuauWasmLoader` removal.** The loader is unused and hazardous (missing `reset()`).
   Should it be deleted?

5. **Synchronous WASM compilation.** `LuauShimFactory` calls `WebAssembly.Module(bytes)`
   synchronously. The WebAssembly spec recommends asynchronous compilation for modules above
   ~4 KB to avoid blocking the JS event loop. This works on Node.js test runner but may be
   problematic in browser environments.

6. **`toBytes` empty-string bug.** See §10.1. The fix is to change
   `LuaType.String.luaCode` comparison to the literal `6` (matching `LX_TSTRING`).
