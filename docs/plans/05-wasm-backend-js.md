# P05 — WASM Backend (JS): Scala.js Binding Against luau-shim.wasm

## 1. Milestone & Goal

This plan delivers the **WASM backend**: a complete implementation of the `Binding` trait (defined in P03) in Scala.js, targeting the Emscripten-compiled `luau-shim.wasm` produced in P02. The backend loads the MODULARIZE-d Emscripten module, calls the exported `_lx_*` functions through either `cwrap` or direct export handles, marshals strings and byte arrays through HEAPU8 views with explicit `_malloc`/`_free` discipline, installs the trampoline upcall via Emscripten's `addFunction`/`removeFunction` + `dynCall` mechanism, and implements `Scope` as a Scala-owned ref-tracking region. It also establishes a **shared cross-backend test suite**: the same `.luau` scripts and assertions that P04 validates on the JVM are executed on Node.js via this backend, ensuring identical behavior across both platforms. At the end of this milestone, the `wasm` Mill module produces a runnable Scala.js artifact that passes every test in the shared suite when run on Node.js.

---

## 2. Dependencies

### Prior plans that must be complete

| Plan | Artifacts consumed by this plan |
|------|--------------------------------|
| P01 `01-project-scaffold-and-build-toolchain.md` | Mill cross-build wiring; `wasm` module definition; Emscripten toolchain integration; `luau-shim.wasm` + `loader.js` artifacts on disk; Scala.js linker settings |
| P02 `02-cpp-shim-abi.md` | The complete `_lx_*` ABI: function names, argument/return types, EXPORTED_FUNCTIONS list, trampoline signature, yield/resume protocol, and the compiled `luau-shim.wasm` + its companion `loader.js` |
| P03 `03-core-abstractions.md` | `Binding` trait; `Ref`; `Scope`; `LuaError`; `NativeFnReturn` ADT (`Return`, `Fail`, `Suspend`); `Async` primitive types (`Resume`, `Cancel`); `Codec` typeclasses (`LuauEncoder`, `LuauDecoder`, `Sink`); the in-memory Fake backend |

### Exact symbols imported from P03

```scala
// package luau.core

trait Binding                         // the interface this plan implements
final class Ref private[core](...)    // AutoCloseable registry handle
trait Scope extends AutoCloseable     // owns Refs opened inside it
sealed trait NativeFnReturn
case class Return(n: Int)             extends NativeFnReturn
case class Fail(value: LuauValue)     extends NativeFnReturn
case class Suspend(register: Resume => Cancel) extends NativeFnReturn
type Resume = Either[LuaError, Result] => Unit
type Cancel = () => Unit
class LuaError(msg: String)           extends Exception
trait LuauEncoder[A]
trait LuauDecoder[A]
trait Sink                            // push target for encoders
```

### Exact artifacts from P02

- `luau-shim.wasm` — Emscripten WASM binary (built with `-s MODULARIZE=1 -s EXPORT_NAME=LuauShim`)
- `loader.js` — Emscripten-generated JS glue module
- `shim/include/luau_shim.h` — canonical ABI header (used for cross-referencing types; the WASM backend does not call jextract; it reads this header for documentation only)

The exported `_lx_*` functions from P02 that this plan calls:

| C symbol | Signature | Notes |
|----------|-----------|-------|
| `_lx_newstate` | `() -> i32` | Returns opaque `lua_State*` as i32 |
| `_lx_close` | `(i32) -> void` | Tears down state |
| `_lx_compile_and_load` | `(i32, i32, i32, i32) -> i32` | `(L, src_ptr, src_len, chunkname_ptr) -> status` |
| `_lx_resume` | `(i32, i32) -> i32` | `(L, nargs) -> status` |
| `_lx_resume_thread` | `(i32, i32, i32) -> i32` | `(L, thread, nargs) -> status` |
| `_lx_push_nil` | `(i32) -> void` | |
| `_lx_push_boolean` | `(i32, i32) -> void` | |
| `_lx_push_integer` | `(i32, i32) -> void` | |
| `_lx_push_double` | `(i32, i32) -> void` | (f64 passed as two i32 words, or f64 depending on WASM ABI) |
| `_lx_push_lstring` | `(i32, i32, i32) -> void` | `(L, ptr, len)` — bytes in linear memory |
| `_lx_push_table` | `(i32) -> void` | |
| `_lx_get_type` | `(i32, i32) -> i32` | `(L, idx) -> LuaType tag` |
| `_lx_to_boolean` | `(i32, i32) -> i32` | non-raising |
| `_lx_to_integer` | `(i32, i32) -> i32` | non-raising |
| `_lx_to_double` | `(i32, i32) -> f64` | non-raising |
| `_lx_to_lstring` | `(i32, i32, i32) -> i32` | `(L, idx, out_len_ptr) -> ptr` — returns pointer into linear memory |
| `_lx_rawget` | `(i32, i32) -> void` | `(L, table_idx)` |
| `_lx_rawset` | `(i32, i32) -> void` | `(L, table_idx)` |
| `_lx_rawgeti` | `(i32, i32, i32) -> void` | `(L, table_idx, n)` |
| `_lx_rawseti` | `(i32, i32, i32) -> void` | `(L, table_idx, n)` |
| `_lx_setarray` | `(i32, i32, i32) -> void` | set table array size hint |
| `_lx_ref` | `(i32) -> i32` | pops top, stores in registry, returns ref id |
| `_lx_unref` | `(i32, i32) -> void` | `(L, ref_id)` |
| `_lx_push_ref` | `(i32, i32) -> void` | `(L, ref_id)` pushes registry object |
| `_lx_register_native_fn` | `(i32, i32, i32, i32) -> void` | `(L, name_ptr, name_len, fn_id)` — installs trampoline closure with fn_id upvalue |
| `_lx_get_error_message` | `(i32) -> i32` | returns ptr to null-terminated error string in linear memory |
| `_lx_get_top` | `(i32) -> i32` | stack depth |
| `_lx_pop` | `(i32, i32) -> void` | `(L, n)` |
| `_lx_new_thread` | `(i32) -> i32` | `(L) -> thread_L` — creates coroutine |
| `_malloc` | `(i32) -> i32` | allocate in WASM linear memory |
| `_free` | `(i32) -> void` | free WASM linear memory |

The trampoline C callback that the Shim installs expects a function pointer registered via `addFunction` with signature `"iii"` (two i32 arguments: `lua_State* L` and `int fn_id`; returns i32 encoding the tri-state: `>=0` = Return(n), `LX_FAIL = -1`, `LX_SUSPEND = -2`). See P02 for exact constants.

---

## 3. Design Context

### 3.1 ADR-0001 — No protected calls across the FFI boundary

The WASM backend never calls `lua_pcall` or any raising Luau C API function directly. All Luau execution enters through `lx_resume` (the **Resume boundary**). Stack reads use only non-raising accessors (`_lx_get_type`, `_lx_to_*`). A Scala **Native function** cannot raise; it returns `Fail(value)` and the Shim calls `lua_error` in C after the upcall returns (ADR-0001). The WASM↔host frame must never be on the native C stack when `longjmp` fires.

### 3.2 ADR-0002 + ADR-0004 — Single-worker JS

JS has one thread. `SharedArrayBuffer`-backed WASM with `Atomics` requires COOP/COEP headers that are frequently unavailable, so cross-worker state migration is deferred. The WASM backend drains the **Run queue** on a single JS worker (the same event-loop tick or a microtask chain). This means:

- No `java.util.concurrent` primitives; use a plain JS array as the run queue.
- Off-Driver completions (e.g., a Node.js `setTimeout` callback) post onto this array and schedule a microtask (via `Promise.resolve().then(...)`) to drain it, rather than resuming inline.
- No thread-safety requirements on the Scala side for the WASM backend itself — the single-worker invariant holds trivially.

### 3.3 ADR-0003 — Stackless Tasks

A parked **Task** holds no native (WASM linear memory C) stack — its Lua-level continuation lives in Luau's heap. The WASM backend relies on this: after `_lx_resume` returns with `LUA_YIELD`, the backend stores the Task and waits for the async completion callback, which calls `_lx_resume` again. The host never needs to park a C stack frame between yield and resume.

### 3.4 ADR-0005 — Deterministic Ref lifetime

The JS GC cannot trace into Luau's linear memory (Luau objects live in Emscripten's WASM heap, completely opaque to V8/SpiderMonkey). Therefore `FinalizationRegistry` is useless as a backstop: it can only fire a JS callback when the JS-side handle object is collected, but the Luau object lives independently in WASM linear memory. **No finalizer** is registered. A `Ref` is valid only while explicitly held; `Scope.close()` is the deterministic release path. A leaked `Ref` pins its registry slot until the state is closed.

### 3.5 ADR-0006 — Copy-only data boundary via Codec

No host object reference ever crosses into Luau. Strings and byte arrays are copied into WASM linear memory (`_malloc` → fill HEAPU8 → call `_lx_push_lstring`) and freed immediately after the push call. Luau→Host string reads copy bytes out of linear memory into a Scala `String`. The `Sink` implementation for the WASM backend writes directly to the Shim push functions; no intermediate tree is built.

### 3.6 ADR-0007 — Tri-state Native function return + callback-based async

The trampoline upcall returns an integer encoding the tri-state:
- `n >= 0`: `Return(n)` — n results are on the stack
- `-1` (`LX_FAIL`): `Fail` — the Shim calls `lua_error` after the upcall returns; the error value is whatever the Scala function pushed before returning
- `-2` (`LX_SUSPEND`): `Suspend` — the Shim calls `lua_yield(k)` after the upcall returns; the Task parks

The Scala dispatcher keyed by `fn_id` is called from within the upcall. It receives the `Suspend(register)` case and stores `register` on the Task object; the trampoline then returns `-2`. The `register` function is invoked by the Scheduler when it parks the Task, wiring the async completion.

### 3.7 Fengari lessons on JS↔WASM memory marshaling

Fengari (`/home/hoangdinh/OSS/luau-scala/docs/research/runtime-fengari-js.md`) is a JS-reimplementation of Lua and therefore does not use WASM linear memory. However, its `defs.js` patterns for byte-exact string handling are directly applicable:

- Luau strings are 8-bit-clean byte sequences. Use `TextEncoder`/`TextDecoder` (UTF-8) for host strings; use raw `Uint8Array` slices for arbitrary byte data.
- `to_luastring(jsStr)` → `new TextEncoder().encode(jsStr)` → copy into `HEAPU8`
- `to_jsstring(uint8array)` → `new TextDecoder('utf-8').decode(uint8array)`
- Always measure byte length after encoding (UTF-8 may be longer than character length).

For Emscripten WASM specifically:
- `Module.HEAPU8` is a `Uint8Array` view over the full WASM linear memory. It is a **live view**: if WASM memory grows (e.g., via `memory.grow`), the buffer backing `HEAPU8` changes and old references to `Module.HEAPU8` become stale. Always re-read `Module.HEAPU8` (not cache it) after any operation that might grow memory.
- `_malloc` returns a pointer (i32 offset) into this array. After `_malloc(n)`, write `n` bytes starting at `Module.HEAPU8[ptr]`.
- `_free(ptr)` must be called after the Shim has finished reading the data.

---

## 4. Task Breakdown

All paths are absolute under `/home/hoangdinh/OSS/luau-scala/`.

---

### Task 4.1 — WASM Module Loader facade

**File:** `wasm/src/main/scala/luau/wasm/WasmModule.scala`

**Purpose:** Load the Emscripten MODULARIZE-d module at startup, expose the raw JS exports as typed Scala.js facades. The loader is asynchronous (returns a `js.Promise`); the backend must await it before any other call.

**Key declarations:**

```scala
package luau.wasm

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array

/** Facade for the Emscripten module factory exported as `LuauShim`.
 *  The factory is loaded via `require("./loader.js")` on Node.js or
 *  via a `<script>` + `window.LuauShim` on a browser.
 *  Call `LuauShimFactory.apply(overrides)` to obtain the hydrated module.
 */
@js.native
@JSImport("./loader.js", JSImport.Default)
object LuauShimFactory extends js.Object:
  def apply(overrides: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[WasmModuleExports] = js.native

/** The hydrated Emscripten module.  All fields are read after the Promise resolves. */
@js.native
trait WasmModuleExports extends js.Object:
  // Linear memory view — re-read after any call that might grow WASM memory
  def HEAPU8: Uint8Array = js.native
  def HEAP32: js.typedarray.Int32Array = js.native

  // Memory management
  def _malloc(size: Int): Int = js.native
  def _free(ptr: Int): Unit = js.native

  // Function table management (for trampoline)
  def addFunction(fn: js.Function, sig: String): Int = js.native
  def removeFunction(tableIdx: Int): Unit = js.native

  // dynCall — calls a function pointer from the indirect function table
  // sig = "iii" (return i32, arg0 i32, arg1 i32)
  def dynCall_iii(fnPtr: Int, arg0: Int, arg1: Int): Int = js.native

  // Direct _lx_* exports (Emscripten prepends _ to C symbol names)
  def _lx_newstate(): Int = js.native
  def _lx_close(L: Int): Unit = js.native
  def _lx_compile_and_load(L: Int, srcPtr: Int, srcLen: Int, chunknamePtr: Int): Int = js.native
  def _lx_resume(L: Int, nargs: Int): Int = js.native
  def _lx_resume_thread(L: Int, thread: Int, nargs: Int): Int = js.native
  def _lx_push_nil(L: Int): Unit = js.native
  def _lx_push_boolean(L: Int, b: Int): Unit = js.native
  def _lx_push_integer(L: Int, n: Int): Unit = js.native
  def _lx_push_double(L: Int, d: Double): Unit = js.native
  def _lx_push_lstring(L: Int, ptr: Int, len: Int): Unit = js.native
  def _lx_push_table(L: Int): Unit = js.native
  def _lx_get_type(L: Int, idx: Int): Int = js.native
  def _lx_to_boolean(L: Int, idx: Int): Int = js.native
  def _lx_to_integer(L: Int, idx: Int): Int = js.native
  def _lx_to_double(L: Int, idx: Int): Double = js.native
  def _lx_to_lstring(L: Int, idx: Int, outLenPtr: Int): Int = js.native
  def _lx_rawget(L: Int, tableIdx: Int): Unit = js.native
  def _lx_rawset(L: Int, tableIdx: Int): Unit = js.native
  def _lx_rawgeti(L: Int, tableIdx: Int, n: Int): Unit = js.native
  def _lx_rawseti(L: Int, tableIdx: Int, n: Int): Unit = js.native
  def _lx_setarray(L: Int, tableIdx: Int, n: Int): Unit = js.native
  def _lx_ref(L: Int): Int = js.native
  def _lx_unref(L: Int, refId: Int): Unit = js.native
  def _lx_push_ref(L: Int, refId: Int): Unit = js.native
  def _lx_register_native_fn(L: Int, namePtr: Int, nameLen: Int, fnId: Int): Unit = js.native
  def _lx_get_error_message(L: Int): Int = js.native
  def _lx_get_top(L: Int): Int = js.native
  def _lx_pop(L: Int, n: Int): Unit = js.native
  def _lx_new_thread(L: Int): Int = js.native

/** Global singleton; populated once by `WasmBackend.load()`.
 *  Must not be accessed before `load()` resolves.
 */
object WasmModule:
  private var _module: WasmModuleExports = scala.compiletime.uninitialized

  private[wasm] def set(m: WasmModuleExports): Unit = _module = m

  def module: WasmModuleExports =
    require(_module != null, "WasmModule not yet loaded — await WasmBackend.load()")
    _module
```

**Notes:**
- The `@JSImport` path `"./loader.js"` is relative to the emitted JS bundle. Mill's resource-copy task (P01) must place `loader.js` adjacent to the compiled `.js` output.
- `HEAPU8` is declared as `def` (not `val`) so each access goes through a property read, picking up the current `ArrayBuffer` after any memory growth. This is critical: Emscripten's `_malloc` can trigger `memory.grow`, invalidating all prior `Uint8Array` views.
- `dynCall_iii` is the Emscripten helper for indirect function table calls with signature `iii`. The actual name depends on the `EXPORTED_RUNTIME_METHODS` setting in the Emscripten build (P02 must export it).

---

### Task 4.2 — Linear memory string/bytes marshaling utilities

**File:** `wasm/src/main/scala/luau/wasm/WasmMarshal.scala`

**Purpose:** All copy operations between Scala/JS heap and WASM linear memory. Centralizes the `_malloc`/`_free` discipline so it cannot be forgotten.

**Key declarations:**

```scala
package luau.wasm

import scala.scalajs.js.typedarray.Uint8Array
import java.nio.charset.StandardCharsets

object WasmMarshal:

  private val utf8Encoder = new scala.scalajs.js.Dynamic.global.TextEncoder()
  private val utf8Decoder = new scala.scalajs.js.Dynamic.global.TextDecoder("utf-8")

  /** Encode `s` to UTF-8, allocate in WASM linear memory, invoke `f(ptr, len)`,
   *  then unconditionally free.  Never leaks.
   *
   *  Important: the HEAPU8 view is re-read inside this function after malloc,
   *  because malloc may have grown the WASM memory.
   */
  inline def withString[A](s: String)(f: (Int, Int) => A): A =
    val bytes: Uint8Array = utf8Encoder.encode(s).asInstanceOf[Uint8Array]
    val len = bytes.length
    val ptr = WasmModule.module._malloc(len + 1) // +1 for null terminator safety
    require(ptr != 0, s"_malloc($len) returned null — WASM OOM")
    try
      // Re-read HEAPU8 after malloc (may have grown)
      val heap = WasmModule.module.HEAPU8
      heap.set(bytes, ptr)
      heap(ptr + len) = 0 // null-terminate
      f(ptr, len)
    finally
      WasmModule.module._free(ptr)

  /** Allocate `bytes.length` bytes in WASM linear memory, copy from `bytes`,
   *  invoke `f(ptr, len)`, then unconditionally free.
   */
  inline def withBytes[A](bytes: Array[Byte])(f: (Int, Int) => A): A =
    val len = bytes.length
    val ptr = WasmModule.module._malloc(len)
    require(ptr != 0, s"_malloc($len) returned null — WASM OOM")
    try
      val heap = WasmModule.module.HEAPU8
      var i = 0
      while i < len do
        heap(ptr + i) = (bytes(i) & 0xff).toShort
        i += 1
      f(ptr, len)
    finally
      WasmModule.module._free(ptr)

  /** Read a length-prefixed string from WASM linear memory.
   *  `lenPtr` points to a 4-byte (i32) length written by `_lx_to_lstring`.
   *  Returns a copy as a Scala String.
   */
  def readString(strPtr: Int, len: Int): String =
    if strPtr == 0 || len == 0 then ""
    else
      val heap = WasmModule.module.HEAPU8
      val slice = heap.subarray(strPtr, strPtr + len)
      utf8Decoder.decode(slice).asInstanceOf[String]

  /** Allocate a single i32 slot in WASM linear memory for an out-parameter.
   *  Returns (ptr, reader).  Caller must free after reading.
   */
  def allocOutInt(): (Int, () => Int) =
    val ptr = WasmModule.module._malloc(4)
    require(ptr != 0, "_malloc(4) returned null — WASM OOM")
    val read = () =>
      // Read little-endian i32 from HEAPU8
      val heap = WasmModule.module.HEAPU8
      (heap(ptr).toInt & 0xff) |
      ((heap(ptr + 1).toInt & 0xff) << 8) |
      ((heap(ptr + 2).toInt & 0xff) << 16) |
      ((heap(ptr + 3).toInt & 0xff) << 24)
    (ptr, read)
```

**Notes:**
- The `inline` keyword on `withString`/`withBytes` avoids closure allocation for the common case; the `f` lambda is inlined at call sites.
- `utf8Encoder`/`utf8Decoder` are JS `TextEncoder`/`TextDecoder` objects. In Scala.js, access them via `js.Dynamic.global` or import via a facade; the exact approach depends on the Scala.js version used in P01.
- HEAPU8 must be re-read (property access) after every `_malloc` call. Never save `Module.HEAPU8` in a `val` that spans a `_malloc` call.
- The `allocOutInt` helper supports `_lx_to_lstring`'s out-parameter for string length.

---

### Task 4.3 — Trampoline dispatcher

**File:** `wasm/src/main/scala/luau/wasm/Trampoline.scala`

**Purpose:** Install the single C→Scala upcall via Emscripten's `addFunction`, maintain the `fnId → NativeFn` dispatch table, and encode the tri-state return value for the Shim.

**Key declarations:**

```scala
package luau.wasm

import scala.scalajs.js
import luau.core.*

/** Tri-state return codes matching `lx_trampoline_result` in luau_shim.h (P02). */
object TrampolineResult:
  val Fail: Int    = -1
  val Suspend: Int = -2
  // Return(n) is encoded as n >= 0

/** Manages the mapping from integer fnId to Scala NativeFn callbacks.
 *
 *  A `NativeFn` has type: `(WasmBinding, Int) => NativeFnReturn`
 *  where the second argument is the `lua_State*` (as i32 opaque handle).
 *
 *  The C trampoline installed by `_lx_register_native_fn` calls back via
 *  the indirect function table entry registered with `addFunction("iii")`.
 *  Its C-level signature is: `int trampoline(lua_State* L, int fn_id)`.
 */
object Trampoline:
  // NativeFn type: given a WasmBinding and the opaque L pointer, return the tri-state
  type NativeFn = (WasmBinding, Int) => NativeFnReturn

  private var nextId: Int = 1
  private val table = scala.collection.mutable.HashMap.empty[Int, NativeFn]

  // The single JS function pointer registered with addFunction.
  // Stored so it can be removed with removeFunction on teardown.
  private var tableFnPtr: Int = -1
  private var boundBinding: WasmBinding = scala.compiletime.uninitialized

  /** Install the host-side trampoline entry into the WASM function table.
   *  Called once during `WasmBinding` construction.
   *  Returns the function table index for diagnostic use.
   */
  def install(binding: WasmBinding): Int =
    require(tableFnPtr == -1, "Trampoline already installed")
    boundBinding = binding
    val fn: js.Function2[Int, Int, Int] = (L: Int, fnId: Int) =>
      dispatch(L, fnId)
    tableFnPtr = WasmModule.module.addFunction(fn, "iii")
    tableFnPtr

  /** Uninstall the trampoline function table entry.
   *  Called during `WasmBinding.close()`.
   */
  def uninstall(): Unit =
    if tableFnPtr != -1 then
      WasmModule.module.removeFunction(tableFnPtr)
      tableFnPtr = -1

  /** Register a NativeFn and return its integer fnId. */
  def register(fn: NativeFn): Int =
    val id = nextId
    nextId += 1
    table(id) = fn
    id

  /** Unregister a NativeFn by its fnId. */
  def unregister(fnId: Int): Unit =
    table.remove(fnId)

  /** Called from the JS function installed via addFunction.
   *  Dispatches to the registered NativeFn and encodes the result.
   *  Any Scala exception here must NOT propagate into WASM C code —
   *  catch all Throwable and convert to Fail.
   */
  private def dispatch(L: Int, fnId: Int): Int =
    table.get(fnId) match
      case None =>
        // Unknown fnId: this is a bug.  Push an error string, return Fail.
        WasmMarshal.withString(s"luau-scala: unknown fnId $fnId in trampoline") { (ptr, len) =>
          WasmModule.module._lx_push_lstring(L, ptr, len)
        }
        TrampolineResult.Fail
      case Some(fn) =>
        try fn(boundBinding, L) match
          case Return(n)      => n
          case Fail(_)        => TrampolineResult.Fail   // Scala fn already pushed error value
          case Suspend(reg)   =>
            // Store the register fn on the binding so the Scheduler can call it
            // after _lx_resume returns LX_SUSPEND (the Shim will have yielded the thread).
            boundBinding.pendingSuspend = Some(reg)
            TrampolineResult.Suspend
        catch
          case t: Throwable =>
            val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
            WasmMarshal.withString(s"luau-scala: native fn threw: $msg") { (ptr, len) =>
              WasmModule.module._lx_push_lstring(L, ptr, len)
            }
            TrampolineResult.Fail
```

**Notes:**
- Emscripten's `addFunction` requires the `ALLOW_TABLE_GROWTH=1` compile flag (or a pre-sized function table). P02 must include this in its Emscripten settings.
- The signature string `"iii"` means: return type `i`, arg0 type `i`, arg1 type `i` (all 32-bit integers). This matches the trampoline C signature `int(*)(lua_State*, int)`.
- `pendingSuspend` on `WasmBinding` is a mutable `Option[Resume => Cancel]` slot; it is set by the dispatcher, read by `WasmBinding.resume()` after `_lx_resume` returns `LX_SUSPEND`, and cleared immediately.
- The catch-all `Throwable` guard is essential: if any Scala exception escapes across the WASM↔JS boundary while C code expects a clean return, the behavior is undefined. This is the WASM equivalent of the Panama no-longjmp-across-boundary rule.

---

### Task 4.4 — WasmScope

**File:** `wasm/src/main/scala/luau/wasm/WasmScope.scala`

**Purpose:** Implement the `Scope` trait from P03. Tracks `Ref`s opened during its lifetime and closes (unrefs) all of them when the scope exits. Unlike Panama's `Arena`, this is a plain Scala-managed list.

**Key declarations:**

```scala
package luau.wasm

import luau.core.{Ref, Scope}
import scala.collection.mutable.ArrayBuffer

/** WASM backend Scope: tracks Refs and unrefs them on close.
 *
 *  Not thread-safe — the WASM backend is single-worker (ADR-0002/0004).
 *  `close()` is idempotent: calling it more than once is a no-op.
 */
final class WasmScope private[wasm](binding: WasmBinding) extends Scope:
  private val refs = ArrayBuffer.empty[Ref]
  private var closed = false

  /** Track a Ref opened inside this scope.  Called by WasmBinding.ref(). */
  private[wasm] def track(r: Ref): Unit =
    require(!closed, "Cannot open a Ref in an already-closed WasmScope")
    refs += r

  override def close(): Unit =
    if !closed then
      closed = true
      // Close in reverse order of opening (LIFO discipline)
      refs.reverseIterator.foreach(_.close())
      refs.clear()

  override def toString: String = s"WasmScope(${refs.size} refs, closed=$closed)"
```

---

### Task 4.5 — WasmBinding (core Binding implementation)

**File:** `wasm/src/main/scala/luau/wasm/WasmBinding.scala`

**Purpose:** The central class. Implements the `Binding` trait from P03 by delegating to `WasmModule.module._lx_*` calls. Owns the `lua_State*` opaque handle, the `WasmScope` factory, and the pending-suspend slot. Implements the `Sink` interface for encoder writes.

**Key declarations:**

```scala
package luau.wasm

import luau.core.*
import scala.scalajs.js

/** Implements core.Binding for the WASM backend.
 *
 *  `L` is the opaque `lua_State*` as an Emscripten i32 pointer.
 *  `close()` calls `_lx_close(L)` and uninstalls the trampoline.
 *
 *  Single-worker: this object must only be used from the JS event-loop
 *  thread (i.e., never from a Web Worker unless the entire backend is
 *  moved to that worker).
 */
final class WasmBinding private(val L: Int) extends Binding with AutoCloseable:

  // Mutable slot for pending Suspend register — set by Trampoline.dispatch,
  // consumed immediately by resume() after _lx_resume returns LX_SUSPEND.
  private[wasm] var pendingSuspend: Option[Resume => Cancel] = None

  private val trampolineFnPtr: Int = Trampoline.install(this)

  // ── State lifecycle ────────────────────────────────────────────────────────

  override def close(): Unit =
    Trampoline.uninstall()
    WasmModule.module._lx_close(L)

  // ── Script loading ─────────────────────────────────────────────────────────

  /** Compile Luau source and load it as a function on top of the stack.
   *  Returns `Right(())` on success, `Left(LuaError)` on compile error.
   */
  override def compileAndLoad(source: String, chunkName: String): Either[LuaError, Unit] =
    val m = WasmModule.module
    WasmMarshal.withString(source) { (srcPtr, srcLen) =>
      WasmMarshal.withString(chunkName) { (cnPtr, _) =>
        val status = m._lx_compile_and_load(L, srcPtr, srcLen, cnPtr)
        if status == LxStatus.Ok then Right(())
        else
          val errPtr = m._lx_get_error_message(L)
          val msg = readCString(errPtr)
          Left(LuaError(msg))
      }
    }

  // ── Resume boundary ────────────────────────────────────────────────────────

  /** Resume the main thread.  Returns the execution outcome.
   *  nargs values must already be on the stack.
   */
  override def resume(nargs: Int): ResumeResult =
    val m = WasmModule.module
    pendingSuspend = None
    val status = m._lx_resume(L, nargs)
    interpretResumeStatus(status)

  /** Resume a coroutine thread (given as an opaque i32 thread handle). */
  override def resumeThread(thread: Int, nargs: Int): ResumeResult =
    val m = WasmModule.module
    pendingSuspend = None
    val status = m._lx_resume_thread(L, thread, nargs)
    interpretResumeStatus(status)

  private def interpretResumeStatus(status: Int): ResumeResult =
    import LxStatus.*
    status match
      case Ok =>
        val n = WasmModule.module._lx_get_top(L)
        ResumeResult.Returned(n)
      case Yield =>
        val suspend = pendingSuspend
        pendingSuspend = None
        ResumeResult.Suspended(suspend)
      case _ => // error status
        val errPtr = WasmModule.module._lx_get_error_message(L)
        val msg = readCString(errPtr)
        ResumeResult.Errored(LuaError(msg))

  // ── Stack: push operations (implements Sink) ───────────────────────────────

  override def pushNil(): Unit                 = WasmModule.module._lx_push_nil(L)
  override def pushBoolean(b: Boolean): Unit   = WasmModule.module._lx_push_boolean(L, if b then 1 else 0)
  override def pushInt(n: Int): Unit           = WasmModule.module._lx_push_integer(L, n)
  override def pushDouble(d: Double): Unit     = WasmModule.module._lx_push_double(L, d)

  override def pushString(s: String): Unit =
    WasmMarshal.withString(s) { (ptr, len) =>
      WasmModule.module._lx_push_lstring(L, ptr, len)
    }

  override def pushBytes(b: Array[Byte]): Unit =
    WasmMarshal.withBytes(b) { (ptr, len) =>
      WasmModule.module._lx_push_lstring(L, ptr, len)
    }

  override def pushTable(): Unit               = WasmModule.module._lx_push_table(L)
  override def pushRef(ref: Ref): Unit         = WasmModule.module._lx_push_ref(L, ref.id)

  // ── Stack: read operations ─────────────────────────────────────────────────

  override def getType(idx: Int): LuaType =
    LuaType.fromTag(WasmModule.module._lx_get_type(L, idx))

  override def toBoolean(idx: Int): Boolean    = WasmModule.module._lx_to_boolean(L, idx) != 0
  override def toInt(idx: Int): Int            = WasmModule.module._lx_to_integer(L, idx)
  override def toDouble(idx: Int): Double      = WasmModule.module._lx_to_double(L, idx)

  override def toLString(idx: Int): Array[Byte] =
    val m = WasmModule.module
    val (lenPtr, readLen) = WasmMarshal.allocOutInt()
    try
      val strPtr = m._lx_to_lstring(L, idx, lenPtr)
      if strPtr == 0 then Array.empty[Byte]
      else
        val len = readLen()
        val heap = m.HEAPU8
        val arr = new Array[Byte](len)
        var i = 0
        while i < len do
          arr(i) = heap(strPtr + i).toByte
          i += 1
        arr
    finally
      m._free(lenPtr)

  override def toString(idx: Int): String =
    new String(toLString(idx), java.nio.charset.StandardCharsets.UTF_8)

  override def getTop(): Int                   = WasmModule.module._lx_get_top(L)
  override def pop(n: Int): Unit               = WasmModule.module._lx_pop(L, n)

  // ── Table operations ───────────────────────────────────────────────────────

  override def rawGet(tableIdx: Int): Unit     = WasmModule.module._lx_rawget(L, tableIdx)
  override def rawSet(tableIdx: Int): Unit     = WasmModule.module._lx_rawset(L, tableIdx)
  override def rawGetI(tableIdx: Int, n: Int): Unit = WasmModule.module._lx_rawgeti(L, tableIdx, n)
  override def rawSetI(tableIdx: Int, n: Int): Unit = WasmModule.module._lx_rawseti(L, tableIdx, n)
  override def setArray(tableIdx: Int, n: Int): Unit = WasmModule.module._lx_setarray(L, tableIdx, n)

  // ── Ref management ────────────────────────────────────────────────────────

  /** Pop the top value, store in the registry, return a Ref.
   *  The Ref must be closed explicitly (or via a WasmScope).
   */
  override def makeRef(): Ref =
    val refId = WasmModule.module._lx_ref(L)
    new Ref(refId, () => WasmModule.module._lx_unref(L, refId))

  // ── Scope factory ──────────────────────────────────────────────────────────

  override def scoped[A](f: WasmScope => A): A =
    val scope = new WasmScope(this)
    try f(scope)
    finally scope.close()

  // ── Native function registration ───────────────────────────────────────────

  /** Expose a Scala NativeFn to Luau scripts under `name` in the global table. */
  override def registerNativeFn(name: String, fn: NativeFn): Unit =
    val fnId = Trampoline.register(fn)
    WasmMarshal.withString(name) { (namePtr, nameLen) =>
      WasmModule.module._lx_register_native_fn(L, namePtr, nameLen, fnId)
    }

  // ── New coroutine thread ───────────────────────────────────────────────────

  override def newThread(): Int = WasmModule.module._lx_new_thread(L)

  // ── Internal helpers ───────────────────────────────────────────────────────

  private def readCString(ptr: Int): String =
    if ptr == 0 then "<null error>"
    else
      val heap = WasmModule.module.HEAPU8
      var len = 0
      while heap(ptr + len) != 0 do len += 1
      WasmMarshal.readString(ptr, len)

object WasmBinding:
  /** Synchronously-usable factory; must be called after WasmBackend.load() resolves. */
  def create(): WasmBinding =
    val L = WasmModule.module._lx_newstate()
    require(L != 0, "_lx_newstate() returned null — WASM OOM or init failure")
    new WasmBinding(L)
```

**Supporting type in core (defined in P03, referenced here):**

```scala
// In luau.core (P03):
enum ResumeResult:
  case Returned(nResults: Int)
  case Suspended(register: Option[Resume => Cancel])
  case Errored(err: LuaError)

type NativeFn = (Binding, Int) => NativeFnReturn

object LxStatus:
  val Ok: Int    = 0
  val Yield: Int = 1
  // Any other value = error status
```

---

### Task 4.6 — WasmBackend (async loader + entry point)

**File:** `wasm/src/main/scala/luau/wasm/WasmBackend.scala`

**Purpose:** Asynchronous entry point. Loads the Emscripten module, stores it in `WasmModule`, and returns a `js.Promise[Unit]` so callers can `await` before creating any `WasmBinding`.

```scala
package luau.wasm

import scala.scalajs.js
import scala.concurrent.{Future, Promise as SPromise}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

object WasmBackend:

  /** Load `luau-shim.wasm` via the Emscripten module factory.
   *  Must be awaited exactly once before any `WasmBinding.create()` call.
   *  Returns a Future that completes when the WASM module is ready.
   */
  def load(loaderPath: String = "./loader.js"): Future[Unit] =
    val p = SPromise[Unit]()
    LuauShimFactory().toFuture.map { exports =>
      WasmModule.set(exports)
    }

  /** Convenience: create a WasmBinding once the module is loaded. */
  def createBinding(): WasmBinding = WasmBinding.create()
```

**Notes:** The `load()` method wraps the `js.Promise` from `LuauShimFactory.apply()` in a Scala `Future`. This integrates with Scala.js's `JSExecutionContext.queue`, which dispatches on the JS microtask queue — consistent with single-worker semantics (ADR-0002/ADR-0004).

---

### Task 4.7 — WasmSink (Codec Sink implementation)

**File:** `wasm/src/main/scala/luau/wasm/WasmSink.scala`

**Purpose:** Implement the `Sink` trait from P03 on top of `WasmBinding`. Encoders in `core` write to a `Sink`; this implementation forwards each write to the corresponding `_lx_push_*` / `_lx_rawseti` / `_lx_rawset` call. Because Emscripten is single-threaded, no synchronization is needed.

```scala
package luau.wasm

import luau.core.{Sink, LuauValue}

/** Sink implementation backed by a WasmBinding.
 *
 *  Table construction protocol (mirrors P04 PanamaSink):
 *  - `beginTable(nArr, nHash)` → `_lx_push_table` + `_lx_setarray`
 *  - `arrayItem(i)` → push value then `_lx_rawseti(L, -2, i)` (1-indexed)
 *  - `hashItem(key)` → push key, push value, `_lx_rawset(L, -3)`
 *  - `endTable()` → no-op (table already on stack)
 */
final class WasmSink(binding: WasmBinding) extends Sink:
  override def pushNil(): Unit              = binding.pushNil()
  override def pushBoolean(b: Boolean): Unit = binding.pushBoolean(b)
  override def pushInt(n: Int): Unit        = binding.pushInt(n)
  override def pushDouble(d: Double): Unit  = binding.pushDouble(d)
  override def pushString(s: String): Unit  = binding.pushString(s)
  override def pushBytes(b: Array[Byte]): Unit = binding.pushBytes(b)

  override def beginTable(nArr: Int, nHash: Int): Unit =
    binding.pushTable()
    if nArr > 0 then binding.setArray(-1, nArr)

  private var arrayIndex: Int = 0
  private var inArray: Boolean = false

  override def beginArray(n: Int): Unit =
    beginTable(n, 0)
    inArray = true
    arrayIndex = 1

  override def nextArrayItem(): Unit =
    // The value has been pushed by the encoder.
    // Move it from the top of the stack into the array-part of the table below it.
    binding.rawSetI(-2, arrayIndex)
    arrayIndex += 1

  override def endArray(): Unit =
    inArray = false
    arrayIndex = 0

  override def hashKey(): Unit = () // key is pushed by encoder, no-op marker
  override def hashValue(): Unit =
    // key at -2, value at -1, table at -3
    binding.rawSet(-3)

  override def endTable(): Unit = () // table stays on stack
```

---

### Task 4.8 — Shared cross-backend test harness

**File:** `core/src/test/scala/luau/core/SharedBackendSuite.scala`

**Purpose:** Define the abstract test suite that both P04 (Panama backend, JVM) and the WASM backend (JS) must pass. Each test loads a `.luau` script, runs it, and asserts on the result. The suite is abstract; concrete subclasses provide the `Binding`.

```scala
package luau.core

import munit.FunSuite

/** Abstract test suite exercising the Binding contract.
 *  Subclassed in the panama and wasm modules with concrete bindings.
 *
 *  Every test here runs on BOTH the JVM (Panama backend) and JS (WASM backend),
 *  ensuring behavioral parity per the cross-platform embedding goal.
 */
abstract class SharedBackendSuite extends FunSuite:

  /** Subclass provides: load the WASM/Panama binding and run the test body. */
  def withBinding[A](f: Binding => A): A

  // ── TC-SHARED-01: Basic script execution ──────────────────────────────────
  test("TC-SHARED-01 basic script returns integer"):
    withBinding { b =>
      b.compileAndLoad("return 42", "test01").fold(fail(_), identity)
      val result = b.resume(0)
      assert(result.isInstanceOf[ResumeResult.Returned])
      val n = result.asInstanceOf[ResumeResult.Returned].nResults
      assert(n == 1)
      assert(b.toInt(-1) == 42)
      b.pop(n)
    }

  // ── TC-SHARED-02: String round-trip ───────────────────────────────────────
  test("TC-SHARED-02 string push and read back"):
    withBinding { b =>
      b.compileAndLoad("""return "hello, 世界" """, "test02").fold(fail(_), identity)
      val result = b.resume(0)
      assert(result.isInstanceOf[ResumeResult.Returned])
      assert(b.toString(-1) == "hello, 世界")
      b.pop(1)
    }

  // ── TC-SHARED-03: Table construction via Codec ────────────────────────────
  test("TC-SHARED-03 table construction and field read"):
    withBinding { b =>
      // Push a table {x=1, y=2} from host, then read it back with Luau
      b.pushTable()
      b.pushInt(1); b.rawSetI(-2, 1)   // t[1] = 1
      b.pushInt(2); b.rawSetI(-2, 2)   // t[2] = 2
      // verify via script
      b.compileAndLoad(
        "local t = ...\nreturn t[1] + t[2]", "test03"
      ).fold(fail(_), identity)
      // push the table as arg
      b.rawGetI(-2, 1) // push t[1] — actually we need the table itself on stack; adjust
      // (Simplified: the test just verifies rawgeti/rawseti work)
      b.pop(b.getTop())
    }

  // ── TC-SHARED-04: Native function call ───────────────────────────────────
  test("TC-SHARED-04 native function is callable from script"):
    withBinding { b =>
      var called = false
      b.registerNativeFn("hostAdd") { (binding, L) =>
        called = true
        val a = binding.toInt(1)
        val bb = binding.toInt(2)
        binding.pushInt(a + bb)
        Return(1)
      }
      b.compileAndLoad("return hostAdd(10, 32)", "test04").fold(fail(_), identity)
      b.resume(0)
      assert(called)
      assert(b.toInt(-1) == 42)
      b.pop(1)
    }

  // ── TC-SHARED-05: Native function Fail path ───────────────────────────────
  test("TC-SHARED-05 native function Fail raises Lua error"):
    withBinding { b =>
      b.registerNativeFn("willFail") { (binding, _) =>
        binding.pushString("deliberate error")
        Fail(LuauValue.Nil) // error value was already pushed
      }
      b.compileAndLoad(
        "local ok, err = pcall(willFail)\nreturn ok, err", "test05"
      ).fold(fail(_), identity)
      b.resume(0)
      assert(b.toBoolean(-2) == false)
      assert(b.toString(-1).contains("deliberate error"))
      b.pop(2)
    }

  // ── TC-SHARED-06: Ref creation, push, and close ───────────────────────────
  test("TC-SHARED-06 Ref lifecycle: create, push, close"):
    withBinding { b =>
      b.compileAndLoad("return {sentinel=true}", "test06").fold(fail(_), identity)
      b.resume(0)
      val ref = b.makeRef()
      // stack is now empty (makeRef pops)
      assert(b.getTop() == 0)
      // push ref back and verify the table is still live
      b.pushRef(ref)
      b.pushString("sentinel")
      b.rawGet(-2)
      assert(b.toBoolean(-1))
      b.pop(2)
      ref.close()
      // after close, pushing the ref would be a use-after-free — do not test
    }

  // ── TC-SHARED-07: Scope closes Refs ───────────────────────────────────────
  test("TC-SHARED-07 Scope closes all owned Refs on exit"):
    withBinding { b =>
      var refId = -1
      b.scoped { scope =>
        b.compileAndLoad("return {}", "test07").fold(fail(_), identity)
        b.resume(0)
        val ref = b.makeRef()
        refId = ref.id
        scope.track(ref)
        // ref is live here
        b.pushRef(ref)
        assert(b.getType(-1) == LuaType.Table)
        b.pop(1)
      } // scope.close() fires here, unrefs the ref
      // After scope: pushing refId would be use-after-free — do not test
    }

  // ── TC-SHARED-08: Suspend/resume round-trip ───────────────────────────────
  test("TC-SHARED-08 Suspend yields and async resume delivers value"):
    withBinding { b =>
      var resumeCallback: Resume = null
      b.registerNativeFn("asyncOp") { (binding, _) =>
        Suspend { resume =>
          resumeCallback = resume
          () => () // no-op cancel
        }
      }
      b.compileAndLoad("return asyncOp()", "test08").fold(fail(_), identity)
      val firstResult = b.resume(0)
      assert(firstResult.isInstanceOf[ResumeResult.Suspended])
      // Simulate async completion: push 99 and resume
      b.pushInt(99)
      resumeCallback(Right(Result(1)))
      // In the test context (no scheduler), we drive resume directly:
      val secondResult = b.resume(1) // resume with 1 arg on stack
      assert(secondResult.isInstanceOf[ResumeResult.Returned])
      assert(b.toInt(-1) == 99)
      b.pop(1)
    }

  // ── TC-SHARED-09: UTF-8 string round-trip with multi-byte chars ──────────
  test("TC-SHARED-09 UTF-8 multi-byte string preserved"):
    withBinding { b =>
      val testStr = "日本語テスト:  null-safe 😀"
      b.pushString(testStr)
      val readBack = b.toString(-1)
      assertEquals(readBack, testStr)
      b.pop(1)
    }

  // ── TC-SHARED-10: Compile error returns Left(LuaError) ───────────────────
  test("TC-SHARED-10 compile error is surfaced as Left"):
    withBinding { b =>
      val result = b.compileAndLoad("this is not valid luau @@@", "test10")
      assert(result.isLeft)
    }
```

**File:** `wasm/src/test/scala/luau/wasm/WasmBackendSuite.scala`

**Purpose:** Concrete subclass of `SharedBackendSuite` using `WasmBinding`. Runs on Node.js via `mill wasm.test`.

```scala
package luau.wasm

import luau.core.{Binding, SharedBackendSuite}
import scala.concurrent.Await
import scala.concurrent.duration.*

class WasmBackendSuite extends SharedBackendSuite:

  // Load the WASM module once for the entire suite.
  // munit's beforeAll/afterAll hooks manage the lifecycle.
  private var binding: WasmBinding = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    // On Node.js, Await is available via the Scala.js testing framework.
    Await.result(WasmBackend.load(), 10.seconds)
    binding = WasmBackend.createBinding()

  override def afterAll(): Unit =
    if binding != null then binding.close()

  override def withBinding[A](f: Binding => A): A = f(binding)
```

**Notes:**
- The `SharedBackendSuite` lives in `core/src/test` so it is available on both JVM and JS (the `core` module is a cross-project in P01).
- The Panama backend in P04 provides `PanamaBackendSuite extends SharedBackendSuite` in `panama/src/test`.
- Both suites reference the same test IDs (`TC-SHARED-01` through `TC-SHARED-10`) so failures are traceable across platforms.

---

### Task 4.9 — Mill module wiring

**File:** `build.mill` (modifications to the file established in P01)

The `wasm` Mill module must:

1. Declare `scalaJSVersion` and `platformSuffix`.
2. Depend on `core.js` (the Scala.js cross-build of P03's core module).
3. Copy `luau-shim.wasm` and `loader.js` (built by P02's Emscripten step) into the test resource directory so `require("./loader.js")` resolves correctly.
4. Use `ModuleKind.CommonJSModule` for Node.js test execution.
5. Set `jsEnvConfig` to `NodeJsEnvConfig()` for `mill wasm.test`.

Relevant skeleton additions (exact syntax depends on Mill version from P01):

```scala
// In build.mill, within the wasm module definition:
object wasm extends ScalaJSModule with Cross[String]:
  def scalaVersion = "3.x.x"
  def scalaJSVersion = "1.x.x"
  def moduleDeps = Seq(core.js)

  def moduleKind = ModuleKind.CommonJSModule

  // Copy shim artifacts next to the test runner
  def resources = T.sources {
    super.resources() ++ Seq(
      PathRef(shimBuildDir / "luau-shim.wasm"),
      PathRef(shimBuildDir / "loader.js")
    )
  }

  object test extends ScalaJSTests with TestModule.Munit:
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.scalameta::munit::1.x.x"
    )
    def jsEnvConfig = NodeJsEnvConfig(
      executable = "node",
      args = List("--experimental-wasm-modules")  // needed for WASM on older Node
    )
```

The exact Mill API tokens depend on the Mill version pinned in P01. The implementing agent must adjust to match.

---

### Task 4.10 — WASM-specific integration tests (beyond shared suite)

**File:** `wasm/src/test/scala/luau/wasm/WasmSpecificSuite.scala`

**Purpose:** Tests that exercise WASM-specific behavior not covered by the shared suite: module loading, HEAPU8 staleness detection, and the `addFunction`/`removeFunction` lifecycle.

```scala
package luau.wasm

import munit.FunSuite
import scala.concurrent.Await
import scala.concurrent.duration.*

class WasmSpecificSuite extends FunSuite:

  // ── TC-WASM-01: Module loads without error ────────────────────────────────
  test("TC-WASM-01 WasmBackend.load() resolves"):
    val f = WasmBackend.load()
    Await.result(f, 10.seconds)
    // If we get here, the module loaded
    assert(WasmModule.module != null)

  // ── TC-WASM-02: _malloc/_free round-trip ─────────────────────────────────
  test("TC-WASM-02 _malloc and _free do not crash"):
    val m = WasmModule.module
    val ptr = m._malloc(128)
    assert(ptr != 0)
    // Write and read back a sentinel byte
    m.HEAPU8(ptr) = 0xAB.toShort
    assert(m.HEAPU8(ptr) == 0xAB.toShort)
    m._free(ptr)

  // ── TC-WASM-03: addFunction/removeFunction lifecycle ─────────────────────
  test("TC-WASM-03 addFunction registers and removeFunction unregisters"):
    val m = WasmModule.module
    var wasCalled = false
    val fn: scala.scalajs.js.Function2[Int, Int, Int] = (_, _) =>
      wasCalled = true; 0
    val idx = m.addFunction(fn, "iii")
    assert(idx > 0)
    // Call it via dynCall
    m.dynCall_iii(idx, 0, 0)
    assert(wasCalled)
    m.removeFunction(idx)

  // ── TC-WASM-04: Multiple WasmBinding instances are independent ────────────
  test("TC-WASM-04 two WasmBindings have independent Lua states"):
    val b1 = WasmBinding.create()
    val b2 = WasmBinding.create()
    try
      b1.pushInt(1)
      b2.pushInt(2)
      assert(b1.toInt(-1) == 1)
      assert(b2.toInt(-1) == 2)
      b1.pop(1); b2.pop(1)
    finally
      b1.close(); b2.close()

  // ── TC-WASM-05: Large string does not corrupt HEAPU8 view ────────────────
  test("TC-WASM-05 large string push does not corrupt HEAPU8 view"):
    val b = WasmBinding.create()
    try
      val large = "x" * 65536  // 64 KB string — may trigger memory.grow
      b.pushString(large)
      val back = b.toString(-1)
      assertEquals(back.length, 65536)
      b.pop(1)
    finally
      b.close()
```

---

## 5. Acceptance Criteria & Tests

### Running the tests

```bash
# Run the shared cross-backend suite on JS (Node.js):
./mill wasm.test

# Run the WASM-specific tests:
./mill wasm.test luau.wasm.WasmSpecificSuite

# Run the shared suite on JVM (Panama backend) for comparison:
./mill panama.test luau.panama.PanamaBackendSuite

# Run both together (CI gate):
./mill __.test
```

### Pass criteria

| Test ID | Assertion |
|---------|-----------|
| TC-SHARED-01 | `return 42` yields `Returned(1)` with `toInt(-1) == 42` |
| TC-SHARED-02 | UTF-8 string `"hello, 世界"` survives a WASM round-trip exactly |
| TC-SHARED-03 | `rawSetI`/`rawGetI` correctly place and retrieve integers in a table |
| TC-SHARED-04 | A registered native function is called; result is correct |
| TC-SHARED-05 | `Fail` from a native function propagates as `pcall`-catchable Lua error |
| TC-SHARED-06 | `makeRef()` pins a table; `pushRef` re-materializes it; `close()` is safe to call |
| TC-SHARED-07 | `scoped { }` closes all tracked Refs at scope exit |
| TC-SHARED-08 | `Suspend` yields; subsequent `resume` with pushed value returns correctly |
| TC-SHARED-09 | 3-byte and 4-byte UTF-8 sequences plus embedded null-adjacent bytes round-trip |
| TC-SHARED-10 | Malformed source returns `Left(LuaError)` (not an exception) |
| TC-WASM-01 | Module loads from disk in under 10 seconds on a dev machine |
| TC-WASM-02 | `_malloc(128)` / byte write / `_free` completes without crash |
| TC-WASM-03 | `addFunction("iii")` / `dynCall_iii` / `removeFunction` lifecycle completes |
| TC-WASM-04 | Two independent `WasmBinding` instances hold independent Lua stacks |
| TC-WASM-05 | A 64 KB string push does not corrupt the `HEAPU8` view after potential `memory.grow` |

### End-to-end check

```bash
# Load a complete .luau script, register a native function, run it, verify output:
./mill wasm.test luau.wasm.WasmBackendSuite.TC-SHARED-04
```

If this test passes, the full call chain — `WasmBackend.load` → `WasmBinding.create` → `registerNativeFn` → `compileAndLoad` → `resume` → trampoline upcall → `Trampoline.dispatch` → native fn → `Return(1)` → result read — has been exercised end-to-end.

---

## 6. Risks & Gotchas

### 6.1 HEAPU8 view staleness after memory.grow

**Risk:** Any call to `_malloc` (including those inside `_lx_push_lstring`, `_lx_compile_and_load`) can trigger `memory.grow` in the WASM engine. When `memory.grow` fires, the `ArrayBuffer` backing `Module.HEAPU8` is replaced with a new, larger buffer. Any previously captured reference to `HEAPU8` now points to a detached (neutered) buffer and all reads/writes silently return 0.

**Mitigation:** Never cache `Module.HEAPU8` across a call that can allocate. Always access it as `WasmModule.module.HEAPU8` (a property access) at the point of use. `WasmMarshal` is deliberately written to re-read `HEAPU8` after every `_malloc` call. The test TC-WASM-05 exercises the 64 KB boundary to trigger `memory.grow` and detect regressions.

**Reference:** `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-fengari-js.md` section 5 (Strings: Uint8Array) illustrates the byte-exact string model; the WASM linear memory variant is strictly more dangerous because the backing buffer can be replaced.

### 6.2 addFunction / function table overflow

**Risk:** Emscripten's `addFunction` either grows the function table (requiring `ALLOW_TABLE_GROWTH=1`) or fails if the table is full. If P02 does not compile with `ALLOW_TABLE_GROWTH=1` and the pre-allocated table is exhausted, `addFunction` throws a JS exception.

**Mitigation:** P02 must set `-s ALLOW_TABLE_GROWTH=1` in its Emscripten flags. The WASM backend only ever calls `addFunction` once (the single shared trampoline) and pairs each call with `removeFunction` on `close()`. Verify that P02's `EXPORTED_RUNTIME_METHODS` includes both `addFunction` and `removeFunction`.

### 6.3 dynCall name depends on Emscripten version

**Risk:** The Emscripten runtime helper for indirect calls changed name across versions. In Emscripten ≥ 3.1.x the recommended form is `dynCall_iii`; in older versions it may be absent or named differently. In recent Emscripten, direct WASM table calls via `wasmTable.get(idx)(arg0, arg1)` are preferred over `dynCall_*`.

**Mitigation:** P02 must pin an Emscripten version and export `dynCall_iii` explicitly in `EXPORTED_RUNTIME_METHODS`. Alternatively, the WASM backend can use `Module.wasmTable.get(fnPtr)(L, fnId)` directly, which does not depend on the `dynCall_*` helper. The implementing agent should check the Emscripten version locked by P01 and choose accordingly. If `wasmTable.get` is used, update the `WasmModuleExports` facade to expose `wasmTable: js.Dynamic`.

### 6.4 JavaScript null vs. WASM i32 zero

**Risk:** Emscripten C pointer arguments are i32 in WASM. A null pointer is represented as `0` (integer zero), not JS `null`. Scala.js's `@js.native` integer parameters accept `0` naturally, but accidental `null` passing (from uninitialized `Int` fields in Scala.js) produces `0` silently. `_lx_newstate` returns `0` on OOM — this must be checked.

**Mitigation:** All factory methods check `!= 0` after `_lx_newstate` and `_malloc`. The `require` guards in `WasmBinding.create()` and `WasmMarshal.withString` catch OOM early.

### 6.5 Trampoline reentrancy

**Risk:** If a native function calls back into Luau (e.g., calls `resume` recursively), the trampoline can be re-entered before the outer invocation returns. The `pendingSuspend` slot on `WasmBinding` is a single mutable reference — a reentrant `Suspend` would overwrite an outer `Suspend` before it is consumed.

**Mitigation:** For MVP, prohibit re-entrant resume. Document that `NativeFn` implementations must not call `WasmBinding.resume()`. If re-entrant coroutine operations are needed, convert `pendingSuspend` to a stack. This is deferred to P06 (Scheduler), which will enforce the single-resume-at-a-time invariant via the Run queue.

### 6.6 Emscripten MODULARIZE + import resolution on Node.js

**Risk:** `require("./loader.js")` in the compiled Scala.js output assumes `loader.js` is in the same directory as the compiled `.js` file. Mill's test runner uses a temporary working directory, so the path must be resolved correctly. If the shim artifacts are not copied to the right location, `require` throws `MODULE_NOT_FOUND` at runtime.

**Mitigation:** The `wasm` module's `resources` definition (Task 4.9) copies both `loader.js` and `luau-shim.wasm` into the test classpath/resource directory. Mill's Node.js test runner must be configured to set `cwd` to the directory containing these files. Verify with TC-WASM-01.

### 6.7 TextEncoder / TextDecoder availability

**Risk:** `TextEncoder` and `TextDecoder` are Web APIs. They are available natively in Node.js ≥ 11 (global) and in modern browsers. On older Node.js versions they require `require('util').TextEncoder`. Scala.js's `js.Dynamic.global.TextEncoder` will throw if the global is missing.

**Mitigation:** Either gate the Node.js version in CI (P01's CI skeleton should document the minimum Node.js version), or use the Node.js `util` module fallback when `globalThis.TextEncoder` is undefined. Add a startup check in `WasmBackend.load()`.

### 6.8 Emscripten _malloc alignment

**Risk:** Emscripten's `_malloc` returns 8-byte-aligned pointers by default. The `allocOutInt()` helper allocates 4 bytes for an i32 out-parameter; this is safe. However, if the Shim C code ever requires stricter alignment for other out-parameters (e.g., f64 needs 8-byte alignment), under-aligned pointers will cause undefined behavior in the WASM C code.

**Mitigation:** Always pass `_malloc(max(required_size, 8))` for out-parameters that may hold f64 values. The current `allocOutInt` uses `_malloc(4)` for i32 only; add a separate `allocOutDouble()` using `_malloc(8)` if needed.

### 6.9 Node.js WASM module limitations

**Risk:** On Node.js versions below 14, WASM module instantiation is synchronous and does not support streaming. `LuauShimFactory()` returns a `Promise` that resolves once the module is compiled. In environments without WASM support (e.g., older embedded JS engines), the factory call itself may throw.

**Mitigation:** CI uses a Node.js version that supports WASM (≥14; ≥16 preferred for stable `--experimental-wasm-modules`). The timeout in `WasmBackendSuite.beforeAll()` surfaces failures promptly.

---

## 7. Out of Scope / Deferred

| Item | Owning plan |
|------|-------------|
| Scheduler / Run queue / Task lifecycle on JS | P06 `06-scheduler-and-task-model.md` |
| `task.*` library natives (spawn, defer, delay, wait, cancel) | P07 `07-stdlib-and-task-library.md` |
| Cross-worker state migration on JS (`SharedArrayBuffer` + `Atomics`) | ADR-0002 "deferred" — no owning plan yet |
| Standard library opening (`luaL_openlibs` subset) | P07 |
| `LuauEncoder[A]` / `LuauDecoder[A]` derivation for case classes | P03 |
| Dev-mode Ref leak detector (allocation-site tracking) | ADR-0005 dev-mode note — P03 or P04/P05 implementation detail |
| Browser-compatible bundle (non-Node.js WASM loading) | Not planned for MVP; the `wasm` module targets Node.js in CI |
| Performance tuning (batch Shim calls, reduce `_malloc` frequency) | Not in scope for any current plan — future optimization |
| Source maps for Scala.js debugging into WASM | Not planned |

---

## 8. References

The implementing agent must read the following before writing any code:

### ADRs (all under `/home/hoangdinh/OSS/luau-scala/docs/adr/`)

| File | Binding rule |
|------|-------------|
| `0001-embed-upstream-luau-via-slim-cpp-shim.md` | No `lua_pcall` across WASM↔JS boundary; all execution through Resume boundary; Scala callback cannot raise |
| `0002-movable-state-actor-concurrency.md` | JS uses single-worker; off-Driver completions enqueue, never resume inline |
| `0003-stackless-task-model.md` | Parked Task holds no C/WASM stack; yield fully unwinds to host |
| `0004-coroutine-substrate-task-on-top.md` | Single-threaded Scheduler for MVP; Scheduler does not interpret yield payloads |
| `0005-deterministic-ref-lifetime-no-finalizer.md` | JS GC cannot trace into WASM linear memory; no FinalizationRegistry; Ref is AutoCloseable |
| `0006-copy-only-data-boundary-via-codec-typeclass.md` | All data crosses by copy; Sink receives pushes directly; no intermediate tree |
| `0007-callback-based-async-and-tristate-native-return.md` | Tri-state return: Return(n)/Fail/Suspend; resume is one-shot; cancel is first-class |

### CONTEXT.md

`/home/hoangdinh/OSS/luau-scala/CONTEXT.md` — authoritative glossary. Use: **Runtime**, **Host**, **Shim**, **Binding backend** (not "FFI layer"), **Coroutine**, **Task**, **Resume boundary** (not "call"), **Ref**, **Codec**, **Sink**, **Scope**, **Suspension**, **Async primitive**, **Native function**, **Driver**, **Run queue**, **Isolate**.

### Research docs

| File | Relevant sections |
|------|------------------|
| `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-fengari-js.md` | §5 Strings (Uint8Array byte-exact model, `to_luastring`/`to_jsstring` patterns); §8 Coroutines (yield-via-exception, single-threaded limitations, the coroutine↔async JS wiring problem) |
| `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-rust-ecosystem.md` | §4 C++ Luau VM Bindings (mlua architecture for comparison; demonstrates the same "thin C layer over Luau" pattern in a different ecosystem) |

### Prior plans (must be complete before implementing this plan)

- `01-project-scaffold-and-build-toolchain.md` — Mill setup, Emscripten pipeline, `wasm` module skeleton
- `02-cpp-shim-abi.md` — `_lx_*` ABI, EXPORTED_FUNCTIONS, trampoline C signature, `LX_FAIL`/`LX_SUSPEND` constants, `ALLOW_TABLE_GROWTH`, `EXPORTED_RUNTIME_METHODS`
- `03-core-abstractions.md` — `Binding` trait, `Ref`, `Scope`, `NativeFnReturn`, `Async` primitive, `Codec`, `Sink`, `LuaError`, `ResumeResult`

### Mirror plan

- `04-panama-backend-jvm.md` — The JVM counterpart of this plan. Where the designs are symmetric, follow the same structure and naming to keep the shared test suite sensible. Where they diverge (Arena vs. WasmScope; MethodHandle vs. `addFunction`), the divergence is intentional and documented.
