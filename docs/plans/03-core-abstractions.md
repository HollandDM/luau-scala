# Plan 03: Core Abstractions (Backend-Agnostic `core` Module)

## 1. Milestone & Goal

This plan delivers the `core` cross-platform module: the complete set of backend-agnostic Scala 3 abstractions that both the Panama backend (JVM) and the WASM backend (JS) implement and consume. Concretely, this means: the `Binding` trait mirroring the `lx_*` Shim ABI; the `LuaValue` host-side value ADT; `LuaError`; `Ref` (an `AutoCloseable` registry handle, ADR-0005); `Scope` (owned-Ref region); the `Codec` typeclass pair `LuauEncoder[A]`/`LuauDecoder[A]` with the `Sink` streaming interface and a full set of `given` instances for primitives, `String`, `Option`, `Seq`/`Array`, `Map`, and `case class` derivation via `scala.deriving.Mirror` (ADR-0006); the tri-state `NativeFnResult` ADT (`Return`/`Fail`/`Suspend`) and the `Async` primitive types `Resume`/`Cancel` (ADR-0007); and an in-memory **Fake backend** that implements `Binding` entirely on the JVM heap without any FFI, used to unit-test all codec, Sink, Ref, and Scope logic in isolation. When this plan is complete, every subsequent plan (P04, P05, P06, P07, P08) has a stable API surface to build against.

---

## 2. Dependencies

**Requires P01** (`docs/plans/01-project-scaffold-and-build-toolchain.md`) to be complete:
- The Mill cross-build is configured. `core` is a `CrossPlatform` module (JVM + Scala.js).
- Scala 3 toolchain is pinned and the module layout (`core/`, `panama/`, `wasm/`, `scheduler/`, `stdlib/`) compiles.
- No artifact from the Shim is needed by `core` directly — `core` abstracts over it via `Binding`.

**Requires P02** (`docs/plans/02-cpp-shim-abi.md`) to be complete (for conceptual grounding, not a compile-time dep of `core`):
- `core`'s `Binding` trait is defined to mirror the `lx_*` symbol set exactly. The trait method signatures must match the semantics documented in P02 even though `core` does not link against the Shim binary.
- Specifically: `lx_newstate`, `lx_close`, `lx_compile`, `lx_load`, `lx_resume`, `lx_newthread`, the stack push/read ops, `lx_newtable`, `lx_rawget`, `lx_rawset`, `lx_setarray`, `lx_ref`, `lx_unref`, and the trampoline/NativeFn registration (`lx_pushfunction`).
- The tri-state return protocol from P02 (`Return(n)` / `Fail(value)` / `Suspend(token)` from the trampoline) is encoded as the `NativeFnResult` ADT here.

**Consumed by P03's output (what later plans import from `core`):**

| Symbol | Consumed by |
|--------|-------------|
| `Binding[H]` trait | P04 (Panama impl), P05 (WASM impl) |
| `LuaValue` ADT | P04, P05, P06, P07, P08 |
| `LuaError` | P04, P05, P06, P07, P08 |
| `Ref` | P04, P05, P06, P07, P08 |
| `Scope` | P04, P05, P06, P07, P08 |
| `LuauEncoder[A]`, `LuauDecoder[A]`, `Sink` | P04, P05, P07, P08 |
| `NativeFnResult` | P04, P05, P06, P07 |
| `Resume`, `Cancel`, `Suspend` | P06, P08 |
| `FakeBinding` | Testing in P03, P06 (scheduler unit tests) |

---

## 3. Design Context

### 3.1 No lua_pcall Across the FFI Boundary (ADR-0001)

The Shim guarantees that `lua_error` (which `longjmp`s up the C stack) never crosses the Panama downcall or WASM host-function frame. All Luau execution enters through `lx_resume`, which converts errors to a status code. The Host side touches the Luau stack only with non-raising accessors (`lua_type`, `lua_toXxx`, `lua_rawXxx`). This constraint appears throughout `Binding`: every method that reads a stack slot uses a non-raising variant; the trait exposes no method that could trigger an internal Lua error.

### 3.2 Stackless Task Model (ADR-0003)

When a Native function needs to await something, it cannot block the upcall — it must return `Suspend(register)` so the Shim can call `lua_yield(k)` in pure C and unwind the native stack completely. The Host parks the Task as pure heap data. This is why `NativeFnResult.Suspend` carries a `register: Resume => Cancel` callback rather than a blocking handle.

### 3.3 Deterministic Ref Lifetime, No Finalizer (ADR-0005)

`Ref` is `AutoCloseable`. It is **never** backed by a GC finalizer. Ownership is via explicit `close()`, `Using`, or a scope that releases everything it owns on exit. The `core` module keeps `Ref` as a bare `AutoCloseable`. A leaked Ref pins its Luau object until state teardown. A dev-mode leak detector (allocation site tracking, reported at state close) is required; it must be gated behind a system property or build flag and is not a finalizer.

### 3.4 Copy-Only Data Boundary via Codec (ADR-0006)

No host object crosses the Host→Luau boundary by reference. Every push is a copy into Luau's heap. Luau then owns that copy. Only `LuauEncoder[A]` may push Host→Luau. `LuauDecoder[A]` lifts Luau→Host by copying the value. The direction is asymmetric: Host→Luau is copy-only; Luau→Host may be a `Ref` (cheap registry handle that pins the Luau object). Strings cross as UTF-8 bytes (`Luau strings are byte strings`). Encoders write to a `Sink` rather than an intermediate tree to remain single-copy and backend-agnostic.

### 3.5 Tri-State Native Return and Callback Async (ADR-0007)

A Native function returns `NativeFnResult`:
```
Return(nResults: Int)           -> trampoline returns n values to Luau
Fail(value: LuaValue)           -> trampoline calls lua_error in pure C
Suspend(register: Resume=>Cancel) -> trampoline calls lua_yield(k); Task parks
```
The `resume` callback is one-shot and thread-safe: it only enqueues onto the Run queue, never resumes inline. A second call is a no-op (dev-mode throws).

### 3.6 Movable State / Off-Driver Completions (ADR-0002, ADR-0004)

Async completions must enqueue onto the Run queue rather than calling `lua_resume` directly. This is enforced by the `Resume` type — calling it posts to the queue. `core` does not own the Run queue (that is P06), but the `Resume`/`Cancel` types are defined here so P06 can implement the queue against them.

### 3.7 CONTEXT Terminology

The following CONTEXT terms are used throughout this plan with their exact glossary meanings:
- **Binding backend** — the platform-specific Scala code that calls the Shim's ABI (not "FFI layer").
- **Ref** — a stable Host-held handle to a Luau-heap object (not "pointer" or "handle").
- **Scope** — a confined region owning Refs (not "arena", except when naming the JVM impl).
- **Codec** — the `LuauEncoder`/`LuauDecoder` typeclass pair (not "serializer" or "marshaller").
- **Sink** — the streaming push target encoders write into (not "builder" or "writer").
- **Native function** — a Scala function exposed to scripts (not "callback").
- **Resume boundary** — the single sanctioned entry point for executing Luau code.
- **Async primitive** — the `Suspend(register)` callback model.
- **Suspension** — what a Task produces when it yields to the Host.
- **Driver** — the serial execution context owning a Luau state (not "thread").

---

## 4. Task Breakdown

All files live under `/home/hoangdinh/OSS/luau-scala/`. The module root is `core/src/` (cross-platform, compiled for JVM and Scala.js from the same source). The Fake backend lives in `core/test/` because it is a test-only artifact — it must not be published as part of the production `core` artifact.

### File 1: `core/src/luau/core/LuaValue.scala`

**Purpose:** Host-side Luau value ADT. This is the Scala representation of values read from or written to the Luau stack. It is distinct from the C-level `TValue` in the Runtime; it is what the Host sees after a non-raising read.

Luau uses doubles exclusively for numbers (no integer subtype; see ADR note and research doc `/home/hoangdinh/OSS/luau-scala/docs/research/topic-value-representation-and-tables.md` §1.1). The value ADT must not include an integer subtype.

**Key declarations:**

```scala
package luau.core

/** Host-side representation of a Luau value, as lifted from the Luau stack
 *  by non-raising accessors. Crossing the Resume boundary always copies; the
 *  Host and Luau own independent copies (ADR-0006).
 *
 *  Number model: Luau uses doubles exclusively — no integer subtype.
 */
sealed trait LuaValue

object LuaValue:
  /** The nil singleton. The only other falsy value is LuaBoolean(false). */
  case object Nil extends LuaValue

  /** Boolean. Only two instances; use LuaValue.True / LuaValue.False. */
  sealed abstract class Bool(val value: Boolean) extends LuaValue
  case object True  extends Bool(true)
  case object False extends Bool(false)

  object Bool:
    def apply(b: Boolean): Bool = if b then True else False
    def unapply(b: Bool): Some[Boolean] = Some(b.value)

  /** Luau number (double-precision IEEE 754). Luau has no integer subtype. */
  final case class Number(value: Double) extends LuaValue

  /** Luau byte string. Luau strings are 8-bit-clean byte sequences. The Host
   *  always represents them as IArray[Byte]. UTF-8 decode is an explicit op.
   *  (ADR-0006: "Strings cross as bytes.")
   */
  final case class LuaString(bytes: IArray[Byte]) extends LuaValue

  object LuaString:
    /** Convenience: encode a Scala String as UTF-8 bytes. */
    def fromUtf8(s: String): LuaString =
      LuaString(IArray.unsafeFromArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)))

  /** A Ref returned from a Luau→Host read of a table or function. The caller
   *  is responsible for closing this Ref (ADR-0005). Carries the Handle type H
   *  from the backend — opaque to LuaValue consumers.
   *
   *  Note: a LuaRef appearing in a LuaValue is only valid within the state
   *  that created it. Attempting to use it from another state is undefined.
   */
  final class LuaRef[+H](val ref: Ref[H]) extends LuaValue

  /** Truthiness per Luau semantics: only nil and false are falsy. */
  def isTruthy(v: LuaValue): Boolean = v match
    case Nil | False => false
    case _           => true
```

### File 2: `core/src/luau/core/LuaError.scala`

**Purpose:** The error type representing a Luau error propagated back to the Host across the Resume boundary. Used as the left side of `Either[LuaError, Result]` in the `Resume` callback.

```scala
package luau.core

/** An error returned at the Resume boundary when lua_resume returns a
 *  non-LUA_OK / non-LUA_YIELD status. The message is the Luau error
 *  object coerced to string by the Shim before crossing the boundary.
 *
 *  LuaError is NOT a JVM exception by design: it must not cause a
 *  stack unwind that could cross the FFI frame. It is a plain data type.
 */
final case class LuaError(message: String, level: LuaError.Level) extends Throwable(message, null, true, false)

object LuaError:
  enum Level:
    /** Runtime error from lua_resume (LUA_ERRRUN). */
    case Runtime
    /** Memory allocation failure (LUA_ERRMEM). */
    case Memory
    /** Error inside an error handler (LUA_ERRERR) — should be rare. */
    case Handler

  def runtime(msg: String): LuaError = LuaError(msg, Level.Runtime)
  def memory(msg: String): LuaError  = LuaError(msg, Level.Memory)
```

Note: `LuaError extends Throwable` with `writableStackTrace = false` so it can be thrown by effect adapters (P08) without capturing a JVM stack trace. The `core` module itself does not throw it; the Binding backend translates the Shim's error status into a `LuaError` value.

### File 3: `core/src/luau/core/Ref.scala`

**Purpose:** A stable Host-held handle to a Luau-heap object, backed by a registry reference (the equivalent of `luaL_ref` / `luaL_unref`). Implements `AutoCloseable`. Carries the backend's opaque `Handle` type as a type parameter so the Panama backend can embed a `MemorySegment` state pointer and an `Int` registry index without boxing.

```scala
package luau.core

/** A host-held registry reference to a Luau heap object.
 *
 *  Lifecycle (ADR-0005):
 *  - Created by Binding.ref(state) — pops the top stack value, stores in registry.
 *  - Released ONLY by explicit close() / Scope exit / state teardown.
 *  - No GC finalizer. A leaked Ref pins the Luau object until state close.
 *  - close() after state teardown is a no-op.
 *  - close() must be idempotent (double-close is a no-op; dev-mode warns).
 *
 *  H is the backend's opaque state handle type (e.g. MemorySegment on JVM,
 *  js.Dynamic on Scala.js). The Ref carries the state reference so the
 *  Binding implementation can route unref to the correct state.
 *
 *  The idiomatic owners are:
 *  - scala.util.Using  (for lexical scope)
 *  - Scope             (for grouped deterministic release)
 */
final class Ref[H] private[core] (
  private[core] val state:    H,
  private[core] val registry: Int,
  private[core] val binding:  Binding[H],
  private[core] val origin:   String, // stack trace string in dev mode, "" in prod
) extends AutoCloseable:

  @volatile private var closed = false

  /** Push this Ref's value onto the Luau stack of the owning state.
   *  Precondition: called on the Driver that owns this state.
   *  This calls lx_rawgeti(state, LUA_REGISTRYINDEX, registry).
   */
  def push(): Unit =
    require(!closed, "Ref.push() on a closed Ref")
    binding.pushRef(state, registry)

  override def close(): Unit =
    if !closed then
      closed = true
      binding.unref(state, registry)
    // else: no-op; dev-mode should log a warning on double-close

  def isClosed: Boolean = closed
```

**Factory method** lives in `Binding` (see File 5). The `private[core]` constructor prevents external code from bypassing the factory.

### File 4: `core/src/luau/core/Scope.scala`

**Purpose:** A confined region that owns `Ref`s and closes them all on `close()`. The canonical everyday way to avoid per-`Ref` bookkeeping. On the Panama backend `Scope` is backed by a `java.lang.foreign.Arena` (which also governs the lifetime of off-heap memory segments); the `core` version is a pure Scala wrapper.

```scala
package luau.core

import scala.collection.mutable

/** A confined region that owns Refs opened inside it.
 *
 *  Usage:
 *    val scope = state.openScope()        // or: Scope.open(binding, handle)
 *    try
 *      val ref = scope.ref()              // pops top of stack, registers
 *      // ... use ref ...
 *    finally
 *      scope.close()                      // unrefs all owned refs in LIFO order
 *
 *  Alternatively with Using:
 *    Using.resource(state.openScope()) { scope =>
 *      val ref = scope.ref()
 *      // ...
 *    }
 *
 *  The Panama backend overrides this with an Arena-backed subclass (P04).
 *  On Scala.js the default JVM-heap implementation is used (P05).
 *
 *  Thread-safety: Scope is NOT thread-safe. It must be created and closed
 *  on the same Driver. Refs must be used on the same Driver.
 */
class Scope[H](
  private val binding: Binding[H],
  private val state:   H,
) extends AutoCloseable:

  // LIFO order: last opened, first closed.
  private val owned: mutable.ArrayDeque[Ref[H]] = mutable.ArrayDeque.empty

  /** Pop the top of the Luau stack, store in registry, return a Ref owned by
   *  this Scope. Equivalent to: binding.ref(state) then register with Scope.
   */
  def captureTop(): Ref[H] =
    val r = binding.ref(state)
    owned.addOne(r)
    r

  /** Register an existing Ref with this Scope. Scope takes ownership.
   *  Calling close() on a Ref after transfer is a no-op.
   */
  def own(r: Ref[H]): r.type =
    owned.addOne(r)
    r

  /** Close all owned Refs in LIFO order. Idempotent. */
  override def close(): Unit =
    while owned.nonEmpty do
      owned.removeLast().close()
```

### File 5: `core/src/luau/core/Binding.scala`

**Purpose:** The central abstraction of the entire `core` module. A type-parameterized trait over an opaque `Handle` type `H` that represents one Luau state. Every method maps 1:1 to a Shim `lx_*` function. The `Binding` trait is what both the Panama backend and the WASM backend implement; it is also what the Fake backend implements for testing.

No Luau C API function is called directly from `core`; all access is via `Binding[H]`. The trait uses non-raising accessors only. Panics in method bodies are reserved for calls that break the documented preconditions (wrong type check, called after close, etc.).

```scala
package luau.core

/** Backend-agnostic abstraction over a single Luau state (lx_* ABI).
 *
 *  H is the opaque handle type:
 *    - Panama backend: java.lang.foreign.MemorySegment (pointer to lua_State)
 *    - WASM backend: Int (linear-memory address)
 *    - Fake backend: FakeState (a JVM-heap object)
 *
 *  Preconditions applying to ALL methods:
 *  - Unless stated otherwise, the caller is on the Driver that owns the state.
 *  - The state must not be closed (lx_close called).
 *  - Stack depths noted in comments; implementations must check in dev mode.
 *
 *  Correspondence to Luau C API symbols is noted in each method.
 *
 *  This trait intentionally has NO default implementations — every backend
 *  must implement every method. Scala 3 abstract methods catch omissions at
 *  compile time.
 */
trait Binding[H]:

  // ---- State lifecycle ------------------------------------------------

  /** Create a new Luau state. Returns an opaque Handle.
   *  Corresponds to: lx_newstate() -> lua_State*
   *  The state is empty; no libraries are loaded.
   */
  def newState(): H

  /** Destroy the Luau state, releasing all associated memory.
   *  Corresponds to: lx_close(L)
   *  All open Refs become invalid after this call; subsequent close()
   *  on any Ref for this state must be a no-op.
   */
  def closeState(state: H): Unit

  // ---- Compile + load -------------------------------------------------

  /** Compile Luau source bytes to bytecode using the Luau Compiler, then
   *  load the resulting chunk onto the top of the stack as a function.
   *  Corresponds to: lx_compile_and_load(L, source, source_len, chunkname)
   *    which calls luau_compile then luau_load.
   *  On success: stack grows by 1 (the chunk function).
   *  On failure: returns Left(LuaError) — the chunk function is NOT pushed.
   *  The chunkname appears in error messages and stack traces.
   */
  def compileAndLoad(
    state:     H,
    source:    IArray[Byte],
    chunkname: String,
  ): Either[LuaError, Unit]

  // ---- Resume boundary ------------------------------------------------

  /** Resume a Luau thread (coroutine) with nargs arguments already on the
   *  stack of the THREAD (not the main state), popping them.
   *  Corresponds to: lx_resume(thread, nargs, &nresults) -> status
   *
   *  The lx_resume wrapper converts any lua_error into a status code so
   *  nothing longjmps across this call (ADR-0001). The status is:
   *    ResumeResult.Returned(nresults) — thread finished normally
   *    ResumeResult.Yielded(nresults)  — thread called coroutine.yield
   *    ResumeResult.Error(LuaError)    — thread raised an error
   *
   *  The thread handle H here is the lua_State* for the coroutine thread,
   *  NOT the main state. The backend creates thread handles via newThread.
   */
  def resume(thread: H, nargs: Int): ResumeResult

  // ---- Coroutine / thread lifecycle -----------------------------------

  /** Create a new Luau thread (coroutine), pushing it on the main state's
   *  stack. The thread is not started.
   *  Corresponds to: lua_newthread(L) — non-raising.
   *  Stack effect: +1 (thread value on main state stack).
   *  Returns the thread's own lua_State* as a new Handle.
   *  Caller should immediately call ref() on the main state to anchor the
   *  thread in the registry lest it be GC'd.
   */
  def newThread(state: H): H

  // ---- Stack: push operations -----------------------------------------
  // All push variants correspond to non-raising Luau API calls.

  /** lua_pushnil(L) */
  def pushNil(state: H): Unit

  /** lua_pushboolean(L, b) */
  def pushBoolean(state: H, value: Boolean): Unit

  /** lua_pushnumber(L, n) — Luau numbers are doubles only. */
  def pushNumber(state: H, value: Double): Unit

  /** lua_pushlstring(L, bytes, len) — raw bytes, not necessarily UTF-8.
   *  The Luau VM copies the bytes; the caller's array may be freed afterward.
   */
  def pushBytes(state: H, bytes: IArray[Byte]): Unit

  /** Convenience: push a UTF-8 string. Equivalent to pushBytes(utf8(s)). */
  def pushString(state: H, value: String): Unit

  /** Push a function into the registry by fnId, wrapping it in a closure
   *  carrying that fnId as an upvalue.
   *  Corresponds to: lx_pushfunction(L, fnId)
   *    which installs the trampoline closure (ADR-0007, P02).
   *  Stack effect: +1.
   *  The Binding backend must maintain a dispatch table mapping fnId ->
   *    (H, IArray[Byte]) => NativeFnResult
   *  The fnId is chosen by the backend; it is opaque to call sites.
   */
  def pushFunction(state: H, fnId: Int): Unit

  /** Push a table reference from the registry back onto the stack.
   *  Used by Ref.push() — corresponds to:
   *    lua_rawgeti(L, LUA_REGISTRYINDEX, registry)
   */
  def pushRef(state: H, registry: Int): Unit

  // ---- Stack: read operations (non-raising) ---------------------------

  /** lua_type(L, idx) — returns the LuaType tag; never raises. */
  def typeAt(state: H, idx: Int): LuaType

  /** lua_tonumberx(L, idx, &isnum) — returns None if not a number.
   *  Non-raising (uses tonumberx, not checknumber).
   */
  def toNumber(state: H, idx: Int): Option[Double]

  /** lua_toboolean(L, idx) — always returns a value (nil/false -> false). */
  def toBoolean(state: H, idx: Int): Boolean

  /** lua_tolstring(L, idx, &len) — returns None if not a string/number.
   *  Returns the raw bytes of the Luau string. Non-raising.
   *  IMPORTANT: the returned IArray is a copy; the pointer from tolstring
   *  is only valid while the value is on the stack.
   */
  def toBytes(state: H, idx: Int): Option[IArray[Byte]]

  /** lua_type(L, idx) == LUA_TNIL */
  def isNil(state: H, idx: Int): Boolean = typeAt(state, idx) == LuaType.Nil

  /** lua_gettop(L) — number of values on the stack. */
  def stackTop(state: H): Int

  /** lua_settop(L, idx) — set stack top; pops or pushes nils. */
  def setStackTop(state: H, idx: Int): Unit

  /** lua_pop(L, n) — equivalent to setStackTop(state, -n-1). */
  def pop(state: H, n: Int): Unit = setStackTop(state, -n - 1)

  // ---- Table operations -----------------------------------------------

  /** lua_newtable(L) — push empty table onto stack. Stack effect: +1. */
  def newTable(state: H): Unit

  /** lx_rawget(L, tableIdx) — rawget using key on top of stack; replaces key
   *  with result. Non-raising. Stack effect: 0 (key popped, value pushed).
   *  Corresponds to: lua_rawget(L, tableIdx)
   */
  def rawGet(state: H, tableIdx: Int): Unit

  /** lx_rawset(L, tableIdx) — rawset key/value on top of stack.
   *  Pops both key and value. Non-raising.
   *  Corresponds to: lua_rawset(L, tableIdx)
   */
  def rawSet(state: H, tableIdx: Int): Unit

  /** lx_setarray(L, tableIdx, n) — set t[n] = stack top; pops value.
   *  Used by sequence encoder to push 1-indexed array-part entries.
   *  Corresponds to: lua_rawseti(L, tableIdx, n)
   */
  def setArray(state: H, tableIdx: Int, n: Int): Unit

  /** lua_rawgeti(L, tableIdx, n) — push t[n] onto stack. Stack effect: +1. */
  def getArray(state: H, tableIdx: Int, n: Int): Unit

  /** lua_rawlen(L, idx) — length of table or string, non-raising. */
  def rawLen(state: H, idx: Int): Long

  // ---- Registry (Ref management) --------------------------------------

  /** luaL_ref(L, LUA_REGISTRYINDEX) — pop top, store in registry.
   *  Returns an Int registry key. Creates a new Ref owned by the caller.
   *  The Ref must be closed explicitly or via a Scope (ADR-0005).
   */
  def ref(state: H): Ref[H]

  /** luaL_unref(L, LUA_REGISTRYINDEX, key) — release registry slot.
   *  Called by Ref.close(); not intended for direct use.
   *  No-op if the state is already closed.
   */
  def unref(state: H, key: Int): Unit

  // ---- Native function registration -----------------------------------

  /** Register a NativeFn under a fresh fnId and push the trampoline closure.
   *  Stack effect: +1.
   *  The backend assigns a unique fnId, stores fn in its dispatch table, and
   *  calls pushFunction(state, fnId).
   *
   *  fn signature:
   *    (state: H, nargs: Int) => NativeFnResult
   *  where nargs arguments are on top of the stack (index 1..nargs).
   *  fn reads them with toNumber/toBytes/etc.; must not raise.
   *
   *  The pushed closure is used by scripts to call the Native function.
   *  Normally callers push it and then set it in a table/global.
   */
  def registerNativeFn(state: H, fn: NativeFn[H]): Unit

  // ---- Global access --------------------------------------------------

  /** lua_getglobal(L, name) — push global by name. Stack effect: +1. */
  def getGlobal(state: H, name: String): Unit

  /** lua_setglobal(L, name) — set global from stack top; pops. Stack effect: -1. */
  def setGlobal(state: H, name: String): Unit

  // ---- Scope helpers --------------------------------------------------

  /** Open a new Scope for this state. The Scope's Refs are closed in LIFO
   *  order when Scope.close() is called.
   *  The Panama backend overrides this to return an Arena-backed subclass.
   */
  def openScope(state: H): Scope[H] = Scope(this, state)
```

### File 6: `core/src/luau/core/LuaType.scala`

**Purpose:** Enum mirroring Luau's type tags, returned by `Binding.typeAt`.

```scala
package luau.core

/** Luau value type tags, as returned by lua_type().
 *  Maps to LUA_T* constants in lua.h.
 */
enum LuaType(val luaCode: Int):
  case None     extends LuaType(-1)  // LUA_TNONE  — invalid stack index
  case Nil      extends LuaType(0)   // LUA_TNIL
  case Boolean  extends LuaType(1)   // LUA_TBOOLEAN
  case Number   extends LuaType(3)   // LUA_TNUMBER (Luau: doubles only)
  case String   extends LuaType(4)   // LUA_TSTRING
  case Table    extends LuaType(5)   // LUA_TTABLE
  case Function extends LuaType(6)   // LUA_TFUNCTION
  case Userdata extends LuaType(7)   // LUA_TUSERDATA
  case Thread   extends LuaType(8)   // LUA_TTHREAD

object LuaType:
  def fromCode(code: Int): LuaType =
    values.find(_.luaCode == code).getOrElse(
      throw IllegalArgumentException(s"Unknown Luau type code: $code")
    )
```

### File 7: `core/src/luau/core/ResumeResult.scala`

**Purpose:** The result of a `Binding.resume` call, encoding the tri-state outcome of `lx_resume`.

```scala
package luau.core

/** Result of Binding.resume(thread, nargs).
 *
 *  Returned(nresults): the thread finished normally; nresults values are
 *    on the thread's stack at indices 1..nresults.
 *  Yielded(nresults): the thread called coroutine.yield; nresults values
 *    are the yield arguments on the thread's stack at indices 1..nresults.
 *    The thread is parked; resume it again to continue.
 *  Error(error): the thread raised an error (lua_resume returned non-OK).
 *    The error message is on the thread's stack at index 1.
 */
enum ResumeResult:
  case Returned(nresults: Int)
  case Yielded(nresults: Int)
  case Error(error: LuaError)
```

### File 8: `core/src/luau/core/NativeFnResult.scala`

**Purpose:** The tri-state return ADT for Native functions, as described in ADR-0007. The Shim trampoline reads this value and takes the corresponding C action.

```scala
package luau.core

/** The tri-state return value of a NativeFn (ADR-0007).
 *
 *  Return(nResults):
 *    The function succeeded. nResults values have been pushed onto the
 *    Luau stack (indices starting at 1 above the args). The trampoline
 *    returns nResults to Luau.
 *
 *  Fail(value):
 *    The function failed. `value` is the error object (typically a string).
 *    The trampoline calls lua_error(L) in pure C after the upcall returns
 *    (so no longjmp crosses the FFI boundary — ADR-0001).
 *    The NativeFn must NOT have pushed partial results onto the stack.
 *
 *  Suspend(register):
 *    The function needs to await an async operation. `register` is called
 *    by the Shim immediately before lua_yield(k). It receives a one-shot
 *    Resume callback and returns a Cancel (ADR-0007).
 *    The Task parks; the NativeFn must NOT have pushed partial results.
 *    When the async op completes, calling Resume enqueues a resume onto the
 *    Run queue (P06). Calling Cancel aborts the async op on teardown.
 */
enum NativeFnResult:
  case Return(nResults: Int)
  case Fail(value: LuaValue)
  case Suspend(register: Resume => Cancel)
```

### File 9: `core/src/luau/core/Async.scala`

**Purpose:** The callback-based async primitive types. Defined in `core` so that P06 (Scheduler) and any other consumer can depend on stable types without circular dependencies.

```scala
package luau.core

/** One-shot callback signaling completion of an async operation.
 *
 *  Called by the external async op when it completes (success or failure).
 *  MUST only enqueue onto the Run queue — never call lua_resume inline
 *  (ADR-0002: off-Driver completions enqueue, never resume inline).
 *
 *  Thread-safe: may be called from any thread (e.g. a JS microtask, a
 *  ThreadPoolExecutor callback).
 *
 *  One-shot: calling Resume more than once is a no-op in production;
 *  in dev mode it throws IllegalStateException.
 *
 *  The result: Right(result) for success, Left(error) for failure.
 *  The result type is LuaValue because the Shim pushes it as the
 *  resumed coroutine's first argument.
 */
opaque type Resume = Either[LuaError, LuaValue] => Unit

object Resume:
  def apply(f: Either[LuaError, LuaValue] => Unit): Resume = f
  extension (r: Resume)
    def complete(result: Either[LuaError, LuaValue]): Unit = r(result)
    def succeed(v: LuaValue): Unit = r(Right(v))
    def fail(e: LuaError): Unit    = r(Left(e))

/** Cancellation callback for an in-flight async operation.
 *
 *  Returned by register (the Suspend argument). Called by the scheduler
 *  or state teardown when the Task is cancelled before the async op
 *  completes. The implementation should abort the in-flight operation
 *  (cancel an HTTP request, remove a timer, etc.).
 *
 *  Cancel must be idempotent: calling it multiple times must be safe.
 *  Cancel must NOT call Resume.
 */
opaque type Cancel = () => Unit

object Cancel:
  val noop: Cancel = () => ()
  def apply(f: () => Unit): Cancel = f
  extension (c: Cancel)
    def cancel(): Unit = c()
```

### File 10: `core/src/luau/core/NativeFn.scala`

**Purpose:** Type alias for the Scala function type of a Native function, plus supporting infrastructure.

```scala
package luau.core

/** A Scala function exposed to Luau scripts via the Shim trampoline.
 *
 *  Receives:
 *    state: H  — the Luau state handle (opaque backend type)
 *    nargs: Int — number of arguments on the stack (indices 1..nargs)
 *
 *  Must return a NativeFnResult describing the outcome.
 *  Must NOT call lua_error or lua_yield directly.
 *  Must NOT throw a JVM exception (use Fail or Suspend instead).
 *  Must read arguments only with non-raising accessors (toNumber, toBytes, etc.).
 *
 *  The Binding backend stores NativeFns in a dispatch table keyed by fnId.
 *  The trampoline recovers the fnId from its upvalue, looks up the fn, calls it,
 *  and acts on the returned NativeFnResult in pure C.
 */
type NativeFn[H] = (state: H, nargs: Int) => NativeFnResult
```

### File 11: `core/src/luau/core/codec/Sink.scala`

**Purpose:** The streaming push interface that `LuauEncoder` instances write into. Backend-agnostic; the Panama and WASM backends provide concrete implementations over their respective `Binding` instances. The `Sink` is the mechanism that keeps encoders in `core` while the actual `lua_push*` calls live in the backends.

```scala
package luau.core.codec

import luau.core.*

/** Streaming push target for LuauEncoder instances.
 *
 *  An encoder writes a sequence of Sink operations to produce one Luau value
 *  on the stack. The backend's Sink implementation translates each operation
 *  into Binding calls (pushNil, pushNumber, pushBytes, newtable, rawset, etc.).
 *
 *  Design rule: encode() must push EXACTLY ONE value onto the Luau stack.
 *  Multiple pushes inside a table are done via nested beginTable/endTable calls.
 *
 *  Invariants maintained by implementations:
 *  - beginTable increments nesting depth; endTable decrements it.
 *  - pushKey/pushValue must alternate at each nesting level.
 *  - pushArrayValue(n) pushes value and sets t[n] = value (rawseti).
 *  - Implementations are NOT required to be thread-safe.
 */
trait Sink[H]:
  val binding: Binding[H]
  val state:   H

  /** Push nil onto the stack. */
  def pushNil(): Unit

  /** Push a boolean value. */
  def pushBoolean(value: Boolean): Unit

  /** Push a double. */
  def pushNumber(value: Double): Unit

  /** Push a Luau string from raw bytes (copies into Luau heap). */
  def pushBytes(bytes: IArray[Byte]): Unit

  /** Convenience: push a UTF-8 encoded string. */
  def pushString(value: String): Unit =
    pushBytes(IArray.unsafeFromArray(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))

  /** Begin a new table. Calls lua_newtable; leaves table on stack. */
  def beginTable(): Unit

  /** End the current table. The table remains on the stack as the final value. */
  def endTable(): Unit

  /** Push a key for the current table (to be followed by pushValue). */
  def pushKey(key: LuaValue): Unit

  /** Push a value for the current table key (lua_rawset). */
  def pushValue[A: LuauEncoder](value: A): Unit

  /** Push t[n] = value for array-part encoding (lua_rawseti).
   *  n is 1-indexed per Luau convention.
   */
  def pushArrayValue[A: LuauEncoder](n: Int, value: A): Unit

  /** Convenience: push a string key then a value (common field pattern). */
  def pushField[A: LuauEncoder](name: String, value: A): Unit =
    pushKey(LuaValue.LuaString.fromUtf8(name))
    pushValue(value)
```

### File 12: `core/src/luau/core/codec/LuauEncoder.scala`

**Purpose:** The typeclass that governs Host→Luau encoding. A type `A` may cross into Luau only if it has a `LuauEncoder[A]` instance. ADR-0006.

```scala
package luau.core.codec

import luau.core.*

/** Typeclass: A has a Luau encoding.
 *
 *  Laws:
 *  1. encode(value, sink) pushes EXACTLY ONE value onto the Luau stack.
 *  2. encode is pure with respect to the sink (no side effects beyond
 *     the push operations described).
 *  3. encode must not raise; errors are impossible for well-formed values.
 *
 *  Usage:
 *    given LuauEncoder[MyType] = ...
 *    binding.pushEncoded(state, myValue)   // pushes exactly one value
 *
 *  The compile-time enforcement: push[A: LuauEncoder] won't compile for
 *  a type without an encoder instance (ADR-0006).
 */
trait LuauEncoder[A]:
  def encode[H](value: A, sink: Sink[H]): Unit

object LuauEncoder:
  def apply[A](using enc: LuauEncoder[A]): LuauEncoder[A] = enc

  /** Summon and encode in one step. */
  def encode[A: LuauEncoder, H](value: A, sink: Sink[H]): Unit =
    summon[LuauEncoder[A]].encode(value, sink)

  // ---- Primitive instances -------------------------------------------

  given LuauEncoder[Unit] with
    def encode[H](value: Unit, sink: Sink[H]): Unit = sink.pushNil()

  given LuauEncoder[Boolean] with
    def encode[H](value: Boolean, sink: Sink[H]): Unit = sink.pushBoolean(value)

  given LuauEncoder[Double] with
    def encode[H](value: Double, sink: Sink[H]): Unit = sink.pushNumber(value)

  /** Int encodes as Double (Luau has no integer subtype). */
  given LuauEncoder[Int] with
    def encode[H](value: Int, sink: Sink[H]): Unit = sink.pushNumber(value.toDouble)

  given LuauEncoder[Long] with
    def encode[H](value: Long, sink: Sink[H]): Unit = sink.pushNumber(value.toDouble)
    // Note: Long precision loss for values > 2^53. Documented limitation.

  given LuauEncoder[Float] with
    def encode[H](value: Float, sink: Sink[H]): Unit = sink.pushNumber(value.toDouble)

  // ---- String instances ----------------------------------------------

  /** String crosses as UTF-8 bytes (ADR-0006: "Strings cross as bytes"). */
  given LuauEncoder[String] with
    def encode[H](value: String, sink: Sink[H]): Unit = sink.pushString(value)

  /** Raw bytes cross as a Luau byte string, bypassing UTF-8 interpretation. */
  given LuauEncoder[IArray[Byte]] with
    def encode[H](value: IArray[Byte], sink: Sink[H]): Unit = sink.pushBytes(value)

  given LuauEncoder[Array[Byte]] with
    def encode[H](value: Array[Byte], sink: Sink[H]): Unit =
      sink.pushBytes(IArray.unsafeFromArray(value))

  // ---- Option --------------------------------------------------------

  /** None encodes as nil; Some(a) encodes as a. */
  given [A: LuauEncoder]: LuauEncoder[Option[A]] with
    def encode[H](value: Option[A], sink: Sink[H]): Unit = value match
      case None    => sink.pushNil()
      case Some(a) => summon[LuauEncoder[A]].encode(a, sink)

  // ---- Collections (Seq / List / Array -> 1-indexed table) -----------

  /** Seq[A] encodes as a 1-indexed Luau table (array part).
   *  t[1] = seq(0), t[2] = seq(1), ... t[n] = seq(n-1)
   *  Nil values in the sequence produce gaps (t[i] not set).
   */
  given [A: LuauEncoder]: LuauEncoder[Seq[A]] with
    def encode[H](value: Seq[A], sink: Sink[H]): Unit =
      sink.beginTable()
      var i = 1
      for elem <- value do
        sink.pushArrayValue(i, elem)
        i += 1
      sink.endTable()

  given [A: LuauEncoder]: LuauEncoder[List[A]] =
    summon[LuauEncoder[Seq[A]]].asInstanceOf[LuauEncoder[List[A]]]
    // Delegation via structural subtyping; explicit instance avoids ambiguity.

  given [A: LuauEncoder]: LuauEncoder[Array[A]] with
    def encode[H](value: Array[A], sink: Sink[H]): Unit =
      sink.beginTable()
      var i = 1
      for elem <- value do
        sink.pushArrayValue(i, elem)
        i += 1
      sink.endTable()

  given [A: LuauEncoder]: LuauEncoder[Vector[A]] with
    def encode[H](value: Vector[A], sink: Sink[H]): Unit =
      sink.beginTable()
      var i = 1
      for elem <- value do
        sink.pushArrayValue(i, elem)
        i += 1
      sink.endTable()

  // ---- Map (String keys -> table) ------------------------------------

  /** Map[String, V] encodes as a Luau table with string keys.
   *  Key ordering is undefined; Luau tables are unordered.
   */
  given [V: LuauEncoder]: LuauEncoder[Map[String, V]] with
    def encode[H](value: Map[String, V], sink: Sink[H]): Unit =
      sink.beginTable()
      for (k, v) <- value do
        sink.pushField(k, v)
      sink.endTable()

  // ---- Case class derivation via Mirror (ADR-0006) -------------------

  /** Automatic derivation for case classes:
   *    t["fieldName"] = fieldValue  for each field.
   *
   *  Usage:
   *    case class Point(x: Double, y: Double) derives LuauEncoder
   *    // OR: given LuauEncoder[Point] = LuauEncoder.derived
   *
   *  Field names are taken from Mirror.ProductOf.MirroredElemLabels at
   *  compile time (no reflection). All field types must have LuauEncoder
   *  instances.
   */
  inline def derived[A](using m: scala.deriving.Mirror.ProductOf[A]): LuauEncoder[A] =
    new LuauEncoder[A]:
      def encode[H](value: A, sink: Sink[H]): Unit =
        sink.beginTable()
        encodeProduct(value.asInstanceOf[Product], sink)
        sink.endTable()

  private inline def encodeProduct[H](
    product: Product,
    sink:    Sink[H],
  )(using m: scala.deriving.Mirror.ProductOf[?]): Unit =
    import scala.compiletime.*
    inline erasedValue[m.MirroredElemTypes] match
      case _: EmptyTuple => ()
      case _: (head *: tail) =>
        // Implementation: iterate labels and values in parallel using
        // summonAll[Tuple.Map[m.MirroredElemTypes, LuauEncoder]].
        // Full inline derivation implementation uses summonInline and
        // compiletime.constValueTuple for label extraction.
        ???  // Filled in by the implementing agent; skeleton shown.
```

Note to implementing agent: the `derived` macro body uses `scala.compiletime.summonInline`, `scala.compiletime.constValueTuple`, and `scala.compiletime.erasedValue` to iterate over `MirroredElemTypes` and `MirroredElemLabels` at compile time. The pattern is identical to standard Scala 3 typeclass derivation (e.g., as shown in the Scala 3 reference for `Show` derivation). No macro annotations are needed; `inline def derived` with `Mirror.ProductOf` is sufficient.

### File 13: `core/src/luau/core/codec/LuauDecoder.scala`

**Purpose:** The typeclass governing Luau→Host decoding. Reads values from the Luau stack by index using non-raising Binding accessors.

```scala
package luau.core.codec

import luau.core.*

/** Typeclass: A can be decoded from a Luau stack value.
 *
 *  Laws:
 *  1. decode reads the value at the given stack index WITHOUT modifying
 *     the stack (no pop; callers pop explicitly if needed).
 *  2. decode returns Left(LuaError) if the value at idx is the wrong type.
 *  3. decode must not raise; all errors are returned as Left.
 *
 *  The stack index `idx` follows Luau convention:
 *    1..n: absolute from stack bottom of the current call frame
 *    -1: top of stack; -2: one below top; etc.
 */
trait LuauDecoder[A]:
  def decode[H](binding: Binding[H], state: H, idx: Int): Either[LuaError, A]

object LuauDecoder:
  def apply[A](using dec: LuauDecoder[A]): LuauDecoder[A] = dec

  // ---- Primitive instances -------------------------------------------

  given LuauDecoder[Unit] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Unit] =
      if b.isNil(s, idx) then Right(())
      else Left(LuaError.runtime(s"expected nil at stack index $idx"))

  given LuauDecoder[Boolean] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Boolean] =
      Right(b.toBoolean(s, idx))  // toBoolean never fails in Luau

  given LuauDecoder[Double] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Double] =
      b.toNumber(s, idx).toRight(
        LuaError.runtime(s"expected number at stack index $idx, got ${b.typeAt(s, idx)}")
      )

  given LuauDecoder[Int] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Int] =
      b.toNumber(s, idx).map(_.toInt).toRight(
        LuaError.runtime(s"expected number (int) at stack index $idx")
      )

  given LuauDecoder[Long] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Long] =
      b.toNumber(s, idx).map(_.toLong).toRight(
        LuaError.runtime(s"expected number (long) at stack index $idx")
      )

  // ---- String / bytes -----------------------------------------------

  /** Decode a Luau string as raw bytes. */
  given LuauDecoder[IArray[Byte]] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, IArray[Byte]] =
      b.toBytes(s, idx).toRight(
        LuaError.runtime(s"expected string at stack index $idx, got ${b.typeAt(s, idx)}")
      )

  /** Decode a Luau string as a UTF-8 Java String.
   *  Returns Left if the bytes are not valid UTF-8.
   */
  given LuauDecoder[String] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, String] =
      b.toBytes(s, idx) match
        case None => Left(LuaError.runtime(s"expected string at stack index $idx"))
        case Some(bytes) =>
          try Right(new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8))
          catch case _: java.nio.charset.CharacterCodingException =>
            Left(LuaError.runtime(s"string at index $idx is not valid UTF-8"))

  // ---- Option --------------------------------------------------------

  /** Nil decodes as None; otherwise delegates to A's decoder. */
  given [A: LuauDecoder]: LuauDecoder[Option[A]] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Option[A]] =
      if b.isNil(s, idx) then Right(None)
      else summon[LuauDecoder[A]].decode(b, s, idx).map(Some(_))

  // ---- Seq (1-indexed table -> Seq[A]) --------------------------------

  /** Decode a 1-indexed Luau table as a Seq[A].
   *  Iterates t[1], t[2], ... until the first nil.
   *  The table must be at stack index `idx`.
   */
  given [A: LuauDecoder]: LuauDecoder[Seq[A]] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Seq[A]] =
      if b.typeAt(s, idx) != LuaType.Table then
        return Left(LuaError.runtime(s"expected table at $idx, got ${b.typeAt(s, idx)}"))
      val buf = collection.mutable.ArrayBuffer.empty[A]
      var i   = 1
      var err: LuaError | Null = null
      while err == null do
        b.getArray(s, idx, i)  // pushes t[i]
        if b.isNil(s, -1) then
          b.pop(s, 1)
          err = null // sentinel: exit cleanly by checking
          return Right(buf.toSeq)
        summon[LuauDecoder[A]].decode(b, s, -1) match
          case Right(a)  => buf += a; b.pop(s, 1); i += 1
          case Left(e)   => b.pop(s, 1); return Left(e)
      Right(buf.toSeq)

  // ---- Map (string-keyed table -> Map[String, V]) --------------------

  /** Decode a Luau table as Map[String, V].
   *  Iterates via lua_next (exposed as Binding.next or via raw iteration).
   *  NOTE: Map decoding requires Binding.next support; this is a richer
   *  Binding method. For the initial implementation, decode only string keys.
   *  Implementing agent: add Binding.next(state: H, tableIdx: Int): Boolean
   *  to support general iteration; skeleton shown below.
   */
  given [V: LuauDecoder]: LuauDecoder[Map[String, V]] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Map[String, V]] =
      if b.typeAt(s, idx) != LuaType.Table then
        return Left(LuaError.runtime(s"expected table at $idx"))
      val map = collection.mutable.Map.empty[String, V]
      // Implementation requires Binding.tableNext; see File 5 addendum.
      // Skeleton: push nil (first key), then loop lua_next until false.
      Right(map.toMap)  // TODO: implement via Binding.tableNext

  // ---- Case class derivation -----------------------------------------

  inline def derived[A](using m: scala.deriving.Mirror.ProductOf[A]): LuauDecoder[A] =
    new LuauDecoder[A]:
      def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, A] =
        if b.typeAt(s, idx) != LuaType.Table then
          return Left(LuaError.runtime(s"expected table for case class at $idx"))
        // For each field label: push field name, lua_rawget, decode, pop.
        // Full implementation uses summonInline and constValueTuple.
        ???  // Skeleton; implementing agent fills body.
```

### File 14: `core/src/luau/core/codec/SinkImpl.scala`

**Purpose:** The default concrete implementation of `Sink[H]` backed by a `Binding[H]` instance. This is the implementation used by both the Panama backend and the WASM backend — they simply construct `SinkImpl(binding, state)` and pass it to `LuauEncoder.encode`.

```scala
package luau.core.codec

import luau.core.*

/** Default Sink[H] implementation: delegates every operation to Binding[H].
 *
 *  Table nesting is tracked via a stack (the Luau stack itself plus
 *  a depth counter for validation in dev mode).
 *
 *  Stack discipline:
 *  - beginTable: calls binding.newTable; recordNestingDepth += 1.
 *  - pushKey: encodes key using its LuauEncoder, leaves on stack.
 *  - pushValue: encodes value, then calls binding.rawSet.
 *  - pushArrayValue: encodes value, then calls binding.setArray(n).
 *  - endTable: recordNestingDepth -= 1; table remains on stack as result.
 *
 *  After encode() returns, exactly one new value (the encoded result)
 *  is on the stack at the top.
 */
final class SinkImpl[H](
  val binding: Binding[H],
  val state:   H,
) extends Sink[H]:

  private var depth: Int = 0

  def pushNil(): Unit = binding.pushNil(state)
  def pushBoolean(value: Boolean): Unit = binding.pushBoolean(state, value)
  def pushNumber(value: Double): Unit   = binding.pushNumber(state, value)
  def pushBytes(bytes: IArray[Byte]): Unit = binding.pushBytes(state, bytes)

  def beginTable(): Unit =
    binding.newTable(state)
    depth += 1

  def endTable(): Unit =
    require(depth > 0, "endTable without matching beginTable")
    depth -= 1
    // table is already on stack; nothing to do

  def pushKey(key: LuaValue): Unit =
    key match
      case LuaValue.Nil              => binding.pushNil(state)
      case LuaValue.Bool(b)          => binding.pushBoolean(state, b)
      case LuaValue.Number(n)        => binding.pushNumber(state, n)
      case LuaValue.LuaString(bytes) => binding.pushBytes(state, bytes)
      case _: LuaValue.LuaRef[?]     =>
        throw IllegalArgumentException("LuaRef cannot be used as a table key (ADR-0006)")

  def pushValue[A: LuauEncoder](value: A): Unit =
    // The table is below on the stack. Standard lua_rawset pattern:
    // 1. Push key (already done by pushKey).
    // 2. Push value (done here).
    // 3. Call binding.rawSet.
    // BUT: SinkImpl.pushKey is called BEFORE pushValue.
    // So at this point: stack is [..., table, key].
    summon[LuauEncoder[A]].encode(value, this)
    // Now: [..., table, key, value]. Call rawSet.
    binding.rawSet(state, -3)  // tableIdx = -3 (below key and value)

  def pushArrayValue[A: LuauEncoder](n: Int, value: A): Unit =
    summon[LuauEncoder[A]].encode(value, this)
    // [..., table, value]. Call setArray.
    binding.setArray(state, -2, n)  // tableIdx = -2 (below value)
```

### File 15: `core/src/luau/core/codec/package.scala`

**Purpose:** Re-exports and extension methods making codec usage ergonomic.

```scala
package luau.core.codec

import luau.core.*

/** Extension on Binding for push-with-encoder and decode-at-index. */
extension [H](b: Binding[H])
  /** Push an encoded value onto the Luau stack. Exactly one value is pushed. */
  def pushEncoded[A: LuauEncoder](state: H, value: A): Unit =
    val sink = SinkImpl(b, state)
    summon[LuauEncoder[A]].encode(value, sink)

  /** Decode the value at stack index idx into A. Does not pop. */
  def decodeAt[A: LuauDecoder](state: H, idx: Int): Either[LuaError, A] =
    summon[LuauDecoder[A]].decode(b, state, idx)
```

### File 16: `core/test/luau/core/fake/FakeState.scala`

**Purpose:** The internal state of the Fake backend — a pure JVM heap simulation of a Luau stack, registry, and global table. No FFI. Used exclusively in tests.

```scala
package luau.core.fake

import luau.core.*
import scala.collection.mutable

/** JVM-heap simulation of a Luau state. Used by FakeBinding in tests.
 *
 *  Models a simplified subset of the Luau C stack:
 *  - A mutable stack of LuaValue (the Luau value stack).
 *  - A registry: Map[Int, LuaValue] (for Ref support).
 *  - A globals table: Map[String, LuaValue].
 *  - A NativeFn dispatch table: Map[Int, NativeFn[FakeState]].
 *  - A thread stack: mutable.Stack[FakeState] (for nested thread simulation).
 *
 *  This is NOT a real Luau VM. It does not run Luau bytecode. It exists
 *  only to enable testing Ref lifecycle, Scope, Codec, and Sink logic
 *  without any FFI dependency.
 */
final class FakeState:
  val stack:    mutable.ArrayDeque[LuaValue] = mutable.ArrayDeque.empty
  val registry: mutable.Map[Int, LuaValue]   = mutable.Map.empty
  val globals:  mutable.Map[String, LuaValue]= mutable.Map.empty
  val nativeFns: mutable.Map[Int, NativeFn[FakeState]] = mutable.Map.empty

  private var nextRegKey: Int  = 1
  private var nextFnId:   Int  = 1
  private var closed:     Boolean = false

  def allocRegKey(): Int   = { val k = nextRegKey; nextRegKey += 1; k }
  def allocFnId():   Int   = { val k = nextFnId;   nextFnId   += 1; k }
  def isClosed:      Boolean = closed
  def markClosed():  Unit    = closed = true

  /** Read stack at 1-based positive or negative index. */
  def stackIdx(idx: Int): Int =
    if idx > 0 then idx - 1
    else stack.size + idx  // -1 -> last, etc.

  def valueAt(idx: Int): LuaValue =
    val i = stackIdx(idx)
    if i < 0 || i >= stack.size then LuaValue.Nil
    else stack(i)
```

### File 17: `core/test/luau/core/fake/FakeBinding.scala`

**Purpose:** The Fake `Binding[FakeState]` implementation. Implements every method of the `Binding` trait against `FakeState`. Enables unit testing of all `core` logic without FFI.

```scala
package luau.core.fake

import luau.core.*
import luau.core.codec.*

/** A pure JVM-heap Binding[FakeState] for unit testing.
 *
 *  Does NOT run Luau bytecode. compileAndLoad is a stub that pushes a
 *  FakeFunction (a LuaValue.Nil placeholder) — sufficient to test Ref
 *  and Scope logic. resume() is a stub that returns Returned(0).
 *
 *  The purpose is to isolate codec, Sink, Ref, and Scope correctness
 *  from FFI concerns.
 */
object FakeBinding extends Binding[FakeState]:

  def newState(): FakeState = FakeState()

  def closeState(state: FakeState): Unit =
    state.markClosed()
    // simulate unref of all registry entries
    state.registry.clear()

  def compileAndLoad(
    state:     FakeState,
    source:    IArray[Byte],
    chunkname: String,
  ): Either[LuaError, Unit] =
    // Push a placeholder function value (not runnable).
    state.stack.addOne(LuaValue.Nil)
    Right(())

  def resume(thread: FakeState, nargs: Int): ResumeResult =
    // Stub: immediately return with 0 results. Sufficient for lifecycle tests.
    ResumeResult.Returned(0)

  def newThread(state: FakeState): FakeState = FakeState()

  // ---- Push -----------------------------------------------------------

  def pushNil(state: FakeState): Unit     = state.stack.addOne(LuaValue.Nil)
  def pushBoolean(state: FakeState, v: Boolean): Unit =
    state.stack.addOne(LuaValue.Bool(v))
  def pushNumber(state: FakeState, v: Double): Unit  =
    state.stack.addOne(LuaValue.Number(v))
  def pushBytes(state: FakeState, bytes: IArray[Byte]): Unit =
    state.stack.addOne(LuaValue.LuaString(bytes))
  def pushString(state: FakeState, v: String): Unit  =
    pushBytes(state, IArray.unsafeFromArray(v.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
  def pushFunction(state: FakeState, fnId: Int): Unit =
    state.stack.addOne(LuaValue.Nil)  // placeholder

  def pushRef(state: FakeState, registry: Int): Unit =
    val v = state.registry.getOrElse(registry, LuaValue.Nil)
    state.stack.addOne(v)

  // ---- Read -----------------------------------------------------------

  def typeAt(state: FakeState, idx: Int): LuaType =
    state.valueAt(idx) match
      case LuaValue.Nil           => LuaType.Nil
      case _: LuaValue.Bool       => LuaType.Boolean
      case _: LuaValue.Number     => LuaType.Number
      case _: LuaValue.LuaString  => LuaType.String
      case _: LuaValue.LuaRef[?]  => LuaType.Table  // simplified
      case _                      => LuaType.Nil

  def toNumber(state: FakeState, idx: Int): Option[Double] =
    state.valueAt(idx) match
      case LuaValue.Number(n) => Some(n)
      case _                  => None

  def toBoolean(state: FakeState, idx: Int): Boolean =
    LuaValue.isTruthy(state.valueAt(idx))

  def toBytes(state: FakeState, idx: Int): Option[IArray[Byte]] =
    state.valueAt(idx) match
      case LuaValue.LuaString(b) => Some(b)
      case _                     => None

  def stackTop(state: FakeState): Int = state.stack.size

  def setStackTop(state: FakeState, idx: Int): Unit =
    val newSize = if idx >= 0 then idx else state.stack.size + idx + 1
    while state.stack.size > newSize do state.stack.removeLast()
    while state.stack.size < newSize do state.stack.addOne(LuaValue.Nil)

  // ---- Table ----------------------------------------------------------

  def newTable(state: FakeState): Unit =
    // Push a fake table represented as a mutable Map wrapped in a Ref.
    // For testing purposes, we use a ListBuffer-backed fake table.
    state.stack.addOne(FakeTable.empty)

  def rawGet(state: FakeState, tableIdx: Int): Unit =
    val key   = state.stack.removeLast()
    val table = state.valueAt(tableIdx)
    val result = table match
      case t: FakeTable => t.rawGet(key)
      case _            => LuaValue.Nil
    state.stack.addOne(result)

  def rawSet(state: FakeState, tableIdx: Int): Unit =
    val value = state.stack.removeLast()
    val key   = state.stack.removeLast()
    val table = state.valueAt(tableIdx)
    table match
      case t: FakeTable => t.rawSet(key, value)
      case _            => ()

  def setArray(state: FakeState, tableIdx: Int, n: Int): Unit =
    val value = state.stack.removeLast()
    state.valueAt(tableIdx) match
      case t: FakeTable => t.rawSet(LuaValue.Number(n.toDouble), value)
      case _            => ()

  def getArray(state: FakeState, tableIdx: Int, n: Int): Unit =
    val result = state.valueAt(tableIdx) match
      case t: FakeTable => t.rawGet(LuaValue.Number(n.toDouble))
      case _            => LuaValue.Nil
    state.stack.addOne(result)

  def rawLen(state: FakeState, idx: Int): Long =
    state.valueAt(idx) match
      case t: FakeTable  => t.size.toLong
      case LuaValue.LuaString(b) => b.length.toLong
      case _             => 0L

  // ---- Registry -------------------------------------------------------

  def ref(state: FakeState): Ref[FakeState] =
    val value = state.stack.removeLast()
    val key   = state.allocRegKey()
    state.registry(key) = value
    new Ref[FakeState](state, key, this, "")

  def unref(state: FakeState, key: Int): Unit =
    if !state.isClosed then state.registry.remove(key)

  // ---- Native functions -----------------------------------------------

  def registerNativeFn(state: FakeState, fn: NativeFn[FakeState]): Unit =
    val id = state.allocFnId()
    state.nativeFns(id) = fn
    pushFunction(state, id)

  // ---- Globals --------------------------------------------------------

  def getGlobal(state: FakeState, name: String): Unit =
    state.stack.addOne(state.globals.getOrElse(name, LuaValue.Nil))

  def setGlobal(state: FakeState, name: String): Unit =
    state.globals(name) = state.stack.removeLast()
```

### File 18: `core/test/luau/core/fake/FakeTable.scala`

**Purpose:** A minimal hash-map-backed table used internally by `FakeBinding`. Not a full Luau table; only `rawGet`/`rawSet` are needed for codec testing.

```scala
package luau.core.fake

import luau.core.*
import scala.collection.mutable

/** Minimal mutable table for FakeBinding. LuaValue.LuaRef-free; uses
 *  Map[LuaValue, LuaValue] for simplicity (no array-part optimization).
 */
final class FakeTable extends LuaValue:
  val map: mutable.Map[LuaValue, LuaValue] = mutable.Map.empty

  def rawGet(key: LuaValue): LuaValue = map.getOrElse(key, LuaValue.Nil)
  def rawSet(key: LuaValue, value: LuaValue): Unit =
    if value == LuaValue.Nil then map.remove(key)
    else map(key) = value
  def size: Int = map.size

object FakeTable:
  def empty: FakeTable = FakeTable()
```

### File 19: `core/test/luau/core/RefScopeSpec.scala`

**Purpose:** Unit tests for `Ref` lifecycle and `Scope` using `FakeBinding`. Mill task: `./mill core.jvm.test` (or `core.js.test`).

```scala
package luau.core

import luau.core.fake.*
import munit.FunSuite

class RefScopeSpec extends FunSuite:

  test("Ref.close releases registry slot") {
    val state = FakeBinding.newState()
    FakeBinding.pushNumber(state, 42.0)
    val r = FakeBinding.ref(state)
    assert(!r.isClosed)
    assert(state.registry.contains(r.registry))
    r.close()
    assert(r.isClosed)
    assert(!state.registry.contains(r.registry))
  }

  test("Ref.close is idempotent") {
    val state = FakeBinding.newState()
    FakeBinding.pushNumber(state, 1.0)
    val r = FakeBinding.ref(state)
    r.close()
    r.close()  // must not throw
    assert(r.isClosed)
  }

  test("Ref.push restores value to stack") {
    val state = FakeBinding.newState()
    FakeBinding.pushNumber(state, 99.0)
    val r = FakeBinding.ref(state)
    assert(FakeBinding.stackTop(state) == 0)  // ref() popped the value
    r.push()
    assert(FakeBinding.stackTop(state) == 1)
    assert(FakeBinding.toNumber(state, -1).contains(99.0))
    r.close()
  }

  test("Scope closes all owned Refs in LIFO order") {
    val state = FakeBinding.newState()
    val scope = FakeBinding.openScope(state)
    FakeBinding.pushNumber(state, 1.0)
    val r1 = scope.captureTop()
    FakeBinding.pushNumber(state, 2.0)
    val r2 = scope.captureTop()
    assert(!r1.isClosed && !r2.isClosed)
    scope.close()
    assert(r1.isClosed && r2.isClosed)
  }

  test("Ref.close after state close is no-op") {
    val state = FakeBinding.newState()
    FakeBinding.pushNumber(state, 1.0)
    val r = FakeBinding.ref(state)
    FakeBinding.closeState(state)
    r.close()  // must not throw; unref skips closed state
    assert(r.isClosed)
  }

  test("Using.resource closes Ref on exit") {
    import scala.util.Using
    val state = FakeBinding.newState()
    FakeBinding.pushNumber(state, 7.0)
    var captured: Ref[FakeState] | Null = null
    Using.resource(FakeBinding.ref(state)) { r =>
      captured = r
      assert(!r.isClosed)
    }
    assert(captured != null && captured.isClosed)
  }
```

### File 20: `core/test/luau/core/codec/CodecSpec.scala`

**Purpose:** Unit tests for `LuauEncoder`, `LuauDecoder`, and `Sink` using `FakeBinding`.

```scala
package luau.core.codec

import luau.core.*
import luau.core.fake.*
import munit.FunSuite

class CodecSpec extends FunSuite:

  def encode[A: LuauEncoder](value: A): FakeState =
    val s = FakeBinding.newState()
    FakeBinding.pushEncoded(s, value)
    s

  def decode[A: LuauDecoder](s: FakeState, idx: Int = -1): Either[LuaError, A] =
    FakeBinding.decodeAt[A](s, idx)

  // ---- Primitives -------------------------------------------------------

  test("encode Double roundtrip") {
    val s = encode(3.14)
    assert(decode[Double](s).contains(3.14))
  }

  test("encode Boolean true") {
    val s = encode(true)
    assert(decode[Boolean](s).contains(true))
  }

  test("encode Boolean false") {
    val s = encode(false)
    assert(decode[Boolean](s).contains(false))
  }

  test("encode Int as Double") {
    val s = encode(42)
    assert(decode[Double](s).contains(42.0))
  }

  test("encode nil (Unit)") {
    val s = encode(())
    assert(decode[Unit](s).isRight)
    assert(FakeBinding.isNil(s, -1))
  }

  // ---- String -----------------------------------------------------------

  test("encode String as UTF-8") {
    val s = encode("hello")
    val decoded = decode[String](s)
    assert(decoded.contains("hello"))
  }

  test("encode String with non-ASCII (UTF-8)") {
    val s = encode("日本語")
    val decoded = decode[String](s)
    assert(decoded.contains("日本語"))
  }

  test("encode raw bytes roundtrip") {
    val bytes: IArray[Byte] = IArray(0xDE.toByte, 0xAD.toByte, 0xBE.toByte, 0xEF.toByte)
    val s = encode(bytes)
    assert(decode[IArray[Byte]](s).exists(_.sameElements(bytes)))
  }

  // ---- Option -----------------------------------------------------------

  test("encode Some(42.0)") {
    val s = encode(Some(42.0))
    assert(decode[Double](s).contains(42.0))
  }

  test("encode None as nil") {
    val s = encode(None: Option[Double])
    assert(FakeBinding.isNil(s, -1))
  }

  test("decode Option: nil -> None") {
    val s = FakeBinding.newState()
    FakeBinding.pushNil(s)
    assert(decode[Option[Double]](s).contains(None))
  }

  test("decode Option: number -> Some") {
    val s = FakeBinding.newState()
    FakeBinding.pushNumber(s, 7.0)
    assert(decode[Option[Double]](s).contains(Some(7.0)))
  }

  // ---- Seq / List -------------------------------------------------------

  test("encode Seq[Double] as 1-indexed table") {
    val s = encode(Seq(10.0, 20.0, 30.0))
    val decoded = decode[Seq[Double]](s)
    assert(decoded.contains(Seq(10.0, 20.0, 30.0)))
  }

  test("encode empty Seq as empty table") {
    val s = encode(Seq.empty[Double])
    val decoded = decode[Seq[Double]](s)
    assert(decoded.contains(Seq.empty))
  }

  // ---- Map[String, V] ---------------------------------------------------

  test("encode Map[String, Double]") {
    val input = Map("x" -> 1.0, "y" -> 2.0)
    val s = encode(input)
    // FakeBinding rawGet by string key
    val st = s
    FakeBinding.pushEncoded(st, "x")
    FakeBinding.rawGet(st, -2)
    assert(FakeBinding.toNumber(st, -1).contains(1.0))
  }

  // ---- Case class derivation --------------------------------------------

  case class Point(x: Double, y: Double) derives LuauEncoder, LuauDecoder

  test("derive LuauEncoder for case class") {
    val s = encode(Point(3.0, 4.0))
    // Table should be on top; verify t["x"] = 3.0
    val st = s
    FakeBinding.pushEncoded(st, "x")
    FakeBinding.rawGet(st, -2)
    assert(FakeBinding.toNumber(st, -1).contains(3.0))
  }

  test("derive LuauDecoder for case class") {
    val s = encode(Point(5.0, 6.0))
    val decoded = decode[Point](s)
    assert(decoded.contains(Point(5.0, 6.0)))
  }
```

### File 21: `core/test/luau/core/NativeFnResultSpec.scala`

**Purpose:** Tests for `NativeFnResult` and the `Async` primitive types.

```scala
package luau.core

import luau.core.fake.*
import munit.FunSuite

class NativeFnResultSpec extends FunSuite:

  test("NativeFnResult.Return holds nResults") {
    val r = NativeFnResult.Return(3)
    assertEquals(r, NativeFnResult.Return(3))
  }

  test("NativeFnResult.Fail holds value") {
    val v = LuaValue.LuaString.fromUtf8("oops")
    val f = NativeFnResult.Fail(v)
    assertEquals(f.value, v)
  }

  test("NativeFnResult.Suspend register is called") {
    var registered = false
    var cancelled  = false
    val s = NativeFnResult.Suspend { resume =>
      registered = true
      resume.succeed(LuaValue.Nil)
      Cancel(() => { cancelled = true })
    }
    // Simulate the Shim calling register:
    var result: Either[LuaError, LuaValue] | Null = null
    val cancel = s.register(Resume(r => result = r))
    assert(registered)
    assert(result.exists(_.isRight))
    cancel.cancel()
    assert(cancelled)
  }

  test("Resume.succeed produces Right(value)") {
    var got: Either[LuaError, LuaValue] | Null = null
    val resume = Resume(r => got = r)
    resume.succeed(LuaValue.Number(42.0))
    assert(got.exists(_.exists(_ == LuaValue.Number(42.0))))
  }

  test("Resume.fail produces Left(LuaError)") {
    var got: Either[LuaError, LuaValue] | Null = null
    val resume = Resume(r => got = r)
    resume.fail(LuaError.runtime("test error"))
    assert(got.exists(_.isLeft))
  }
```

### File 22: `core/src/luau/core/codec/instances/package.scala`

**Purpose:** A single file that re-exports all `given` codec instances so consumers can import them with a single wildcard: `import luau.core.codec.instances.given`.

```scala
package luau.core.codec.instances

export luau.core.codec.LuauEncoder.given
export luau.core.codec.LuauDecoder.given
```

---

## 5. Acceptance Criteria & Tests

### 5.1 Unit Tests (no FFI required — run against FakeBinding)

All tests run with: `./mill core.jvm.test` and `./mill core.js.test` (both must pass).

| Test class | Key assertions |
|---|---|
| `RefScopeSpec` | Ref close releases registry; idempotent; scope closes LIFO; close after state-close is no-op; Using.resource works |
| `CodecSpec` | All primitive roundtrips; String UTF-8; raw bytes; Option None→nil/Some→value; Seq 1-indexed roundtrip; Map string-key roundtrip; case class derivation encode+decode |
| `NativeFnResultSpec` | Return/Fail/Suspend construction; register called; Resume succeed/fail produce correct Either |

### 5.2 Cross-Platform Parity

`./mill core.js.test` must pass all the same tests on Scala.js without modification. This verifies that no JVM-specific API has leaked into `core`.

### 5.3 Codec Law Tests

For each implemented `LuauEncoder[A]` + `LuauDecoder[A]` pair, verify:
- `decode(encode(a)) == Right(a)` for representative values.
- `encode(a)` pushes exactly 1 value (stack grows by 1).
- `decode` does not modify the stack.

Named test: `CodecLawsSpec` — parameterized across all built-in codec pairs.

### 5.4 Ref Leak Detector (Dev Mode)

When system property `luau.devMode=true` is set, open a state, create a Ref, close the state WITHOUT closing the Ref, and assert that the leak detector reports the unclosed Ref (via a collected warning or exception). This test may be JVM-only.

Named test: `RefLeakDetectorSpec`.

### 5.5 Fake Backend Smoke Test

```
./mill core.jvm.test -- "*FakeBinding*"
```

Manually: create a `FakeState`, push several values, create a table via `newTable`/`rawSet`, use a `Scope` to capture refs, verify they are released, verify the stack is clean.

### 5.6 End-to-End Codec Test

Named test: `EndToEndCodecSpec`. Creates a `FakeState`, encodes a nested structure (a `Map[String, Seq[Double]]`), decodes it back, and asserts deep equality. This exercises `SinkImpl`, `LuauEncoder`, `LuauDecoder`, and `FakeBinding.rawGet`/`rawSet`/`getArray` together.

---

## 6. Risks & Gotchas

### 6.1 String Encoding Mismatch

Luau strings are byte strings (`LUA_TSTRING`). The Luau VM does not require UTF-8. Passing a Java `String` through `pushString` implicitly uses UTF-8 encoding — this is correct for text but will silently corrupt binary data. The `pushBytes` method exists for binary payloads. Document this clearly in `Binding` and `Sink` javadoc. Reference: `/home/hoangdinh/OSS/luau-scala/docs/research/topic-value-representation-and-tables.md` §3 (string interning internals).

### 6.2 Luau Number Model (No Integer Subtype)

Luau uses doubles exclusively. Encoding a Scala `Long` or `Int` as a `Double` loses precision for values > 2^53. The `LuauEncoder[Long]` must document this explicitly. Do NOT add a `LuaInt` case to `LuaValue` — that would contradict Luau semantics and create divergence from the research doc (`/home/hoangdinh/OSS/luau-scala/docs/research/topic-value-representation-and-tables.md` §1.1: "Luau numbers are doubles").

### 6.3 NaN and Infinity in Tables

Luau raises an error for NaN as a table key (`table index is NaN` in `lua_rawset`). The `LuauEncoder[Double]` for table keys must not produce NaN. The `Sink.pushKey` implementation must validate NaN and throw `IllegalArgumentException` before calling `Binding.rawSet`. Reference: `/home/hoangdinh/OSS/luau-scala/docs/research/topic-value-representation-and-tables.md` §8.2 (rawSet NaN check).

### 6.4 Stack Discipline in SinkImpl

`SinkImpl.pushValue` calls `rawSet` with `tableIdx = -3`. This is correct only if the table is exactly 2 levels below the top (table, key, then value is pushed). If an encoder pushes more than one value to the stack for a single `pushValue` call (which would violate the encoder contract), the table index becomes wrong silently. The dev-mode depth tracking must assert the stack grows by exactly 1 per `encode` call. Use `binding.stackTop(state)` before and after to assert in tests.

### 6.5 `LuaRef[H]` in `LuaValue`

The `LuaValue.LuaRef[H]` case introduces a type parameter on the otherwise unparameterized `LuaValue` sealed trait. This requires `LuaValue` to be either parameterized itself or use existential wrapping. The cleanest solution in Scala 3 is to use an existential wrapper: `final class LuaRef(val ref: Ref[?]) extends LuaValue` with `Ref[?]` as the erased type. This avoids propagating `H` through the entire `LuaValue` ADT. The `push()` method on `Ref` is called separately; `LuaRef` in `LuaValue` is a read-back result type (from `Binding.decodeAt` for a table/function slot), not a push-ready object.

### 6.6 Mirror Derivation and Scala.js

`scala.deriving.Mirror` is available in Scala.js. The `inline def derived` approach using `summonInline`/`constValueTuple` compiles to Scala.js. However, verify that `summonAll` on Scala.js does not produce a different code path. Run `core.js.test` as part of the codec suite.

### 6.7 Ref Idempotent close and Double-Unref

`Ref.close()` sets `closed = true` using a `@volatile` field. On JVM, this is sufficient for visibility between threads. However, a race condition exists if two threads race to call `close()` simultaneously — both could read `closed = false` before either sets it. Since `core` is designed for the Driver model (one thread owns the state at a time; ADR-0002), concurrent `close()` calls should not occur in production. However, if an `Async primitive` completion races with a Scope teardown, use an `AtomicBoolean` for `closed` rather than `@volatile Boolean`. Reference: `/home/hoangdinh/OSS/luau-scala/docs/adr/0005-deterministic-ref-lifetime-no-finalizer.md`.

### 6.8 FakeBinding is NOT a Luau VM

`FakeBinding.compileAndLoad` and `FakeBinding.resume` are stubs. They exist only to enable lifecycle and codec testing. Any test that attempts to run actual Luau bytecode using `FakeBinding` will fail silently (resume returns Returned(0) with no results). This is documented and intentional. Real execution tests belong in P04 (Panama) and P05 (WASM) integration test suites.

### 6.9 IArray[Byte] on Scala.js

`IArray[Byte]` is available on Scala.js (it is a Scala 3 standard library type). The underlying representation on Scala.js is a JavaScript typed array (`Int8Array`). Verify that `IArray.unsafeFromArray(arr.getBytes(...))` and `arr.toArray` roundtrip correctly in Scala.js tests, especially for multi-byte UTF-8 characters. Reference: Scala.js IArray docs.

---

## 7. Out of Scope / Deferred

| Item | Deferred to |
|---|---|
| Panama (`MemorySegment`-backed) implementation of `Binding` | P04 (`docs/plans/04-panama-backend-jvm.md`) |
| WASM (`js.Dynamic`-backed) implementation of `Binding` | P05 (`docs/plans/05-wasm-backend-js.md`) |
| Scheduler / Run queue / Task type | P06 (`docs/plans/06-scheduler-and-task-model.md`) |
| `Resume` callback enqueuing (the Run queue is not defined here) | P06 |
| Luau standard library loading (base, math, string, table, etc.) | P07 (`docs/plans/07-stdlib-and-task-library.md`) |
| Isolate management (multiple states) | P06 |
| `LuauDecoder[Map[String, V]]` full implementation (requires `Binding.tableNext`) | P04/P05 integration; add `tableNext` to `Binding` at that point |
| Dev-mode Ref leak detector (detailed allocation-site capture) | Can be implemented in P04 where native stack traces are available |
| `lx_interrupt` / Luau VM interrupt hook | P07 (sandboxing) |
| `coroutine.*` library exposure | P07 |
| Userdata (no host objects cross by reference — ADR-0006; this is by design) | Permanently out of scope per ADR |
| `LuauEncoder[Tuple]` / `LuauDecoder[Tuple]` | Nice-to-have; can be added as part of P07 or later |
| Batched push (coarse RPC path) | Deliberately deferred per ADR-0001 ("can be added selectively later") |
| `Binding.tableNext` (lua_next for map iteration) | Add to `Binding` trait in P04 when first needed |

---

## 8. References

The implementing agent must read these documents before writing any code:

### ADRs (all in `/home/hoangdinh/OSS/luau-scala/docs/adr/`)

| File | Relevance to this plan |
|---|---|
| `0001-embed-upstream-luau-via-slim-cpp-shim.md` | No protected calls across FFI; non-raising accessors only; Shim raises errors in C |
| `0002-movable-state-actor-concurrency.md` | Off-Driver completions must enqueue, not resume inline; `Resume` contract |
| `0003-stackless-task-model.md` | Why `Suspend(register)` exists; NativeFn must not block |
| `0004-coroutine-substrate-task-on-top.md` | Single-threaded MVP; Scheduler owns Tasks; `coroutine` substrate |
| `0005-deterministic-ref-lifetime-no-finalizer.md` | `Ref` is `AutoCloseable`, no finalizer, explicit-only release, `Scope` idiom |
| `0006-copy-only-data-boundary-via-codec-typeclass.md` | `LuauEncoder`/`LuauDecoder` compile-time enforcement; `Sink` for single-copy; strings as bytes; copy cost accepted |
| `0007-callback-based-async-and-tristate-native-return.md` | `NativeFnResult` tri-state; `Resume` one-shot thread-safe; `Cancel` first-class; trampoline ABI |

### CONTEXT.md

`/home/hoangdinh/OSS/luau-scala/CONTEXT.md` — read in full. Use exact terminology: Binding backend, Ref, Scope, Codec, Sink, Native function, Resume boundary, Async primitive, Suspension, Driver, Run queue, Isolate. Never "FFI layer", "pointer", "handle", "marshaller", "serializer", "builder".

### Research Documents

| File | Key sections |
|---|---|
| `/home/hoangdinh/OSS/luau-scala/docs/research/topic-value-representation-and-tables.md` | §1.1 (Luau doubles-only, no integer subtype), §8.2 (NaN table key error), §10 (final Scala value ADT recommendation) |
| `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-rust-ecosystem.md` | §4.4 mlua API shape — use as a reference for proven API surface (especially `Lua.sandbox`, `set_compiler`, `lua.load`, `lua.create_function` for analogous Scala API); §4.5 mluau (Luau continuations / yield pattern) |

### Master Plan Index

Cross-reference by exact filename:
- `docs/plans/01-project-scaffold-and-build-toolchain.md` — build setup this plan depends on
- `docs/plans/02-cpp-shim-abi.md` — the `lx_*` ABI this plan's `Binding` trait mirrors
- `docs/plans/04-panama-backend-jvm.md` — implements `Binding[MemorySegment]` against this plan's trait
- `docs/plans/05-wasm-backend-js.md` — implements `Binding[Int]` (WASM linear memory address) against this plan's trait
- `docs/plans/06-scheduler-and-task-model.md` — consumes `Resume`, `Cancel`, `NativeFnResult.Suspend` from this plan
- `docs/plans/07-stdlib-and-task-library.md` — uses `Binding`, `LuauEncoder`, `LuauDecoder` from this plan
