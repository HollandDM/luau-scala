# Plan 04 — Panama Backend (JVM)

**File**: `docs/plans/04-panama-backend-jvm.md`
**Status**: Draft
**Depends on**: P02 (`docs/plans/02-cpp-shim-abi.md`), P03 (`docs/plans/03-core-abstractions.md`)

---

## 1. Milestone & Goal

This plan delivers the JVM implementation of the `Binding` trait defined in P03, backed by the
`libluau-shim.so` native library produced in P02. Every `lx_*` Shim symbol is wired to a downcall
`MethodHandle` via `java.lang.foreign` (JDK 21 FFM API). The trampoline upcall is implemented as a
Panama upcall stub bound to a `MemorySegment` function pointer installed into each Luau state; a
`NativeFnDispatcher` keyed by integer `fnId` routes the stub back to a registered Scala
`NativeFn`. The tri-state return (`Return(n)` / `Fail(value)` / `Suspend(register)`) crosses the
FFM boundary as a pair of `int` fields in a shared `MemorySegment` struct, keeping the boundary
contract pure-C (ADR-0001, ADR-0007). `Scope` is implemented directly as
`java.lang.foreign.Arena`. UTF-8 `String` and raw `Array[Byte]` are marshaled through
`MemorySegment` allocated in a call-scoped confined `Arena`. The deliverable is the `panama`
Mill module passing a suite of JVM integration tests: load-and-run a Luau script, call a Native
function, exercise the Ref lifecycle, and complete a Suspend/resume round-trip.

---

## 2. Dependencies

### P02 — C++ Shim ABI (`docs/plans/02-cpp-shim-abi.md`)

Provides:
- `libluau-shim.so` (Linux) / `libluau-shim.dylib` (macOS) placed in the build output path
  known to the Mill module (e.g., `out/shim/compile.dest/libluau-shim.so`).
- The C header `shim/include/lx.h` declaring every `lx_*` symbol consumed here (exact
  signatures listed in §4.3 below).
- The `lx_trampoline_t` C typedef for the upcall function pointer signature.
- The `LxCallResult` C struct carrying the tri-state return across the boundary.
- The `lx_set_trampoline(lua_State*, lx_trampoline_t)` function that installs the per-state
  upcall pointer.

### P03 — Core Abstractions (`docs/plans/03-core-abstractions.md`)

Provides:
- `trait Binding` in `core/src/luau/Binding.scala` — the abstract interface this plan
  implements.
- `NativeFnReturn` ADT: `Return(n: Int)`, `Fail(value: LuauValue)`, `Suspend(register: Resume => Cancel)`.
- `type Resume = Either[LuaError, Result] => Unit` and `type Cancel = () => Unit`.
- `trait Ref extends AutoCloseable` with `luaRef: Int`.
- `trait Scope extends AutoCloseable` — the abstract scoped-Ref owner.
- `LuaError`, `Result`, `LuauValue` types.
- `LuauEncoder[A]` / `LuauDecoder[A]` typeclasses and the `Sink` streaming interface.
- `type NativeFn = (Binding, Int) => NativeFnReturn` (or equivalent as defined in P03).

This plan produces no new abstract types; it only provides a concrete `object PanamaBinding`
(or `class PanamaState`) that satisfies `Binding`.

---

## 3. Design Context

### 3.1 No protected calls across the FFM boundary (ADR-0001)

The Luau VM raises errors via `lua_error`, which calls `longjmp`. A `longjmp` that unwinds through
a Panama downcall frame or a JVM JIT frame is undefined behavior. Therefore:

- All Luau execution enters exclusively through the **Resume boundary**: `lx_resume` in the Shim,
  which wraps `lua_resume` and converts any error into a numeric status. This plan never calls
  `lx_pcall`, `luaL_dostring`, or any other Luau entry point that can `longjmp`.
- Stack reads after `lx_resume` use only non-raising accessors (`lx_type`, `lx_tointeger`,
  `lx_tonumber`, `lx_tostring`, `lx_toboolean`, `lx_tolstring`) that do not call metamethods
  and never `longjmp`.
- A Scala `NativeFn` that wants to signal an error returns `Fail(value)`; the Shim trampoline
  calls `lua_error` itself in pure C after the upcall returns. The JVM frame is fully unwound
  before `longjmp` fires (ADR-0001).

### 3.2 Stackless Task model (ADR-0003)

A suspended Task holds no native C stack. The Shim's `lua_yield` call — triggered by
`Suspend(register)` — unwinds the C stack completely back into `lx_resume`, which returns to the
Panama downcall, which returns to the JVM. The JVM holds no live `setjmp` buffer across a
`Suspend`. This is structurally safe. The implementing agent must not store any native-stack
reference across a yield; everything must be heap-resident.

### 3.3 Upcall stub lifetime and Arena ownership (ADR-0005)

The upcall `MemorySegment` (function pointer given to the Shim) must remain valid for the entire
lifetime of the Luau state. It must be allocated in a `Arena.ofShared()` that is closed only when
the state is closed — not in a call-scoped Arena. Closing the Arena while a Luau state holds the
pointer is a use-after-free in C.

### 3.4 Scope = Arena (CONTEXT.md)

The glossary defines: "On the Panama backend it is a `java.lang.foreign.Arena`." A `Scope` is
implemented here as a `java.lang.foreign.Arena` (confined). Opening a `Scope` via
`PanamaState.scoped { ... }` creates a new confined `Arena`; closing the scope closes the Arena,
which in turn frees any `MemorySegment`s allocated inside it (string buffers, scratch struct
allocations). Refs opened inside the Scope are tracked separately and unref'd on scope exit before
the Arena is closed (the unref goes through the `lx_unref` downcall, not through memory
deallocation).

### 3.5 Tri-state upcall return (ADR-0007)

The Shim trampoline upcall function has C signature:

```c
// lx_trampoline_t
int lx_trampoline(lua_State* L, int fnId, LxCallResult* out);
```

Return value conventions (defined in `lx.h`, owned by P02):

| Return int | Meaning                                  |
|-----------|------------------------------------------|
| `LX_RETURN`  (0) | Normal return; `out->nResults` results on stack |
| `LX_FAIL`    (1) | Error; `out->errorValue` marshaled on stack or `out->errorMsg` set |
| `LX_SUSPEND` (2) | Task yields; `out->token` is an opaque `int64_t` the Shim passes to `lua_yield` |

The Panama upcall stub marshals this return from the Scala side: the Scala dispatcher fills an
output `LxCallResult*` struct in native memory and returns the integer tag. The Shim reads it
and acts in pure C — calls `lua_error` for `LX_FAIL`, calls `lua_yield(L, k)` for `LX_SUSPEND`.

### 3.6 Off-Driver completions enqueue only (ADR-0002, ADR-0004)

When a `Resume` callback is called (completing a `Suspend`), it must only post to the Run queue.
It must never call `lx_resume` directly. This plan does not implement the Run queue (that is
P06), but the `PanamaState` must expose a `postResume` hook so P06 can wire it. For the
integration tests in this plan, a simple synchronous driver loop is sufficient.

### 3.7 Native-memory threading caveat (ADR-0002)

The Luau state lives in native (off-heap) memory. The JMM does not formally model writes to
native memory. In practice, on HotSpot, the `java.util.concurrent` queue's `put`/`take` pair
compiles to CPU fences that order all memory including native. Correct on HotSpot; not guaranteed
by the JLS. Do NOT "optimize away" the queue handoff or resume a state on a thread that did not
acquire it from the Run queue. For the MVP (single-threaded), this caveat is academic but must be
documented so the Scheduler in P06 does not violate it.

---

## 4. Task Breakdown

### 4.1 Mill module declaration

**File**: `/home/hoangdinh/OSS/luau-scala/build.mill` (to be updated from P01 scaffold)

The `panama` module must:
- Target JVM, Scala 3.
- Depend on `core` module.
- Add `--enable-native-access=ALL-UNNAMED` (or the module name) to the JVM options, required
  for `java.lang.foreign` on JDK 21.
- Reference the native library path for `System.load` in tests (via a system property
  `luau.shim.lib` set in the test JVM args).

Relevant Mill snippet (skeleton only, exact syntax per P01's build file style):

```scala
object panama extends ScalaModule {
  def scalaVersion = "3.x.x"
  def moduleDeps   = Seq(core)
  def forkArgs = Seq(
    "--enable-native-access=ALL-UNNAMED",
    s"-Dluau.shim.lib=${shimLibPath}"    // filled by P01's build
  )
  object test extends ScalaTests with TestModule.Munit { ... }
}
```

### 4.2 Shim header constants mirrored in Scala

**File**: `/home/hoangdinh/OSS/luau-scala/panama/src/luau/panama/LxConstants.scala`

Purpose: mirror the C-level integer constants from `lx.h` so Scala code does not embed magic
numbers.

```scala
package luau.panama

// Mirror of lx.h — keep in sync with the C header (P02)
object LxConstants:
  // lx_resume status codes
  val LX_OK:      Int = 0
  val LX_YIELD:   Int = 1
  val LX_ERRMEM:  Int = 4
  val LX_ERRERR:  Int = 5
  val LX_ERRRUN:  Int = 2   // runtime error
  val LX_ERRSYNTAX: Int = 3

  // NativeFn return tag (written into LxCallResult.tag by the upcall stub)
  val LX_RETURN:  Int = 0
  val LX_FAIL:    Int = 1
  val LX_SUSPEND: Int = 2

  // lx_type tags
  val LX_TNIL:      Int = 0
  val LX_TBOOLEAN:  Int = 1
  val LX_TNUMBER:   Int = 3
  val LX_TSTRING:   Int = 4
  val LX_TTABLE:    Int = 5
  val LX_TFUNCTION: Int = 6
  val LX_TTHREAD:   Int = 8
  val LX_TINTEGER:  Int = 18  // LUA_TINTEGER as exposed by lx_type
```

### 4.3 Downcall MethodHandle declarations

**File**: `/home/hoangdinh/OSS/luau-scala/panama/src/luau/panama/LxHandles.scala`

Purpose: load `libluau-shim.so` and obtain a downcall `MethodHandle` for every `lx_*` symbol.
All handles are package-private `val`s on a singleton object; `PanamaState` accesses them
directly.

The `Linker` and `SymbolLookup` are acquired once at class-load time:

```scala
package luau.panama

import java.lang.foreign.*
import java.lang.invoke.MethodHandle

object LxHandles:
  private val linker: Linker = Linker.nativeLinker()

  // Load the shared library. Path resolved from system property set by Mill.
  System.load(System.getProperty("luau.shim.lib"))
  private val lookup: SymbolLookup = SymbolLookup.loaderLookup()

  private def sym(name: String): MemorySegment =
    lookup.find(name).orElseThrow(() => new LinkageError(s"symbol not found: $name"))

  private def handle(name: String, desc: FunctionDescriptor): MethodHandle =
    linker.downcallHandle(sym(name), desc)

  import ValueLayout.*

  // ── State lifecycle ──────────────────────────────────────────────────────
  // lua_State* lx_newstate(void)
  val lx_newstate: MethodHandle = handle("lx_newstate",
    FunctionDescriptor.of(ADDRESS))

  // void lx_close(lua_State* L)
  val lx_close: MethodHandle = handle("lx_close",
    FunctionDescriptor.ofVoid(ADDRESS))

  // ── Compile + load ───────────────────────────────────────────────────────
  // int lx_compile(lua_State* L, const char* source, size_t len,
  //                const char* chunkname, int optimize)
  // Returns 0 on success, non-zero on syntax error; error message on stack.
  val lx_compile: MethodHandle = handle("lx_compile",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT))

  // ── Execution ────────────────────────────────────────────────────────────
  // int lx_resume(lua_State* L, lua_State* from, int nargs)
  val lx_resume: MethodHandle = handle("lx_resume",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))

  // ── Stack / type inspection (non-raising) ────────────────────────────────
  // int lx_gettop(lua_State* L)
  val lx_gettop: MethodHandle = handle("lx_gettop",
    FunctionDescriptor.of(JAVA_INT, ADDRESS))

  // void lx_settop(lua_State* L, int idx)
  val lx_settop: MethodHandle = handle("lx_settop",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT))

  // int lx_type(lua_State* L, int idx)
  val lx_type: MethodHandle = handle("lx_type",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))

  // ── Push ops ─────────────────────────────────────────────────────────────
  // void lx_pushnil(lua_State* L)
  val lx_pushnil: MethodHandle = handle("lx_pushnil",
    FunctionDescriptor.ofVoid(ADDRESS))

  // void lx_pushboolean(lua_State* L, int b)
  val lx_pushboolean: MethodHandle = handle("lx_pushboolean",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT))

  // void lx_pushnumber(lua_State* L, double n)
  val lx_pushnumber: MethodHandle = handle("lx_pushnumber",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE))

  // void lx_pushinteger(lua_State* L, int64_t n)
  val lx_pushinteger: MethodHandle = handle("lx_pushinteger",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG))

  // void lx_pushlstring(lua_State* L, const char* s, size_t len)
  val lx_pushlstring: MethodHandle = handle("lx_pushlstring",
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG))

  // ── Read ops (non-raising) ───────────────────────────────────────────────
  // int lx_toboolean(lua_State* L, int idx)
  val lx_toboolean: MethodHandle = handle("lx_toboolean",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))

  // double lx_tonumberx(lua_State* L, int idx, int* isnum)
  val lx_tonumberx: MethodHandle = handle("lx_tonumberx",
    FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_INT, ADDRESS))

  // int64_t lx_tointegerx(lua_State* L, int idx, int* isnum)
  val lx_tointegerx: MethodHandle = handle("lx_tointegerx",
    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS))

  // const char* lx_tolstring(lua_State* L, int idx, size_t* len)
  // NOTE: returned pointer is owned by Luau; copy immediately.
  val lx_tolstring: MethodHandle = handle("lx_tolstring",
    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS))

  // ── Table ops ────────────────────────────────────────────────────────────
  // void lx_newtable(lua_State* L)
  val lx_newtable: MethodHandle = handle("lx_newtable",
    FunctionDescriptor.ofVoid(ADDRESS))

  // void lx_rawget(lua_State* L, int idx)
  val lx_rawget: MethodHandle = handle("lx_rawget",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT))

  // void lx_rawset(lua_State* L, int idx)
  val lx_rawset: MethodHandle = handle("lx_rawset",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT))

  // void lx_rawgeti(lua_State* L, int idx, int n)
  val lx_rawgeti: MethodHandle = handle("lx_rawgeti",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT))

  // void lx_rawseti(lua_State* L, int idx, int n)
  val lx_rawseti: MethodHandle = handle("lx_rawseti",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT))

  // ── Refs ─────────────────────────────────────────────────────────────────
  // int lx_ref(lua_State* L)
  // Pops top of stack, stores in registry, returns integer ref key.
  val lx_ref: MethodHandle = handle("lx_ref",
    FunctionDescriptor.of(JAVA_INT, ADDRESS))

  // void lx_unref(lua_State* L, int ref)
  val lx_unref: MethodHandle = handle("lx_unref",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT))

  // void lx_getref(lua_State* L, int ref)
  // Pushes the value associated with ref onto the stack.
  val lx_getref: MethodHandle = handle("lx_getref",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT))

  // ── Native function registration ─────────────────────────────────────────
  // void lx_pushnativefunction(lua_State* L, int fnId, const char* name)
  // Installs a closure that calls the trampoline with the given fnId.
  val lx_pushnativefunction: MethodHandle = handle("lx_pushnativefunction",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS))

  // void lx_set_trampoline(lua_State* L, lx_trampoline_t fn)
  // Installs the per-state upcall function pointer. Called once on state init.
  val lx_set_trampoline: MethodHandle = handle("lx_set_trampoline",
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))

  // ── Stack helpers ────────────────────────────────────────────────────────
  // void lx_pushvalue(lua_State* L, int idx)
  val lx_pushvalue: MethodHandle = handle("lx_pushvalue",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT))

  // void lx_pop(lua_State* L, int n)
  val lx_pop: MethodHandle = handle("lx_pop",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT))

  // void lx_rotate(lua_State* L, int idx, int n)
  val lx_rotate: MethodHandle = handle("lx_rotate",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT))

  // int lx_newthread(lua_State* L)
  // Pushes a new Luau thread (coroutine) onto the stack; returns its stack index.
  val lx_newthread: MethodHandle = handle("lx_newthread",
    FunctionDescriptor.of(JAVA_INT, ADDRESS))
```

**Note on `JAVA_LONG` for `size_t`**: Panama's `JAVA_LONG` maps to C `long` on LP64 Linux/macOS,
which is 64-bit and correct for `size_t`. On Windows ILP64 this would need `ADDRESS` layout; the
MVP targets Linux/macOS only (P01 scope).

### 4.4 LxCallResult native struct layout

**File**: `/home/hoangdinh/OSS/luau-scala/panama/src/luau/panama/LxCallResult.scala`

Purpose: define the `MemoryLayout` matching the C struct `LxCallResult` declared in `lx.h` (P02).
The Shim writes this struct; the upcall stub reads it. The Scala upcall stub writes it when
returning from a Native function; the Shim reads it.

The C struct (canonical definition owned by P02, mirrored here):

```c
// lx.h (from P02)
typedef struct {
    int      tag;        // LX_RETURN | LX_FAIL | LX_SUSPEND
    int      nResults;   // used when tag == LX_RETURN
    int64_t  token;      // opaque yield token when tag == LX_SUSPEND
    // errorMsg is communicated via the Luau stack for LX_FAIL
} LxCallResult;
```

```scala
package luau.panama

import java.lang.foreign.*

object LxCallResult:
  import ValueLayout.*

  val LAYOUT: StructLayout = MemoryLayout.structLayout(
    JAVA_INT.withName("tag"),
    JAVA_INT.withName("nResults"),
    JAVA_LONG.withName("token"),    // 8-byte aligned; struct is 16 bytes total
  ).withName("LxCallResult")

  val TAG_OFFSET:      Long = LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("tag"))
  val NRESULTS_OFFSET: Long = LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("nResults"))
  val TOKEN_OFFSET:    Long = LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("token"))

  val SIZE: Long = LAYOUT.byteSize()

  def tag(seg: MemorySegment):      Int  = seg.get(JAVA_INT,  TAG_OFFSET)
  def nResults(seg: MemorySegment): Int  = seg.get(JAVA_INT,  NRESULTS_OFFSET)
  def token(seg: MemorySegment):    Long = seg.get(JAVA_LONG, TOKEN_OFFSET)

  def setTag(seg: MemorySegment, v: Int):  Unit = seg.set(JAVA_INT,  TAG_OFFSET,      v)
  def setNResults(seg: MemorySegment, v: Int):  Unit = seg.set(JAVA_INT,  NRESULTS_OFFSET, v)
  def setToken(seg: MemorySegment, v: Long): Unit = seg.set(JAVA_LONG, TOKEN_OFFSET,    v)
```

**Alignment note**: the struct layout must exactly match what the C compiler produces for
`LxCallResult`. If P02 adds or removes fields, this file must change in lockstep. The struct size
(currently 16 bytes) and field offsets should be asserted in a C-level test (§5).

### 4.5 Upcall stub and NativeFnDispatcher

**File**: `/home/hoangdinh/OSS/luau-scala/panama/src/luau/panama/NativeFnDispatcher.scala`

Purpose: (1) maintain the `fnId → NativeFn` registry for a single state, (2) implement the
upcall method that Panama will stub into a C function pointer, (3) expose
`allocateUpcallStub(arena)` to produce the `MemorySegment` function pointer given to
`lx_set_trampoline`.

The upcall function must match the C signature of `lx_trampoline_t`:

```c
// lx.h
typedef int (*lx_trampoline_t)(lua_State* L, int fnId, LxCallResult* out);
```

Panama `FunctionDescriptor` for the upcall:

```scala
import ValueLayout.*
val TRAMPOLINE_DESC: FunctionDescriptor = FunctionDescriptor.of(
  JAVA_INT,   // return: tag (LX_RETURN | LX_FAIL | LX_SUSPEND)
  ADDRESS,    // lua_State* L
  JAVA_INT,   // fnId
  ADDRESS     // LxCallResult* out
)
```

Full skeleton:

```scala
package luau.panama

import java.lang.foreign.*
import java.lang.invoke.{MethodHandles, MethodType}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import luau.core.*
import luau.panama.LxConstants.*

final class NativeFnDispatcher(state: PanamaState):

  // fnId -> NativeFn registry; updated from the Driver thread only in MVP.
  // ConcurrentHashMap for safety against future multi-threaded Scheduler (P06).
  private val fns = new ConcurrentHashMap[Int, NativeFn]()
  private var nextId = 1

  def register(fn: NativeFn): Int =
    val id = nextId
    nextId += 1
    fns.put(id, fn)
    id

  def unregister(id: Int): Unit = fns.remove(id)

  /**
   * The upcall method. Panama reflectively wraps this into a C function pointer.
   * Called by the Shim trampoline on the Driver thread (lua_resume's call stack).
   *
   * Contract (ADR-0001, ADR-0007):
   *  - MUST NOT throw. Any exception is caught, converted to Fail, and written to `out`.
   *  - MUST NOT call lx_resume or any re-entrant Shim call.
   *  - Returns LX_RETURN, LX_FAIL, or LX_SUSPEND via `out` and return int.
   *
   * @param L   lua_State* passed by the trampoline
   * @param fnId integer key identifying which NativeFn to call
   * @param outPtr LxCallResult* the Shim will read after this returns
   * @return tag: LX_RETURN | LX_FAIL | LX_SUSPEND
   */
  def dispatch(L: MemorySegment, fnId: Int, outPtr: MemorySegment): Int =
    val fn = fns.get(fnId)
    if fn == null then
      // Unknown fnId: treat as Fail
      LxCallResult.setTag(outPtr, LX_FAIL)
      state.pushString(s"unknown fnId: $fnId")
      return LX_FAIL

    val result =
      try fn(state, fnId)
      catch
        case t: Throwable =>
          // Never propagate across the FFM boundary.
          state.pushString(t.getMessage.nn)
          NativeFnReturn.Fail(LuauValue.Nil) // errorMsg already on stack

    result match
      case NativeFnReturn.Return(n) =>
        LxCallResult.setTag(outPtr, LX_RETURN)
        LxCallResult.setNResults(outPtr, n)
        LX_RETURN

      case NativeFnReturn.Fail(_) =>
        // Error value is already on the Luau stack (pushed by the NativeFn).
        LxCallResult.setTag(outPtr, LX_FAIL)
        LX_FAIL

      case NativeFnReturn.Suspend(register) =>
        // Allocate a token that the SuspendRegistry will use to route the resume.
        val token = state.suspendRegistry.allocToken(register)
        LxCallResult.setTag(outPtr, LX_SUSPEND)
        LxCallResult.setToken(outPtr, token)
        LX_SUSPEND

  /**
   * Creates a Panama upcall stub (C function pointer) that calls `dispatch`.
   * The stub's lifetime is tied to `arena`; `arena` MUST outlive the Luau state.
   * Call exactly once per state, during state initialization.
   */
  def allocateUpcallStub(arena: Arena): MemorySegment =
    val mh = MethodHandles.lookup().bind(
      this,
      "dispatch",
      MethodType.methodType(
        classOf[Int],
        classOf[MemorySegment],
        classOf[Int],
        classOf[MemorySegment]
      )
    )
    Linker.nativeLinker().upcallStub(mh, NativeFnDispatcher.TRAMPOLINE_DESC, arena)

object NativeFnDispatcher:
  import ValueLayout.*
  val TRAMPOLINE_DESC: FunctionDescriptor = FunctionDescriptor.of(
    JAVA_INT,
    ADDRESS,
    JAVA_INT,
    ADDRESS
  )
```

### 4.6 SuspendRegistry

**File**: `/home/hoangdinh/OSS/luau-scala/panama/src/luau/panama/SuspendRegistry.scala`

Purpose: map the opaque `long` token (passed through `lua_yield` → `lua_resume` continuation
argument) back to the `register: Resume => Cancel` thunk, so that when the Scheduler (P06)
observes a `LX_YIELD` status it can wire up the async completion.

```scala
package luau.panama

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import luau.core.*

final class SuspendRegistry:
  private val seq   = new AtomicLong(1L)
  private val table = new ConcurrentHashMap[Long, NativeFnReturn.Suspend]()

  /** Stores `suspend` and returns an opaque token to pass through lua_yield. */
  def allocToken(suspend: NativeFnReturn.Suspend): Long =
    val tok = seq.getAndIncrement()
    table.put(tok, suspend)
    tok

  /**
   * Called by the Scheduler (P06) after lx_resume returns LX_YIELD.
   * Returns the Suspend so the Scheduler can call register(resume) and
   * wire the async completion.
   * Returns None if the token is unknown (should not happen in correct code).
   */
  def consume(token: Long): Option[NativeFnReturn.Suspend] =
    Option(table.remove(token))
```

**Threading note**: `AtomicLong` + `ConcurrentHashMap` are safe for concurrent `allocToken`
(from Driver thread during upcall) and concurrent `consume` (from Scheduler thread, which may
differ in a multi-worker setup per ADR-0002). In the single-threaded MVP this is moot but
costs nothing.

### 4.7 String and byte-array marshaling utilities

**File**: `/home/hoangdinh/OSS/luau-scala/panama/src/luau/panama/Marshal.scala`

Purpose: conversion between Scala `String`/`Array[Byte]` and Luau C strings (UTF-8 byte
pointer + length). All allocations use a caller-provided `Arena` so they are freed when the
Arena closes.

Key points:
- Luau strings are byte strings. UTF-8 encoding for `String` (per ADR-0006: "Strings cross as
  bytes; a UTF-8 `String` view … sits on top").
- `lx_tolstring` returns a pointer into Luau-managed memory (a `TString` data array — see
  `runtime-luau-official-cpp.md §4.6`). The pointer is valid only while the value stays on the
  stack. Copy immediately into a Scala `Array[Byte]`.
- Never use `MemorySegment.ofAddress(ptr)` without a size — it produces a zero-byte segment and
  any read is undefined. Always use `reinterpret(len)` with the correct length.

```scala
package luau.panama

import java.lang.foreign.*
import java.nio.charset.StandardCharsets

object Marshal:
  import ValueLayout.*

  /**
   * Allocate a null-terminated UTF-8 C string in `arena` from a Scala String.
   * Returns a MemorySegment pointing to the C string (including null terminator).
   */
  def toNativeString(s: String, arena: Arena): MemorySegment =
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    val seg   = arena.allocate(bytes.length.toLong + 1L, 1L)
    MemorySegment.copy(bytes, 0, seg, JAVA_BYTE, 0L, bytes.length)
    seg.set(JAVA_BYTE, bytes.length.toLong, 0.toByte)  // null terminator
    seg

  /**
   * Copy a Luau string (pointer + length) from native memory into a Scala Array[Byte].
   * `ptr` is a raw address returned by lx_tolstring; `len` is the size in bytes.
   * MUST be called while the string is still on the Luau stack.
   */
  def fromNativeBytes(ptr: MemorySegment, len: Long): Array[Byte] =
    val sized = ptr.reinterpret(len)
    val out   = new Array[Byte](len.toInt)
    MemorySegment.copy(sized, JAVA_BYTE, 0L, out, 0, len.toInt)
    out

  /**
   * Convenience: fromNativeBytes decoded as UTF-8.
   */
  def fromNativeString(ptr: MemorySegment, len: Long): String =
    new String(fromNativeBytes(ptr, len), StandardCharsets.UTF_8)

  /**
   * Allocate a scratch Arena-allocated MemorySegment of `size` bytes aligned to `align`.
   * Typically used for output-parameter structs (isnum flag, LxCallResult).
   */
  def scratch(size: Long, align: Long, arena: Arena): MemorySegment =
    arena.allocate(size, align)
```

### 4.8 PanamaState — the Binding implementation

**File**: `/home/hoangdinh/OSS/luau-scala/panama/src/luau/panama/PanamaState.scala`

Purpose: the central class that implements `Binding` for the JVM. Owns the `lua_State*` pointer
(as `MemorySegment`), the shared `Arena` for the upcall stub lifetime, the
`NativeFnDispatcher`, and the `SuspendRegistry`. Exposes the complete `Binding` API.

Key design decisions:
- The `lua_State*` is stored as a `MemorySegment` with `ADDRESS` layout (opaque pointer, no
  dereference from Scala).
- All downcalls go through `LxHandles.lx_*` via `invokeExact` — no dynamic dispatch overhead.
- `invokeExact` is used (not `invoke`) because it avoids argument boxing and is safe when
  the handle's type matches exactly. This requires explicit cast to the correct type. Use a
  helper `call` method to centralize the `invokeExact` pattern.
- The call-scoped scratch `Arena` (confined, for string buffers within a single operation) is
  created and closed inside each method. This avoids leaking buffers between calls.
- `closed` flag prevents use-after-close.

Skeleton (key methods only; the implementing agent fills the bodies):

```scala
package luau.panama

import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import luau.core.*
import luau.panama.LxConstants.*
import luau.panama.LxHandles.*

final class PanamaState private (
  val L: MemorySegment,    // lua_State* — opaque, never dereferenced from Scala
  stateArena: Arena        // shared Arena; closed when state closes
) extends Binding:

  val dispatcher:       NativeFnDispatcher = new NativeFnDispatcher(this)
  val suspendRegistry:  SuspendRegistry    = new SuspendRegistry()

  @volatile private var closed = false

  // ── Compile + load ─────────────────────────────────────────────────────
  def compile(source: Array[Byte], chunkName: String, optimizeLevel: Int): Either[LuaError, Unit] =
    checkOpen()
    Arena.ofConfined().nn.use { arena =>
      val srcSeg   = arena.allocate(source.length.toLong + 1L, 1L)
      MemorySegment.copy(source, 0, srcSeg, ValueLayout.JAVA_BYTE, 0L, source.length)
      srcSeg.set(ValueLayout.JAVA_BYTE, source.length.toLong, 0.toByte)
      val nameSeg  = Marshal.toNativeString(chunkName, arena)
      val rc = lx_compile.invokeExact(L, srcSeg, source.length.toLong, nameSeg, optimizeLevel)
        .asInstanceOf[Int]
      if rc == 0 then Right(())
      else
        val errStr = peekString(-1)
        lx_pop.invokeExact(L, 1): Unit
        Left(LuaError(errStr))
    }

  // ── Resume boundary ────────────────────────────────────────────────────
  /**
   * The ONLY entry point for executing Luau code from the Host (ADR-0001).
   * Returns (status, Option[SuspendToken]).
   * The Scheduler (P06) interprets the status.
   */
  def resume(thread: MemorySegment, from: MemorySegment, nArgs: Int): ResumeResult =
    checkOpen()
    val rc = lx_resume.invokeExact(thread, from, nArgs).asInstanceOf[Int]
    rc match
      case LX_OK    => ResumeResult.Returned
      case LX_YIELD =>
        // The yield token was written into LxCallResult by the upcall stub before
        // lua_yield returned. Retrieve it from the registry.
        // NOTE: The token is communicated via the continuation argument to lua_yield.
        // The Shim passes it back as the first resume argument on the next resume.
        // For Suspend, the token was stored in SuspendRegistry during dispatch().
        // The Scheduler reads it from the last stored token (impl detail, see §4.9).
        ResumeResult.Yielded
      case LX_ERRRUN | LX_ERRMEM | LX_ERRERR =>
        val msg = peekString(-1)
        lx_pop.invokeExact(L, 1): Unit
        ResumeResult.Error(LuaError(msg))
      case _ =>
        ResumeResult.Error(LuaError(s"unexpected lx_resume status: $rc"))

  // ── Stack push operations ───────────────────────────────────────────────
  def pushNil(): Unit       = lx_pushnil.invokeExact(L): Unit
  def pushBoolean(b: Boolean): Unit =
    lx_pushboolean.invokeExact(L, if b then 1 else 0): Unit
  def pushNumber(n: Double): Unit  = lx_pushnumber.invokeExact(L, n): Unit
  def pushInteger(n: Long): Unit   = lx_pushinteger.invokeExact(L, n): Unit

  def pushString(s: String): Unit =
    Arena.ofConfined().nn.use { arena =>
      val bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      val seg   = arena.allocate(bytes.length.toLong, 1L)
      MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.length)
      lx_pushlstring.invokeExact(L, seg, bytes.length.toLong): Unit
    }

  def pushBytes(b: Array[Byte]): Unit =
    Arena.ofConfined().nn.use { arena =>
      val seg = arena.allocate(b.length.toLong, 1L)
      MemorySegment.copy(b, 0, seg, ValueLayout.JAVA_BYTE, 0L, b.length)
      lx_pushlstring.invokeExact(L, seg, b.length.toLong): Unit
    }

  // ── Stack read operations ───────────────────────────────────────────────
  def typeAt(idx: Int): Int =
    lx_type.invokeExact(L, idx).asInstanceOf[Int]

  def toBoolean(idx: Int): Boolean =
    lx_toboolean.invokeExact(L, idx).asInstanceOf[Int] != 0

  def toNumber(idx: Int): Option[Double] =
    Arena.ofConfined().nn.use { arena =>
      val flag = arena.allocate(ValueLayout.JAVA_INT)
      val n    = lx_tonumberx.invokeExact(L, idx, flag).asInstanceOf[Double]
      if flag.get(ValueLayout.JAVA_INT, 0L) != 0 then Some(n) else None
    }

  def toInteger(idx: Int): Option[Long] =
    Arena.ofConfined().nn.use { arena =>
      val flag = arena.allocate(ValueLayout.JAVA_INT)
      val n    = lx_tointegerx.invokeExact(L, idx, flag).asInstanceOf[Long]
      if flag.get(ValueLayout.JAVA_INT, 0L) != 0 then Some(n) else None
    }

  /**
   * Read string at `idx` as raw bytes. Copies immediately — the pointer from
   * lx_tolstring is only valid while the value is on the stack.
   */
  def toBytes(idx: Int): Option[Array[Byte]] =
    Arena.ofConfined().nn.use { arena =>
      val lenSeg = arena.allocate(ValueLayout.JAVA_LONG)
      val ptr    = lx_tolstring.invokeExact(L, idx, lenSeg).asInstanceOf[MemorySegment]
      if ptr == MemorySegment.NULL then None
      else
        val len  = lenSeg.get(ValueLayout.JAVA_LONG, 0L)
        Some(Marshal.fromNativeBytes(ptr, len))
    }

  def toString(idx: Int): Option[String] =
    toBytes(idx).map(new String(_, java.nio.charset.StandardCharsets.UTF_8))

  // ── Stack manipulation ──────────────────────────────────────────────────
  def getTop: Int  = lx_gettop.invokeExact(L).asInstanceOf[Int]
  def setTop(n: Int): Unit = lx_settop.invokeExact(L, n): Unit
  def pop(n: Int): Unit    = lx_pop.invokeExact(L, n): Unit
  def pushValue(idx: Int): Unit = lx_pushvalue.invokeExact(L, idx): Unit

  // ── Table operations ────────────────────────────────────────────────────
  def newTable(): Unit           = lx_newtable.invokeExact(L): Unit
  def rawGet(tableIdx: Int): Unit = lx_rawget.invokeExact(L, tableIdx): Unit
  def rawSet(tableIdx: Int): Unit = lx_rawset.invokeExact(L, tableIdx): Unit
  def rawGetI(tableIdx: Int, n: Int): Unit = lx_rawgeti.invokeExact(L, tableIdx, n): Unit
  def rawSetI(tableIdx: Int, n: Int): Unit = lx_rawseti.invokeExact(L, tableIdx, n): Unit

  // ── Ref lifecycle (ADR-0005) ────────────────────────────────────────────
  /**
   * Pops the top of stack, stores in the registry, returns a Ref.
   * The Ref is AutoCloseable: close() calls lx_unref.
   * The Scope (owning Arena) is NOT what frees the registry entry —
   * the explicit unref call on close() does. The Arena merely tracks
   * Ref lifetimes for scope-based batch release.
   */
  def makeRef(): Ref =
    checkOpen()
    val key = lx_ref.invokeExact(L).asInstanceOf[Int]
    new PanamaRef(key, this)

  def pushRef(ref: Ref): Unit =
    checkOpen()
    lx_getref.invokeExact(L, ref.luaRef): Unit

  def releaseRef(ref: Ref): Unit =
    if !closed then
      lx_unref.invokeExact(L, ref.luaRef): Unit

  // ── Native function registration ────────────────────────────────────────
  /**
   * Registers `fn` and pushes a Luau function closure onto the stack.
   * The closure, when called in a Luau script, triggers the trampoline upcall
   * which routes to `fn` via its fnId.
   */
  def registerNativeFn(name: String, fn: NativeFn): Unit =
    checkOpen()
    val fnId = dispatcher.register(fn)
    Arena.ofConfined().nn.use { arena =>
      val nameSeg = Marshal.toNativeString(name, arena)
      lx_pushnativefunction.invokeExact(L, fnId, nameSeg): Unit
    }

  // ── Scope (Arena-based) ─────────────────────────────────────────────────
  /**
   * Opens a new confined Scope (Arena). Refs opened inside the block
   * are tracked by the scope and unref'd when it closes.
   * ADR-0005: "state.scoped { … } closes all Refs opened inside it."
   */
  def scoped[A](block: PanamaScope ?=> A): A =
    val scopeArena = Arena.ofConfined()
    val scope      = new PanamaScope(scopeArena, this)
    try
      val result = block(using scope)
      scope.closeRefs()
      result
    finally
      scope.closeRefs()  // idempotent
      scopeArena.close()

  // ── Thread (coroutine) ops ──────────────────────────────────────────────
  def newThread(): MemorySegment =
    lx_newthread.invokeExact(L): Unit
    // lua_newthread pushes the new thread; retrieve it as the stack top
    // by making a ref and using lx_tothread (if exposed), or by keeping
    // a separate handle. Implementation detail: the Shim may return the
    // lua_State* directly from lx_newthread. Adjust signature per P02.
    // For now: push + ref + getref sequence to retrieve as MemorySegment.
    // (Exact impl depends on P02's lx_newthread return type.)
    ???

  // ── Lifecycle ───────────────────────────────────────────────────────────
  def close(): Unit =
    if !closed then
      closed = true
      lx_close.invokeExact(L): Unit
      stateArena.close()

  private def checkOpen(): Unit =
    if closed then throw new IllegalStateException("PanamaState is closed")

  private def peekString(idx: Int): String =
    toString(idx).getOrElse("<non-string error>")

object PanamaState:
  /**
   * Create a new Luau state, install the trampoline, and return the PanamaState.
   * The `stateArena` is shared; it must outlive the state.
   * Use the `apply` factory to manage the lifecycle correctly.
   */
  def open(): PanamaState =
    val stateArena = Arena.ofShared()
    val L = LxHandles.lx_newstate.invokeExact().asInstanceOf[MemorySegment]
    if L == MemorySegment.NULL then
      stateArena.close()
      throw new OutOfMemoryError("lx_newstate returned NULL")
    val ps   = new PanamaState(L, stateArena)
    val stub = ps.dispatcher.allocateUpcallStub(stateArena)
    LxHandles.lx_set_trampoline.invokeExact(L, stub): Unit
    ps

  /**
   * Open a state, run `f`, close the state. Resource-safe.
   */
  def use[A](f: PanamaState => A): A =
    val ps = open()
    try f(ps)
    finally ps.close()
```

### 4.9 ResumeResult ADT

**File**: `/home/hoangdinh/OSS/luau-scala/panama/src/luau/panama/ResumeResult.scala`

Purpose: structured result of `PanamaState.resume`, consumed by the Scheduler (P06).

```scala
package luau.panama

import luau.core.LuaError

enum ResumeResult:
  case Returned                  // lx_resume returned LX_OK: script finished
  case Yielded                   // lx_resume returned LX_YIELD: task suspended
  case Error(err: LuaError)      // lx_resume returned error status
```

The token for a `Yielded` result is retrieved from `SuspendRegistry` by the Scheduler using the
`token` value stored during the preceding `dispatch` call. For the integration tests (§5) a
simpler approach is used: the test driver calls `suspendRegistry.consume(lastToken)` where
`lastToken` is threaded through a `@volatile var` on the test's own driver loop.

### 4.10 PanamaRef

**File**: `/home/hoangdinh/OSS/luau-scala/panama/src/luau/panama/PanamaRef.scala`

Purpose: concrete `Ref` implementation. `close()` calls `lx_unref` exactly once.

```scala
package luau.panama

import luau.core.Ref
import java.util.concurrent.atomic.AtomicBoolean

final class PanamaRef(val luaRef: Int, state: PanamaState) extends Ref:
  private val released = new AtomicBoolean(false)

  /**
   * Release the registry entry. Idempotent (double-close is safe but dev-mode logs).
   * ADR-0005: "released only by explicit close(), by exiting the Scope, or by
   * tearing down the state — never by a GC finalizer."
   */
  override def close(): Unit =
    if released.compareAndSet(false, true) then
      state.releaseRef(this)
```

**No `Cleaner` / finalizer**: per ADR-0005. A leaked `PanamaRef` pins the Luau object until the
state closes (bounded by Isolate lifetime per ADR-0005).

### 4.11 PanamaScope

**File**: `/home/hoangdinh/OSS/luau-scala/panama/src/luau/panama/PanamaScope.scala`

Purpose: implements `Scope` as a confined `Arena` that tracks `PanamaRef`s and unrefs them on
close.

```scala
package luau.panama

import java.lang.foreign.Arena
import luau.core.{Ref, Scope}
import scala.collection.mutable.ArrayBuffer

final class PanamaScope(arena: Arena, state: PanamaState) extends Scope:
  private val refs = new ArrayBuffer[PanamaRef]()

  /** Track a Ref opened inside this scope; it will be closed with the scope. */
  def trackRef(ref: PanamaRef): Unit = refs += ref

  /** Called by PanamaState.scoped before closing the Arena. */
  def closeRefs(): Unit =
    refs.foreach { r =>
      if !r.isClosed then r.close()
    }
    refs.clear()

  override def close(): Unit =
    closeRefs()
    arena.close()

  def allocate(size: Long, align: Long): java.lang.foreign.MemorySegment =
    arena.allocate(size, align)
```

**Note**: `PanamaRef.isClosed` is a helper that reads the `AtomicBoolean`; add it to
`PanamaRef`.

### 4.12 Suspend / yield path end-to-end wiring

The complete data flow for a `Suspend` across the FFM boundary:

1. Scala `NativeFn` returns `NativeFnReturn.Suspend(register)` from within `dispatch`.
2. `dispatch` calls `suspendRegistry.allocToken(suspend)` → `token: Long`.
3. `dispatch` writes `LX_SUSPEND` + `token` into `LxCallResult*` and returns `LX_SUSPEND`.
4. The Shim trampoline reads `LxCallResult.tag == LX_SUSPEND` and calls:
   ```c
   lua_yield(L, 0, (void*)out->token, continuation_k);
   ```
   where `continuation_k` is a C continuation registered in the Shim (P02). The `token` travels
   as the `ctx` argument through `lua_yield`.
5. `lua_yield` unwinds the C stack back to `lx_resume`, which returns `LX_YIELD` to the downcall.
6. The Panama downcall returns to `PanamaState.resume`, which returns `ResumeResult.Yielded`.
7. The Scheduler (P06) sees `Yielded`, retrieves `lastToken` from the `SuspendRegistry` (exact
   mechanism: `PanamaState.lastYieldToken: Option[Long]` — a `@volatile var` set during step 2),
   calls `suspendRegistry.consume(token)` → `Suspend(register)`, calls
   `register(resume)` → `Cancel`.
8. When the async op completes, it calls `resume(Right(result))`, which posts to the Run queue
   (P06). The Scheduler dequeues and calls `PanamaState.resume(thread, from, 1)` — pushing
   the result first — which re-enters `lx_resume`. The Luau continuation `continuation_k` receives
   the result from the stack.

**Concrete addition to `PanamaState`** required for the wiring in step 7:

```scala
// Set by NativeFnDispatcher.dispatch() during the LX_SUSPEND path.
// Read by the Scheduler immediately after resume() returns Yielded.
// Safe because dispatch() and resume() always run on the same Driver thread.
@volatile var lastYieldToken: Long = -1L
```

And in `NativeFnDispatcher.dispatch`:

```scala
case NativeFnReturn.Suspend(register) =>
  val token = state.suspendRegistry.allocToken(NativeFnReturn.Suspend(register))
  state.lastYieldToken = token
  LxCallResult.setTag(outPtr, LX_SUSPEND)
  LxCallResult.setToken(outPtr, token)
  LX_SUSPEND
```

### 4.13 PanamaSink — Codec integration

**File**: `/home/hoangdinh/OSS/luau-scala/panama/src/luau/panama/PanamaSink.scala`

Purpose: implement the `Sink` streaming interface from P03 over `PanamaState` push operations.
This is what makes `LuauEncoder[A]` instances work on the JVM — encoders call Sink methods,
Sink calls `PanamaState.push*`.

```scala
package luau.panama

import luau.core.{Sink, LuauEncoder}

final class PanamaSink(state: PanamaState) extends Sink:
  def pushNil():             Unit = state.pushNil()
  def pushBoolean(b: Boolean): Unit = state.pushBoolean(b)
  def pushNumber(n: Double): Unit = state.pushNumber(n)
  def pushInteger(n: Long):  Unit = state.pushInteger(n)
  def pushString(s: String): Unit = state.pushString(s)
  def pushBytes(b: Array[Byte]): Unit = state.pushBytes(b)
  def beginTable(narr: Int, nrec: Int): Unit = state.newTable()
  def setField(key: String): Unit =
    state.pushString(key)
    state.rawSet(-3)   // table at -3, key at -2, value at -1 → after rotate
    // exact stack discipline depends on P03's Sink protocol; adjust per P03
  def endTable(): Unit = ()  // table is already on stack
```

The exact `Sink` method set is defined in P03. The implementing agent must align the method
names and calling convention with whatever P03 specifies.

---

## 5. Acceptance Criteria & Tests

All tests live in:
**Directory**: `/home/hoangdinh/OSS/luau-scala/panama/test/luau/panama/`

Run with Mill:

```bash
./mill panama.test
```

For a single test suite:

```bash
./mill panama.test.testOnly luau.panama.<SuiteName>
```

### Test 5.1 — `LibraryLoadTest`

**File**: `panama/test/luau/panama/LibraryLoadTest.scala`

Verifies the native library loads and `lx_newstate` / `lx_close` do not crash.

```
- "lx_newstate returns non-null pointer"
- "lx_close on valid state does not throw"
- "PanamaState.open() and close() lifecycle"
```

### Test 5.2 — `CompileAndRunTest`

**File**: `panama/test/luau/panama/CompileAndRunTest.scala`

Verifies compile-load-resume of a trivial Luau script returning a value.

```
- "compile valid script returns Right(())"
- "compile syntax error returns Left(LuaError) with message"
- "resume returns Returned for script that returns immediately"
- "resume returns stack with pushed number result"
- "run 'return 1 + 1' yields integer 2 on stack"
- "run multi-line script with local variables"
```

Concrete test case shape (using MUnit):

```scala
test("run 'return 1 + 1' yields integer 2 on stack") {
  PanamaState.use { ps =>
    ps.compile("return 1 + 1".getBytes, "test", 1).fold(
      e  => fail(s"compile failed: $e"),
      _  => ()
    )
    val result = ps.resume(ps.L, MemorySegment.NULL, 0)
    assertEquals(result, ResumeResult.Returned)
    assertEquals(ps.getTop, 1)
    assertEquals(ps.toNumber(-1), Some(2.0))
  }
}
```

### Test 5.3 — `NativeFunctionTest`

**File**: `panama/test/luau/panama/NativeFunctionTest.scala`

Verifies Native function registration, dispatch, and tri-state returns.

```
- "native function Return(1) — script receives correct value"
- "native function Fail — script pcall sees the error string"
- "unknown fnId — treated as Fail with descriptive message"
- "native function registered as global, callable from script"
- "multiple native functions coexist by fnId"
```

Concrete test case:

```scala
test("native function Return(1) — script receives correct value") {
  PanamaState.use { ps =>
    ps.registerNativeFn("addOne", (state, _) =>
      val n = state.toNumber(-1).getOrElse(0.0)
      state.pushNumber(n + 1)
      NativeFnReturn.Return(1)
    )
    // set as global: needs lx_setglobal equivalent or rawset on globals table
    // ... (exact global registration depends on P02 ABI; see lx_setglobal)
    ps.compile("return addOne(41)".getBytes, "test", 1).getOrElse(fail("compile"))
    ps.resume(ps.L, MemorySegment.NULL, 0)
    assertEquals(ps.toNumber(-1), Some(42.0))
  }
}
```

### Test 5.4 — `RefLifecycleTest`

**File**: `panama/test/luau/panama/RefLifecycleTest.scala`

Verifies Ref creation, use, and deterministic release (ADR-0005).

```
- "lx_ref stores table and lx_getref retrieves it"
- "lx_unref after close does not crash"
- "PanamaRef.close() is idempotent"
- "scoped block releases Refs on exit"
- "leaked Ref does not crash; state teardown frees it"
- "makeRef on table, close state, verify no use-after-free"
```

Concrete test:

```scala
test("scoped block releases Refs on exit") {
  PanamaState.use { ps =>
    ps.scoped { scope ?=>
      ps.newTable()
      val ref = ps.makeRef()
      // ref is valid inside the scope
      ps.pushRef(ref)
      assertEquals(ps.typeAt(-1), LxConstants.LX_TTABLE)
      ps.pop(1)
    }
    // After scope, ref is released. Verify by checking that getTop is 0.
    assertEquals(ps.getTop, 0)
  }
}
```

### Test 5.5 — `SuspendResumeTest`

**File**: `panama/test/luau/panama/SuspendResumeTest.scala`

Verifies the Suspend/yield path end-to-end. Uses a minimal synchronous test driver that
completes the Suspend synchronously (no real async I/O).

```
- "native Suspend returns Yielded from resume()"
- "synchronous resume after Suspend delivers result to Luau"
- "Suspend register() receives the Resume callback"
- "calling Resume with Right(result) re-enters lx_resume and returns Returned"
- "calling Resume with Left(LuaError) propagates error to script pcall"
- "Cancel callback fires on state teardown before completion"
```

Concrete test case:

```scala
test("synchronous resume after Suspend delivers result") {
  PanamaState.use { ps =>
    var capturedResume: Resume = null
    var capturedCancel: Cancel = null

    ps.registerNativeFn("waitForValue", (state, _) =>
      NativeFnReturn.Suspend { resume =>
        capturedResume = resume
        val cancel: Cancel = () => ()
        capturedCancel = cancel
        cancel
      }
    )
    // compile script: local v = waitForValue(); return v + 1
    ps.compile(
      "local v = waitForValue(); return v + 1".getBytes, "test", 1
    ).getOrElse(fail("compile"))

    val r1 = ps.resume(ps.L, MemorySegment.NULL, 0)
    assertEquals(r1, ResumeResult.Yielded)
    assert(capturedResume != null)

    // Complete the async op synchronously
    val token = ps.lastYieldToken
    val suspend = ps.suspendRegistry.consume(token)
    assert(suspend.isDefined)

    // Push the resume value (42.0) onto the Luau thread's stack
    ps.pushNumber(42.0)
    // Fire the resume callback (in real Scheduler this posts to queue;
    // here we call directly for test simplicity)
    capturedResume(Right(luau.core.Result.Number(42.0)))

    // Re-enter lx_resume with 1 argument (the pushed number)
    val r2 = ps.resume(ps.L, ps.L, 1)
    assertEquals(r2, ResumeResult.Returned)
    assertEquals(ps.toNumber(-1), Some(43.0))
  }
}
```

### Test 5.6 — `StringMarshalTest`

**File**: `panama/test/luau/panama/StringMarshalTest.scala`

Verifies UTF-8 string round-trips and byte-array push/read.

```
- "ASCII string round-trip via push/read"
- "UTF-8 multibyte string round-trip (Japanese, emoji)"
- "string with embedded null bytes via pushBytes"
- "toBytes returns None for non-string stack slot"
- "pushString and lx_tolstring agree on byte content"
- "empty string round-trip"
```

### Test 5.7 — `StructLayoutTest`

**File**: `panama/test/luau/panama/StructLayoutTest.scala`

Verifies `LxCallResult.LAYOUT` byte offsets and size match the C compiler's layout.
This catches any padding mismatch before runtime.

```
- "LxCallResult.SIZE == 16"
- "LxCallResult.TAG_OFFSET == 0"
- "LxCallResult.NRESULTS_OFFSET == 4"
- "LxCallResult.TOKEN_OFFSET == 8"
```

These values must match what `offsetof(LxCallResult, ...)` returns in C. If the C compiler adds
padding, update both the struct and this test.

---

## 6. Risks & Gotchas

### 6.1 `longjmp` through Panama frames — fatal

**Severity**: JVM crash, not an exception.

If any code path calls a Luau C function that can `longjmp` (e.g., `lua_getfield`, `lua_call`,
any raising accessor) through an active Panama downcall frame, the JVM crashes with a SIGSEGV or
corrupted stack. The rule from ADR-0001 is absolute: use only non-raising accessors after
`lx_resume` returns. The Shim (P02) must guarantee that none of its `lx_*` functions can raise.

The implementing agent must audit every `lx_*` call site and confirm the Shim does not call any
raising Luau API internally (see `runtime-luau-official-cpp.md §4.5` for the VM dispatch loop
and which operations raise).

### 6.2 `lx_tolstring` pointer lifetime

**Severity**: use-after-free (silent corruption or crash).

`lx_tolstring` returns a pointer into the `TString` data array inside the Luau heap (see
`runtime-luau-official-cpp.md §4.6`, `TString` layout: `char data[1]` inline). This pointer is
valid only as long as the string value remains on the Luau stack at the given index. The moment
any stack-modifying operation runs (push, pop, `lua_resume`), the GC may collect the string.
**Always** copy via `Marshal.fromNativeBytes` before any stack modification.

### 6.3 Upcall stub Arena must outlive the state

**Severity**: use-after-free in C when a Luau state calls a Native function after the upcall
stub has been freed.

The `stateArena` holding the upcall stub is closed in `PanamaState.close()`. The sequence must
be: (1) `lx_close(L)` — this tears down the Luau state and drops all Native function closures;
(2) then close `stateArena`. Reversing the order frees the stub while the state still holds a
pointer to it. The `PanamaState.close()` implementation must enforce this order explicitly.

### 6.4 Upcall thread pinning (Project Loom interaction)

**Severity**: deadlock risk on JDK 21+ with virtual threads.

Panama upcalls pin the carrier thread (the OS thread) for the duration of the call. If the
Scheduler (P06) runs Drivers on JDK 21 virtual threads and a upcall blocks (e.g., waits on a
lock inside a `NativeFn`), the carrier thread is pinned and the virtual thread scheduler may
starve. The design avoids this: `NativeFn`s must return quickly (no blocking) and signal
`Suspend` if they need to await. This plan's integration tests run on platform threads; P06
specifies the threading model.

### 6.5 Struct padding mismatch (LxCallResult)

**Severity**: silent data corruption — wrong tag/token values read.

The `LxCallResult` `StructLayout` in `LxCallResult.scala` must exactly match the C compiler's
layout for the C struct in `lx.h`. C compilers may add padding between `int nResults` and
`int64_t token` depending on target ABI. On x86-64 Linux (System V ABI) and macOS (Apple ABI),
`int64_t` is 8-byte aligned, so after two `int` fields (8 bytes total), no padding is added
before the `int64_t`. On ARM64 the same holds. If P02 reorders fields or changes types, the
`StructLayoutTest` (§5.7) will catch the mismatch.

### 6.6 `invokeExact` type mismatch — ClassCastException

**Severity**: `WrongMethodTypeException` at runtime.

`MethodHandle.invokeExact` requires the call-site type to exactly match the handle's type,
including boxing. When the handle returns `void`, the Scala call must be annotated with `: Unit`
(not `: Any`). When it returns `int`, the result must be cast with `.asInstanceOf[Int]`. Omitting
the return type or using the wrong boxed type throws `WrongMethodTypeException`. The skeleton
above uses the correct pattern; the implementing agent must follow it precisely for every handle
invocation.

### 6.7 Native memory visibility (ADR-0002 caveat)

As noted in §3.7: the JMM does not formally model off-heap native memory writes. The Luau state
lives in memory allocated by the C++ allocator (`global_State::frealloc`). In practice HotSpot
compiles `java.util.concurrent` queue operations to hardware fences that order all memory.
However, the implementing agent must not attempt to "skip" the Run queue fence as a performance
optimization — that would break the happens-before edge for native memory on multi-worker setups
(relevant when P06 enables multi-core; harmless but already correct for single-threaded MVP).

### 6.8 Coroutine stack vs. main state stack

The Luau main state (`lx_newstate` result) is itself a `lua_State*` and acts as both the global
state owner and a thread. `lua_resume` requires a non-main thread (a coroutine created with
`lua_newthread`). The Shim's `lx_compile` loads the chunk onto the main state's stack, but
`lx_resume` must be called on a child thread. The `lx_newthread` handle wraps
`lua_newthread(L)`, which pushes the new thread onto `L`'s stack. The implementing agent must
verify with P02 whether `lx_compile` loads onto the given thread or the main state, and how
`lx_resume` is called (from-thread argument). See `runtime-luau-official-cpp.md §4.8` for the
coroutine relationship between `lua_State` instances.

### 6.9 `Arena.ofConfined()` may return null on some JDK builds

The `nn` Scala null-safety postfix is used in the skeleton above. On standard JDK 21 this will
not return null, but defensive `nn` annotation is harmless and necessary for `-Yexplicit-nulls`
compatibility if P01 enables it.

---

## 7. Out of Scope / Deferred

| Concern | Owning Plan |
|---------|-------------|
| The Run queue and multi-worker Scheduler | P06 (`docs/plans/06-scheduler-and-task-model.md`) |
| Task lifecycle (spawn / park / complete) | P06 |
| `task.*` library natives | P07 (`docs/plans/07-stdlib-and-task-library.md`) |
| Opening Luau standard libraries (base, math, string, …) | P07 |
| Multi-core parallelism across Isolates | ADR-0002 (deferred); P06 scaffolds the queue |
| WASM backend for Scala.js | P05 (`docs/plans/05-wasm-backend-js.md`) |
| Codec `LuauEncoder` / `LuauDecoder` instances | P03 (defined there), used here via `PanamaSink` |
| `jextract`-generated bindings as an alternative source | P01 notes this option; this plan uses hand-written handles which are more explicit and auditable for the narrow `lx_*` set |
| Dev-mode Ref leak detector | P05 / future; not part of MVP |
| `lx_setglobal` / `lx_getglobal` convenience wrappers | Can be added to `PanamaState` trivially; omitted from skeleton for brevity but required for test §5.3 |
| Windows ILP64 / MSVC ABI compatibility | Post-MVP; `JAVA_LONG` for `size_t` is correct only on LP64 |
| Luau native codegen (`@native` / `--!native`) | Transparent — the Shim calls `luau_load` which respects Proto flags; no Panama-side work needed |

---

## 8. References

The implementing agent should read these documents before writing any code:

| Document | Path | Relevance |
|----------|------|-----------|
| CONTEXT.md (glossary) | `/home/hoangdinh/OSS/luau-scala/CONTEXT.md` | Binding, Ref, Scope, Scope=Arena, NativeFn, Suspend, Resume, Cancel, Driver, Isolate |
| ADR-0001 | `/home/hoangdinh/OSS/luau-scala/docs/adr/0001-embed-upstream-luau-via-slim-cpp-shim.md` | No pcall across FFI; resume-only entry; Shim raises in C |
| ADR-0002 | `/home/hoangdinh/OSS/luau-scala/docs/adr/0002-movable-state-actor-concurrency.md` | Native-memory visibility caveat; off-Driver completions enqueue only |
| ADR-0003 | `/home/hoangdinh/OSS/luau-scala/docs/adr/0003-stackless-task-model.md` | C stack fully unwound at Suspend; no native-stack reference across yield |
| ADR-0004 | `/home/hoangdinh/OSS/luau-scala/docs/adr/0004-coroutine-substrate-task-on-top.md` | Single-threaded MVP; off-Driver completions still enqueue |
| ADR-0005 | `/home/hoangdinh/OSS/luau-scala/docs/adr/0005-deterministic-ref-lifetime-no-finalizer.md` | Explicit close only; no Cleaner; Scope = batch release |
| ADR-0006 | `/home/hoangdinh/OSS/luau-scala/docs/adr/0006-copy-only-data-boundary-via-codec-typeclass.md` | Copy-only; strings as bytes; Sink encoding |
| ADR-0007 | `/home/hoangdinh/OSS/luau-scala/docs/adr/0007-callback-based-async-and-tristate-native-return.md` | Return/Fail/Suspend ADT; Resume one-shot; Cancel on teardown |
| Luau runtime internals | `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-official-cpp.md` | TString layout (§4.6, pointer lifetime), lua_State (§4.2), coroutine model (§4.8), GC write barriers (§8.2) |
| P02 (Shim ABI) | `docs/plans/02-cpp-shim-abi.md` | Exact `lx_*` C signatures; `LxCallResult` C struct; trampoline C signature; `lx_set_trampoline`; error/yield status codes |
| P03 (Core abstractions) | `docs/plans/03-core-abstractions.md` | `Binding` trait; `NativeFnReturn`; `Resume`/`Cancel`; `Ref`; `Scope`; `Sink`; `LuauEncoder`/`LuauDecoder`; `LuaError` |
| JDK 21 FFM API Javadoc | https://docs.oracle.com/en/java/docs/java/lang/foreign/package-summary.html | `Arena`, `MemorySegment`, `Linker`, `FunctionDescriptor`, `ValueLayout` |

---

*End of Plan 04 — Panama Backend (JVM)*
