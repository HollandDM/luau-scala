# Panama Backend — Deep Reference

**Date:** 2026-06-10

This document is a deep reference for the Panama Binding backend — the JVM-side Scala 3 subsystem that connects the Scala Host to the Luau Runtime via the `java.lang.foreign` (Panama FFM) API. It covers every source file in `panama/src`, the Shim ABI it consumes, the Arena and thread-safety invariants that make the whole thing safe, and the known bugs and missing pieces that define the JVM roadmap.

---

## 1. Position in the Architecture

The overall system has two Binding backends that call the same C Shim ABI (`lx.h`):

| Backend | Platform | Scala module | Shim artifact |
|---|---|---|---|
| **Panama backend** | JVM | `panama` (Mill) | `libluau-shim.so` / `.dylib` |
| WASM backend | JS (Scala.js) | `wasm` (Mill) | `luau-shim.wasm` |

Both backends implement `core.Binding[H]` (`core/jvm/src/luau/core/Binding.scala`). For the Panama backend, the type parameter `H` is `java.lang.foreign.MemorySegment` — every opaque handle (`lx_State`, `lx_Thread`) is represented as a raw pointer segment.

The Panama backend is wired into the Mill build at `build.mill:42–51`:

```scala
object panama extends LuauCrossPlatformModule {
  override def moduleDeps = super.moduleDeps ++ Seq(core.jvm)
  object test extends ScalaTests with TestModule.Munit {
    def forkArgs = Seq(
      "--enable-native-access=ALL-UNNAMED",
      "--enable-preview"
    )
  }
}
```

The two JVM flags are **mandatory** for any JVM process that runs Panama FFM code:
- `--enable-native-access=ALL-UNNAMED` — permits `MemorySegment`, `Arena`, and `Linker` calls from unnamed modules (the normal case for application JARs).
- `--enable-preview` — required on JDK 21 for the `Arena` API, which was in preview until JDK 22.

Neither flag is propagated to the main `panama` module's JVM; only the `test` fork receives them via `forkArgs`. In production, the embedding application must pass these flags to its own JVM invocation.

---

## 2. Native Library Build and Load Path

The Shim native library is built by `build.mill`'s `shim.nativeBuild` task (`build.mill:110–124`):

```
clang++ -std=c++17 -O2 <include dirs> -shared -fPIC \
  -o libluau-shim.so shim/src/lx.cpp <all luau *.cpp sources>
```

On macOS the output extension is `.dylib` and `-dynamiclib` replaces `-shared -fPIC`; this is detected at build time via `System.getProperty("os.name")` (`build.mill:103–108`).

`LxHandles` (`panama/src/luau/panama/LxHandles.scala:9–11`) loads the library at class-load time:

```scala
private val libPath = System.getProperty("luau.shim.lib")
if libPath != null then System.load(libPath)
else System.loadLibrary("luau-shim")
```

Two resolution strategies:
1. **Explicit path**: set the JVM system property `luau.shim.lib` to the absolute path of the `.so`/`.dylib`. Used in tests and CI where the library lives under Mill's `out/` tree.
2. **Library name fallback**: if the property is absent, `System.loadLibrary("luau-shim")` searches `java.library.path`, the standard JVM native library search path.

After loading, `LxHandles` acquires a `SymbolLookup.loaderLookup()` which resolves symbols from all libraries loaded by the current class loader, finding `lx_*` in the just-loaded library.

---

## 3. `LxHandles` — Downcall Binding Surface

**File:** `panama/src/luau/panama/LxHandles.scala`

`LxHandles` is a Scala `object` (singleton) that eagerly resolves all 39 `lx_*` downcall `MethodHandle`s at class-load time. Each handle is obtained by:
1. Looking up the symbol address from `SymbolLookup.loaderLookup()`.
2. Creating a downcall handle via `Linker.nativeLinker().downcallHandle(address, descriptor)`.

If any symbol is absent (library not loaded, old ABI), the constructor throws `UnsatisfiedLinkError` immediately, preventing silent failures at call time.

### Host upcall descriptor

```scala
val HOST_FN_DESC: FunctionDescriptor = FunctionDescriptor.of(
  JAVA_INT,   // return: LX_RETURN / LX_FAIL / LX_SUSPEND
  ADDRESS,    // state:    lx_State
  ADDRESS,    // thread:   lx_Thread
  JAVA_INT,   // fnId
  JAVA_INT,   // nArgs
  ADDRESS,    // nResults: int*
)
```

This descriptor is used in two places: `LxHandles.HOST_FN_DESC` (line 23) and identically duplicated in `NativeFnDispatcher.HOST_FN_DESC` (line 86 of `NativeFnDispatcher.scala`). Both must remain in sync with the C typedef:

```c
typedef int (*lx_HostFn)(lx_State state, lx_Thread thread,
                         int32_t fnId, int nArgs, int* nResults);
// shim/include/lx.h:47
```

### Complete handle table

| Scala val | C function | Descriptor summary |
|---|---|---|
| `lx_newstate` | `lx_newstate` | `(ADDRESS) → ADDRESS` |
| `lx_close` | `lx_close` | `(ADDRESS) → void` |
| `lx_main_thread` | `lx_main_thread` | `(ADDRESS) → ADDRESS` |
| `lx_new_thread` | `lx_new_thread` | `(ADDRESS) → ADDRESS` |
| `lx_thread_status` | `lx_thread_status` | `(ADDRESS, ADDRESS) → JAVA_INT` |
| `lx_compile_and_load` | `lx_compile_and_load` | `(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG) → JAVA_INT` |
| `lx_resume` | `lx_resume` | `(ADDRESS, ADDRESS, JAVA_INT, ADDRESS) → JAVA_INT` |
| `lx_push_nil` | `lx_push_nil` | `(ADDRESS, ADDRESS) → void` |
| `lx_push_boolean` | `lx_push_boolean` | `(ADDRESS, ADDRESS, JAVA_INT) → void` |
| `lx_push_number` | `lx_push_number` | `(ADDRESS, ADDRESS, JAVA_DOUBLE) → void` |
| `lx_push_integer` | `lx_push_integer` | `(ADDRESS, ADDRESS, JAVA_LONG) → void` |
| `lx_push_lstring` | `lx_push_lstring` | `(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG) → void` |
| `lx_push_ref` | `lx_push_ref` | `(ADDRESS, ADDRESS, JAVA_INT) → void` |
| `lx_push_copy` | `lx_push_copy` | `(ADDRESS, ADDRESS, JAVA_INT) → void` |
| `lx_pop` | `lx_pop` | `(ADDRESS, ADDRESS, JAVA_INT) → void` |
| `lx_stack_top` | `lx_stack_top` | `(ADDRESS, ADDRESS) → JAVA_INT` |
| `lx_type` | `lx_type` | `(ADDRESS, ADDRESS, JAVA_INT) → JAVA_INT` |
| `lx_to_number` | `lx_to_number` | `(ADDRESS, ADDRESS, JAVA_INT, ADDRESS) → JAVA_DOUBLE` |
| `lx_to_integer` | `lx_to_integer` | `(ADDRESS, ADDRESS, JAVA_INT, ADDRESS) → JAVA_LONG` |
| `lx_to_boolean` | `lx_to_boolean` | `(ADDRESS, ADDRESS, JAVA_INT) → JAVA_INT` |
| `lx_to_lstring` | `lx_to_lstring` | `(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS) → JAVA_INT` |
| `lx_rawlen` | `lx_rawlen` | `(ADDRESS, ADDRESS, JAVA_INT) → JAVA_LONG` |
| `lx_newtable` | `lx_newtable` | `(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT) → void` |
| `lx_rawget` | `lx_rawget` | `(ADDRESS, ADDRESS, JAVA_INT) → void` |
| `lx_rawset` | `lx_rawset` | `(ADDRESS, ADDRESS, JAVA_INT) → void` |
| `lx_rawgeti` | `lx_rawgeti` | `(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT) → void` |
| `lx_rawseti` | `lx_rawseti` | `(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT) → void` |
| `lx_setarray` | `lx_setarray` | `(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT) → void` |
| `lx_ref` | `lx_ref` | `(ADDRESS, ADDRESS, JAVA_INT) → JAVA_INT` |
| `lx_unref` | `lx_unref` | `(ADDRESS, JAVA_INT) → void` |
| `lx_register_native` | `lx_register_native` | `(ADDRESS, JAVA_INT, ADDRESS) → void` |
| `lx_set_suspend_token` | `lx_set_suspend_token` | `(ADDRESS, ADDRESS, JAVA_LONG) → void` |
| `lx_get_suspend_token` | `lx_get_suspend_token` | `(ADDRESS, ADDRESS) → JAVA_LONG` |
| `lx_openlibs` | `lx_openlibs` | `(ADDRESS, JAVA_INT) → JAVA_INT` |
| `lx_sandbox` | `lx_sandbox` | `(ADDRESS) → void` |
| `lx_open_libs` | `lx_open_libs` | `(ADDRESS) → void` |
| `lx_gc_step` | `lx_gc_step` | `(ADDRESS, JAVA_INT) → void` |
| `lx_gc_collect` | `lx_gc_collect` | `(ADDRESS) → void` |
| `lx_copy_error` | `lx_copy_error` | `(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG) → JAVA_LONG` |

Note that `lx_push_integer` and `lx_to_integer` are bound but **not exposed** through the `Binding[H]` trait. See Section 9 (Known Bugs).

---

## 4. `LxConstants` — Shim Return Codes

**File:** `panama/src/luau/panama/LxConstants.scala`

Scala mirrors of the `#define` constants in `shim/include/lx.h`:

```scala
// Resume boundary return codes
val LX_RESUME_OK     = 0   // thread returned normally
val LX_RESUME_YIELD  = 1   // thread yielded (Suspension)
val LX_RESUME_ERR    = 2   // runtime error
val LX_RESUME_MEMERR = 3   // memory allocation failure

// Native function upcall return codes
val LX_RETURN  = 0   // pushed nResults values
val LX_FAIL    = 1   // pushed one error value
val LX_SUSPEND = 2   // called lx_set_suspend_token, will yield

// Luau type tags (NOTE: different from standard Lua)
val LX_TNONE     = -1
val LX_TNIL      = 0
val LX_TBOOLEAN  = 1
val LX_TNUMBER   = 3   // double
val LX_TINTEGER  = 4   // int64_t (Luau-specific)
val LX_TVECTOR   = 5   // vector3 (Luau-specific)
val LX_TSTRING   = 6
val LX_TTABLE    = 7
val LX_TFUNCTION = 8
val LX_TUSERDATA = 9
val LX_TTHREAD   = 10
val LX_TBUFFER   = 11  // buffer (Luau-specific)
```

The type codes in `LxConstants` deliberately diverge from standard Lua 5.x. Luau adds `LX_TINTEGER=4`, `LX_TVECTOR=5`, and `LX_TBUFFER=11`, and shifts `LX_TSTRING` up to 6, `LX_TTABLE` to 7, etc. This divergence is the root cause of the `LuaType` mismatch bug described in Section 9.

---

## 5. `PanamaState` — Central Isolate Owner

**File:** `panama/src/luau/panama/PanamaState.scala`

`PanamaState` is the main `Binding[MemorySegment]` implementation. One instance owns exactly one Luau Isolate.

### 5.1 Data members

```scala
final class PanamaState private (
  val L: MemorySegment,           // main lx_State — all Binding calls pass this as "state"
  stateArena: Arena,              // Arena.ofShared — owns upcall stub + scratch memory
  val dispatcher: NativeFnDispatcher,
) extends Binding[MemorySegment]:
  val suspendRegistry: SuspendRegistry = new SuspendRegistry()
  @volatile var lastYieldToken: Long = -1L
  @volatile private var closed = false
```

- `L` is the `MemorySegment` wrapping the `lx_State*` pointer. It is the main thread handle passed as the first argument to every `lx_*` call where the C signature takes `lx_State state`.
- `stateArena` is an `Arena.ofShared()`. The "shared" designation means the arena and any memory allocated from it can be accessed from any thread. This is required because the upcall stub allocated inside it may theoretically be invoked from a C thread. In practice (single Driver), `Arena.ofConfined()` would also work, but `ofShared` is the defensive choice.
- `lastYieldToken` is `@volatile` to provide a happens-before guarantee between the `dispatch()` write and the Driver thread read after `lx_resume` returns.

### 5.2 Two-phase construction: `PanamaState.open()`

```scala
// panama/src/luau/panama/PanamaState.scala:263–273
def open(): PanamaState =
  val stateArena = Arena.ofShared()
  val dispatcher = new NativeFnDispatcher()
  val stub = dispatcher.allocateUpcallStub(stateArena)
  val L = LxHandles.lx_newstate.invokeExact(stub).asInstanceOf[MemorySegment]
  if L.address() == 0L then
    stateArena.close()
    throw new OutOfMemoryError("lx_newstate returned NULL")
  val ps = new PanamaState(L, stateArena, dispatcher)
  dispatcher.init(ps)
  ps
```

The ordering is critical:
1. `Arena.ofShared()` is created first — it will own the upcall stub's native memory for the entire lifetime of this Isolate.
2. `NativeFnDispatcher` is constructed before the Isolate. The dispatcher needs to exist so its `dispatch` method reference can be bound to the upcall stub.
3. `allocateUpcallStub(stateArena)` allocates the stub inside `stateArena`. The stub's native memory lifetime is now tied to `stateArena`.
4. `lx_newstate(stub)` passes the stub's address as the `lx_HostFn` raw function pointer to the C Shim. This pointer **must remain valid** for the entire life of the Isolate.
5. `dispatcher.init(ps)` stores the back-reference from dispatcher to `PanamaState`. This must happen after `L` is set, because `dispatch()` uses `ps.L`.

### 5.3 Lifecycle: `close()`

```scala
// panama/src/luau/panama/PanamaState.scala:226–230
def close(): Unit =
  if !closed then
    closed = true
    LxHandles.lx_close.invokeExact(L): Unit
    stateArena.close()
```

`lx_close(L)` runs first, freeing the Luau heap and invalidating all `lx_Thread` handles. Only then is `stateArena.close()` called, releasing the upcall stub's native memory. Reversing this order would free the stub while Luau's GC finalizers might still reference it.

### 5.4 Per-call scratch memory: `withArena`

```scala
// panama/src/luau/panama/PanamaState.scala:257–260
private def withArena[A](f: Arena => A): A =
  val a = Arena.ofConfined()
  try f(a)
  finally a.close()
```

Every operation that needs to pass a buffer to C (string arguments, error buffers, integer out-params) uses a fresh `Arena.ofConfined()` allocated and freed within a single call. `ofConfined()` arenas are not thread-safe; they are owned by the creating thread. This is safe here because all Binding operations must execute on the single Driver thread. The confined arena is strictly cheaper than the shared arena for short-lived allocations.

### 5.5 Resume boundary

`PanamaState.resume()` is the only entry point that executes Luau code:

```scala
// panama/src/luau/panama/PanamaState.scala:53–70
def resume(thread: MemorySegment, nargs: Int): ResumeResult =
  withArena { arena =>
    val nResultsSeg = arena.allocate(ValueLayout.JAVA_INT)
    val rc = LxHandles.lx_resume.invokeExact(L, thread, nargs, nResultsSeg).asInstanceOf[Int]
    val nResults = nResultsSeg.get(ValueLayout.JAVA_INT, 0L)
    rc match
      case LX_RESUME_OK    => ResumeResult.Returned(nResults)
      case LX_RESUME_YIELD => ResumeResult.Yielded(nResults)
      case LX_RESUME_ERR   => ResumeResult.Error(LuaError.runtime(readError(thread)))
      case LX_RESUME_MEMERR => ResumeResult.Error(LuaError.memory("lx_resume: memory allocation failed"))
      case _               => ResumeResult.Error(LuaError.runtime(s"unexpected lx_resume status: $rc"))
  }
```

The C-level `lx_resume` wraps `lua_resume` and guarantees that no `longjmp` crosses the FFI boundary (`shim/src/lx.cpp:177–198`). Errors become `LX_RESUME_ERR`; yields become `LX_RESUME_YIELD`. The `nResultsSeg` scratch allocation follows the `int* nResults` out-parameter pattern from the C signature.

### 5.6 Global variable access

`getGlobal` and `setGlobal` implement access to the Luau global table via the constant `LUA_GLOBALSINDEX = -10002` (the pseudo-index Luau uses for the global environment):

```scala
// panama/src/luau/panama/PanamaState.scala:205–215
def getGlobal(state: MemorySegment, name: String): Unit =
  pushString(state, name)
  LxHandles.lx_rawget.invokeExact(L, state, LUA_GLOBALSINDEX): Unit

def setGlobal(state: MemorySegment, name: String): Unit =
  val saved = LxHandles.lx_ref.invokeExact(L, state, -1).asInstanceOf[Int]
  LxHandles.lx_pop.invokeExact(L, state, 1): Unit
  pushString(state, name)
  LxHandles.lx_push_ref.invokeExact(L, state, saved): Unit
  LxHandles.lx_rawset.invokeExact(L, state, LUA_GLOBALSINDEX): Unit
  LxHandles.lx_unref.invokeExact(L, saved): Unit
```

`setGlobal` pins the top-of-stack value with `lx_ref` (preventing it from being GC'd), pops it, pushes the name string and the ref'd value, then does a raw set on the global table. The temporary ref is released at the end. This is safe but has a subtle risk: if the stack is empty when `setGlobal` is called, `lx_ref` will return `LUA_NOREF` (-1), and the subsequent operations will misbehave. The caller is responsible for pushing the value first.

### 5.7 Ref management

```scala
// panama/src/luau/panama/PanamaState.scala:185–195
def ref(state: MemorySegment): Ref[MemorySegment] =
  val key = LxHandles.lx_ref.invokeExact(L, state, -1).asInstanceOf[Int]
  if key == -1 then
    throw new IllegalStateException("lx_ref returned LUA_NOREF (stack empty?)")
  val origin = Ref.genOrigin()
  Ref(L, key, this, origin)

def unref(state: MemorySegment, key: Int): Unit =
  if !closed then
    LxHandles.lx_unref.invokeExact(state, key): Unit
```

Key invariants (from `shim/include/lx.h:282–294`):
- `lx_ref` pins the value at index `idx` **without popping** it from the stack. After `ref()`, the value remains at stack index -1; the caller is responsible for popping if needed.
- The `H` stored in the returned `Ref[MemorySegment]` is `L` (the main state), not `state` (the thread). `unref(state=L, key)` correctly calls `lx_unref(L, ref)`.
- `lx_unref` must be called on the Driver thread that owns the Isolate. Cross-thread unref is unsafe.

### 5.8 Scope integration

```scala
// panama/src/luau/panama/PanamaState.scala:236–239
def scoped[A](block: Scope[MemorySegment] ?=> A): A =
  val scope = Scope[MemorySegment](this, L)
  try block(using scope)
  finally scope.close()
```

`Scope[H]` (defined in `core/jvm/src/luau/core/Scope.scala`) is a `Binding`-agnostic container that collects `Ref[H]` objects and closes them all in LIFO order on exit. `scoped{}` is the idiomatic way to ensure Refs are released deterministically within a delimited region.

---

## 6. `NativeFnDispatcher` — Upcall Stub and Dispatch

**File:** `panama/src/luau/panama/NativeFnDispatcher.scala`

`NativeFnDispatcher` hosts the single JVM upcall stub that the Shim trampoline calls for every Native function invocation.

### 6.1 Registration

```scala
val fns = new ConcurrentHashMap[Int, NativeFn[MemorySegment]]()
private var nextId = 1

def register(fn: NativeFn[MemorySegment]): Int =
  val id = nextId
  nextId += 1
  fns.put(id, fn)
  id
```

`fns` is a `ConcurrentHashMap` — reads and writes from different threads are safe at the map level. However, `nextId` is a plain `var` with no synchronization (`NativeFnDispatcher.scala:13`). Two concurrent `register()` calls could race on `nextId` and produce the same ID. This is not a current problem because the Driver is single-threaded, but the class has no `@NotThreadSafe` annotation to document the assumption.

### 6.2 Upcall stub allocation

```scala
// panama/src/luau/panama/NativeFnDispatcher.scala:59–72
def allocateUpcallStub(arena: Arena): MemorySegment =
  val mh = MethodHandles.lookup().bind(this, "dispatch",
    MethodType.methodType(classOf[Int],
      classOf[MemorySegment], classOf[MemorySegment],
      classOf[Int], classOf[Int], classOf[MemorySegment]))
  Linker.nativeLinker().upcallStub(mh, NativeFnDispatcher.HOST_FN_DESC, arena)
```

`MethodHandles.lookup().bind(this, "dispatch", ...)` creates a bound method handle that closes over `this` dispatcher instance. `Linker.nativeLinker().upcallStub(mh, desc, arena)` allocates native executable memory in `arena` and installs a thunk that adapts the C calling convention to the JVM method call. The native memory is owned by `arena`; in `PanamaState.open()`, the arena passed is `stateArena` (the `Arena.ofShared()` that lives as long as the Isolate).

### 6.3 Dispatch tri-state protocol

```scala
// panama/src/luau/panama/NativeFnDispatcher.scala:26–57
def dispatch(state: MemorySegment, thread: MemorySegment,
             fnId: Int, nArgs: Int, nResults: MemorySegment): Int =
  val fn = fns.get(fnId)
  if fn == null then
    pushErrorMessage(thread, s"unknown fnId: $fnId")
    return LX_FAIL

  val result = try fn(thread, nArgs)
               catch case t: Throwable =>
                 pushErrorMessage(thread, t.getMessage.nn)
                 NativeFnResult.Fail(LuaValue.Nil)

  result match
    case NativeFnResult.Return(n) =>
      nResults.set(ValueLayout.JAVA_INT, 0L, n)
      LX_RETURN

    case NativeFnResult.Fail(_) =>
      LX_FAIL   // error value already pushed by the fn, or by the catch above

    case s @ NativeFnResult.Suspend(_) =>
      val token = ps.suspendRegistry.allocToken(s)
      ps.lastYieldToken = token
      lx_set_suspend_token.invokeExact(state, thread, token): Unit
      LX_SUSPEND
```

The three outcomes map directly to the Shim's tri-state ABI (`shim/include/lx.h:26–28`):

- **`NativeFnResult.Return(n)`** — the Native function pushed `n` result values onto the thread's stack before returning. `dispatch` writes `n` to the `nResults` out-parameter and returns `LX_RETURN`. The Shim's trampoline returns `nResults` to Luau (`shim/src/lx.cpp:62`).
- **`NativeFnResult.Fail`** — the Native function (or the JVM exception catch) has already pushed one error value. `dispatch` returns `LX_FAIL`. The trampoline calls `lua_error(L)` in pure C inside `lua_resume`'s `setjmp` frame (`shim/src/lx.cpp:67`), making the error propagate up the Luau call stack without crossing the JVM frame boundary.
- **`NativeFnResult.Suspend(register)`** — the Native function wants to yield. `dispatch` allocates a token in `SuspendRegistry`, stores it in `PanamaState.lastYieldToken`, calls `lx_set_suspend_token` on the C side, and returns `LX_SUSPEND`. The trampoline calls `lua_yield(L, 0)` (`shim/src/lx.cpp:74`), which unwinds the C stack and causes `lx_resume` to return `LX_RESUME_YIELD`.

### 6.4 `pushErrorMessage` — off-by-one allocation

```scala
// panama/src/luau/panama/NativeFnDispatcher.scala:74–82
private def pushErrorMessage(thread: MemorySegment, msg: String): Unit =
  val a = Arena.ofConfined()
  try
    val bytes = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val seg = a.allocate(bytes.length.toLong, 1L)
    MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.length)
    lx_push_lstring.invokeExact(panamaState.L, thread, seg, bytes.length.toLong): Unit
  finally a.close()
```

The allocation is `bytes.length` bytes with no null-terminator byte. `lx_push_lstring` takes `(ptr, len)` and copies exactly `len` bytes — it does not require null termination and will not over-read. This is safe. However, if any code ever changes the call to use a null-terminated version of the string, the allocation would need `bytes.length + 1` bytes.

---

## 7. `SuspendRegistry` — Pending Suspension Store

**File:** `panama/src/luau/panama/SuspendRegistry.scala`

```scala
final class SuspendRegistry:
  private val seq   = new AtomicLong(1L)
  private val table = new ConcurrentHashMap[Long, NativeFnResult.Suspend]()

  def allocToken(suspend: NativeFnResult.Suspend): Long =
    val tok = seq.getAndIncrement()
    table.put(tok, suspend)
    tok

  def consume(token: Long): Option[NativeFnResult.Suspend] =
    Option(table.remove(token))
```

`allocToken` mints a monotonically increasing token using `AtomicLong.getAndIncrement()` — thread-safe. `consume` atomically removes the entry. The token is the same value written to the Shim via `lx_set_suspend_token` and read back by the Driver via `PanamaState.lastYieldToken` after `lx_resume` returns `LX_RESUME_YIELD`.

The Driver's expected flow after a `Yielded` result:

```
lx_resume() → LX_RESUME_YIELD
  → read ps.lastYieldToken
  → ps.suspendRegistry.consume(token) → Some(Suspend(register))
  → register(resume)  // wire async op, returns Cancel
```

`NativeFnResult.Suspend` wraps a `register: Resume => Cancel` function (defined in `core/jvm/src/luau/core/Async.scala`). `Resume` is an opaque `Either[LuaError, LuaValue] => Unit` callback. When the async op completes, it calls `resume.succeed(value)` or `resume.fail(error)`, which must enqueue a resume on the Run queue (not call `lx_resume` directly).

**Current limitation:** The registry stores one `Suspend` per token, and tokens are per-Isolate, not per-Coroutine. If two Coroutines within the same Isolate both yield via `LX_SUSPEND` before either is resumed, only the last-written `lastYieldToken` survives. The design assumes the Scheduler drives at most one Suspension per Isolate at a time. This invariant is not enforced in the code; it is architectural.

---

## 8. `Marshal` — String and Byte Boundary Crossing

**File:** `panama/src/luau/panama/Marshal.scala`

`Marshal` is a stateless utility object for crossing the Java/C string boundary.

```scala
object Marshal:
  // String → null-terminated C string in arena-allocated segment (UTF-8)
  def toNativeString(s: String, arena: Arena): MemorySegment

  // Sized MemorySegment → byte array (copies from native memory)
  def fromNativeBytes(ptr: MemorySegment, len: Long): Array[Byte]

  // Sized MemorySegment → String (UTF-8 decode of fromNativeBytes)
  def fromNativeString(ptr: MemorySegment, len: Long): String

  // Raw arena allocator (size, align) — thin wrapper
  def scratch(size: Long, align: Long, arena: Arena): MemorySegment
```

`toNativeString` allocates `bytes.length + 1` bytes and appends a null terminator. This is the correct allocation for C strings. `fromNativeString` does not expect null termination — it takes an explicit `len` and copies exactly `len` bytes.

**Copying semantics:** Every string or byte array crossing is a copy. The Luau heap and JVM heap never share memory. Luau's `lx_push_lstring` copies the bytes immediately (`shim/src/lx.cpp:208`); after the call returns, the JVM-side buffer can be freed.

---

## 9. `PanamaRef` and `PanamaScope` — Lower-Level Ref Helpers

**Files:** `panama/src/luau/panama/PanamaRef.scala`, `panama/src/luau/panama/PanamaScope.scala`

These are lower-level internal helpers that exist alongside the public `core.Ref[H]` and `core.Scope[H]` types. Their relationship to the core types is not yet fully resolved (see Section 12, Open Question 3).

### `PanamaRef`

```scala
final class PanamaRef(val luaRef: Int, state: PanamaState) extends AutoCloseable:
  private val released = new AtomicBoolean(false)
  override def close(): Unit =
    if released.compareAndSet(false, true) then state.releaseRef(luaRef)
  def push(thread: MemorySegment): Unit =
    LxHandles.lx_push_ref.invokeExact(state.L, thread, luaRef): Unit
```

`close()` uses `AtomicBoolean.compareAndSet` for idempotency — multiple `close()` calls are safe. `push()` duplicates `Ref[MemorySegment].push()` functionality; the distinction is that `PanamaRef` holds a raw `Int` registry key plus a `PanamaState` reference, while `core.Ref[H]` is backend-agnostic.

### `PanamaScope`

```scala
final class PanamaScope(arena: Arena, state: PanamaState) extends AutoCloseable:
  private val refs = new ArrayBuffer[PanamaRef]()
  def trackRef(ref: PanamaRef): Unit = refs += ref
  def closeRefs(): Unit = refs.foreach { r => if !r.isReleased then r.close() }; refs.clear()
  override def close(): Unit = closeRefs(); arena.close()
  def allocate(size: Long, align: Long): MemorySegment = arena.allocate(size, align)
```

`PanamaScope` pairs an `Arena` with a list of `PanamaRef` objects, closing both on exit. It is distinct from `core.Scope[H]` which does not hold an `Arena`. `PanamaState.scoped{}` uses `core.Scope[MemorySegment]`, not `PanamaScope`.

---

## 10. `PanamaSink` — Codec Push Target

**File:** `panama/src/luau/panama/PanamaSink.scala`

`PanamaSink` implements `core.codec.Sink[MemorySegment]`, connecting the Codec encode-push protocol to the Panama Binding operations:

```scala
final class PanamaSink(ps: PanamaState) extends Sink[MemorySegment]:
  val binding: Binding[MemorySegment] = ps
  val state: MemorySegment = ps.L
```

`PanamaSink` is instantiated with the main state `ps.L` as the push target. All values are pushed onto the main state's stack.

Key behaviors:
- `pushKey` enforces ADR-0006: a `LuaValue.LuaRef` cannot be used as a table key (`PanamaSink.scala:24–25`). Attempting to do so throws `IllegalArgumentException`.
- `pushValue[A: LuauEncoder]` encodes `value` onto the stack, then calls `binding.rawSet(state, -3)` to pop key + value and set `table[-3][key] = value`.
- `pushArrayValue[A: LuauEncoder]` encodes `value` and calls `binding.setArray(state, -2, n)`.

**`setArray` performance issue:** `PanamaState.setArray` (`panama/src/luau/panama/PanamaState.scala:176–177`) delegates to `lx_rawseti` (single-element set), **not** `lx_setarray` (batch). Each `pushArrayValue` call therefore incurs one full `lx_rawseti` downcall. For large arrays, this means N individual FFI calls rather than one batch. `lx_setarray` is bound in `LxHandles` but is never called through the `Binding` interface. The C `lx_setarray` implementation (`shim/src/lx.cpp:282–289`) still does N individual `lua_rawseti` calls internally, so the actual performance benefit of using it would be the savings from avoiding N FFI crossings (which is the real cost).

---

## 11. `LxConstants` Type-Tag Mismatch Bug

The `LuaType` enum in `core/jvm/src/luau/core/LuaType.scala` uses standard Lua 5.x type codes:

```scala
enum LuaType(val luaCode: Int):
  case None     extends LuaType(-1)
  case Nil      extends LuaType(0)
  case Boolean  extends LuaType(1)
  case Number   extends LuaType(3)
  case String   extends LuaType(4)   // ← standard Lua code 4
  case Table    extends LuaType(5)   // ← standard Lua code 5
  case Function extends LuaType(6)
  case Userdata extends LuaType(7)
  case Thread   extends LuaType(8)
```

But Luau uses different codes. The `PanamaState.typeAt` function (`PanamaState.scala:104–116`) handles this with explicit case matches:

```scala
def typeAt(state: MemorySegment, idx: Int): LuaType =
  val code = LxHandles.lx_type.invokeExact(L, state, idx).asInstanceOf[Int]
  code match
    case LX_TNONE     => LuaType.None      // -1
    case LX_TNIL      => LuaType.Nil       //  0
    case LX_TBOOLEAN  => LuaType.Boolean   //  1
    case LX_TNUMBER   => LuaType.Number    //  3
    case LX_TSTRING   => LuaType.String    //  6
    case LX_TTABLE    => LuaType.Table     //  7
    case LX_TFUNCTION => LuaType.Function  //  8
    case LX_TUSERDATA => LuaType.Userdata  //  9
    case LX_TTHREAD   => LuaType.Thread    // 10
    case _            => LuaType.fromCode(code)
```

The named cases are correct. However, the `case _ => LuaType.fromCode(code)` fallthrough is hit for:
- `LX_TINTEGER = 4` → `LuaType.fromCode(4)` finds `LuaType.String` (which has `luaCode = 4`) — **integers are misidentified as `LuaType.String`**.
- `LX_TVECTOR = 5` → `LuaType.fromCode(5)` finds `LuaType.Table` — **vectors are misidentified as `LuaType.Table`**.
- `LX_TBUFFER = 11` → `LuaType.fromCode(11)` throws `IllegalArgumentException("Unknown Luau type code: 11")` — **buffers crash `typeAt`**.

Additionally, `lx_push_integer` (`LxHandles.scala:49`) and `lx_to_integer` (`LxHandles.scala:60–61`) are bound but no `Binding` method exposes them. There is no way to push a native 64-bit integer through the public API, and `typeAt` cannot correctly identify integer-typed stack slots.

---

## 12. `LuauShimBindings` — Dead Code

**File:** `panama/src/generated/LuauShimBindings.scala`

This file in package `luau.panama.generated` is a stale `jextract`-style stub. It binds only four functions — `lx_version`, `lx_newstate`, `lx_close`, and `lx_resume` — and **is not referenced by any production code**. Two bugs make it actively incorrect:

1. `shimOf()` uses `Arena.global()` for the library lookup (`LuauShimBindings.scala:54–58`). `Arena.global()` never closes, leaking the OS library handle.
2. The `lx_resume` descriptor is wrong: it declares only `(ADDRESS, JAVA_INT) → JAVA_INT` — missing the `lx_Thread thread`, `int nArgs`, and `int* nResults` parameters. Calling it would corrupt the C ABI and produce undefined behavior.

The file should be deleted to remove confusion about which binding surface is authoritative (`LxHandles` is authoritative).

---

## 13. Test Coverage Status

All 22 Panama tests are marked `.ignore`. **Zero tests run in CI.**

| Test class | Test count | Coverage |
|---|---|---|
| `CompileAndRunTest` | 5 | compile lifecycle, resume, trivial scripts |
| `NativeFunctionTest` | 3 | Return, Fail, multiple fnIds |
| `SuspendResumeTest` | 3 | Suspend→Yielded, sync resume, error propagation |
| `RefLifecycleTest` | 4 | pin, idempotent close, scoped release, leak |
| `StringMarshalTest` | 5 | ASCII, UTF-8, None, empty, null-termination |
| `NativeLibSmokeTest` | 2 | non-null L, open/close lifecycle |

The tests exist and model the correct behaviors, but require the native shared library (`libluau-shim.so`) to be present at test-run time. The `luau.shim.lib` property or a `java.library.path` entry must point to the compiled library. Building the library requires `shim.nativeBuild` to have run. No Mill task currently automates this dependency chain for `panama.test`.

---

## 14. Architecture Diagram

```mermaid
graph TD
    subgraph JVM Process
        subgraph PanamaState
            L["L: MemorySegment (lx_State*)"]
            sa["stateArena: Arena.ofShared"]
            sr["SuspendRegistry"]
            lyt["lastYieldToken: Long @volatile"]
        end
        subgraph NativeFnDispatcher
            stub["upcall stub (native mem in stateArena)"]
            fns["fns: ConcurrentHashMap[Int, NativeFn]"]
            dispatch["dispatch() — HOST_FN_DESC"]
        end
        LxHandles["LxHandles (39 downcall MHs)"]
        Marshal["Marshal (string copy)"]
        PanamaSink["PanamaSink → Binding ops"]
        CoreRef["core.Ref[MemorySegment]"]
        CoreScope["core.Scope[MemorySegment]"]
    end

    subgraph Native (libluau-shim.so)
        Shim["Shim (lx.cpp)"]
        Luau["Luau VM (upstream C++)"]
    end

    LxHandles -->|"downcall MH → lx_* calls"| Shim
    stub -->|"upcall: C → JVM"| dispatch
    Shim -->|"lx_trampoline calls lx_HostFn"| stub
    Shim --> Luau
    PanamaState --> LxHandles
    PanamaState --> NativeFnDispatcher
    PanamaSink --> PanamaState
    CoreScope -->|"captureTop() → ref()"| PanamaState
    CoreRef -->|"close() → unref()"| PanamaState
```

---

## 15. Known Bugs and Risks

### Bug 1 — `LuaType` mismatch for integer, vector, buffer

**Severity: High (data corruption)**

`typeAt()` falls through to `LuaType.fromCode(code)` for `LX_TINTEGER=4`, `LX_TVECTOR=5`, and `LX_TBUFFER=11`. This misidentifies Luau integers as `LuaType.String`, vectors as `LuaType.Table`, and throws for buffers. Any code path that inspects the type of a stack slot containing an integer, vector, or buffer will get wrong results.

**Files:** `panama/src/luau/panama/PanamaState.scala:104–116`, `core/jvm/src/luau/core/LuaType.scala:3–18`, `panama/src/luau/panama/LxConstants.scala:13–24`.

**Fix options:** Add `LuaType` cases for `Integer`, `Vector`, `Buffer` with the correct Luau codes; or add explicit `case LX_TINTEGER => LuaType.Number` (since integers are a numeric subtype in Luau) and explicit cases for Vector and Buffer.

### Bug 2 — `lx_push_integer` / `lx_to_integer` not surfaced

`lx_push_integer` (`LxHandles.scala:49`) and `lx_to_integer` (`LxHandles.scala:60–61`) are bound but no `Binding[H]` method exposes them. Native functions cannot push 64-bit integers or read integer-typed slots through the standard interface.

### Bug 3 — `nextId` in `NativeFnDispatcher` is unsynchronized

`nextId` (`NativeFnDispatcher.scala:13`) is a plain `var`. Two concurrent `register()` calls could produce the same ID, causing one Native function to overwrite another in the `fns` map. The single-Driver design prevents this in practice, but the lack of annotation or enforcement is a maintenance risk.

### Bug 4 — `LuauShimBindings` has wrong `lx_resume` descriptor

`LuauShimBindings.lx_resume` only declares two `ADDRESS` parameters (`state`, `thread`) and is missing `nArgs: JAVA_INT` and `nResults: ADDRESS`. Calling it would corrupt the C ABI. The file is dead code but should be deleted to prevent accidental use.

**File:** `panama/src/generated/LuauShimBindings.scala:43–50`

### Bug 5 — `lx_set_global` / `lx_get_global` not bound

These two functions are defined in `shim/src/lx.cpp:332–340` and exported by the Mill `wasmBuild` task (`build.mill:143`), but are **not in `lx.h`** and are **not bound in `LxHandles`**. The Panama backend cannot call them. For the Panama backend, `getGlobal`/`setGlobal` are implemented via `lx_rawget`/`lx_rawset` on `LUA_GLOBALSINDEX`, which achieves the same effect.

### Bug 6 — `lx_register_native` called with thread handle instead of main state (P0-C)

**Severity: P0 — blocks every test that registers a Native function**

Both `pushFunction` (`PanamaState.scala:95–99`) and `registerNativeFn` (`PanamaState.scala:197–203`) invoke `LxHandles.lx_register_native` with `state` (the per-coroutine thread handle) as the first argument. The `lx_register_native` C function requires the main `lx_State*` (`L`) as its first argument; it looks up the global function table on the main state. Passing a coroutine thread handle instead causes the native function to be registered on the wrong table (or to corrupt memory if the handle is not a valid `lx_State*`). No native function registration can succeed until this is fixed.

**Files:** `panama/src/luau/panama/PanamaState.scala:98`, `panama/src/luau/panama/PanamaState.scala:202`

**Fix:** Replace `state` with `L` in both `lx_register_native.invokeExact(state, ...)` call sites:

```scala
// pushFunction (line 98)
LxHandles.lx_register_native.invokeExact(L, fnId, name): Unit

// registerNativeFn (line 202)
LxHandles.lx_register_native.invokeExact(L, fnId, name): Unit
```

### Risk — `setArray` uses single-element `lx_rawseti`, not batch `lx_setarray`

`PanamaState.setArray` calls `lx_rawseti` per element (`PanamaState.scala:176–177`). The batch `lx_setarray` is bound but never called through `Binding`. For encoding large arrays, this means N FFI crossings instead of one.

### Risk — Single suspend token per Isolate

`LxStateData.suspendToken` is one `int64_t` on the main thread's data block (`shim/src/lx.cpp:17`). If two Coroutines within one Isolate both hit `LX_SUSPEND` before either is resumed, the second token silently overwrites the first. The Scheduler must enforce single-active-suspension per Isolate.

### Risk — `lx_to_lstring` with `dstlen == 0`

In the Shim (`shim/src/lx.cpp:246`): `size_t copy = (slen < dstlen - 1) ? slen : dstlen - 1`. If `dstlen == 0`, the `dstlen - 1` underflows to `SIZE_MAX` (unsigned arithmetic), and `memcpy` attempts to copy up to `min(slen, SIZE_MAX)` bytes into a zero-length buffer — heap corruption. The Panama backend always allocates `rawLen + 1` bytes as the buffer (`PanamaState.scala:139`), so `dstlen` is always at least 1 when `rawLen > 0`. The zero-length path is guarded by the `rawLen == 0` check at line 134. This is safe today but fragile.

---

## 16. Incomplete / Divergent from WASM Backend

The following features are wired in the WASM backend or the Shim but absent or incomplete in the Panama backend:

| Feature | WASM backend | Panama backend | Notes |
|---|---|---|---|
| `lx_push_integer` exposed through `Binding` | Yes (as `pushInteger`) | No (bound but not exposed) | Panama `LxHandles` has the handle |
| `lx_to_integer` exposed through `Binding` | Yes | No | Same |
| `LuaType.Integer` / `Vector` / `Buffer` | Luau type codes correct | Bug: misidentifies via fallthrough | See Section 11 |
| Native library CI integration | WASM built by `shim.wasmBuildNative`, tests run | Library must be manually placed; all tests `.ignore` | Zero automated coverage |
| Scheduler / Run queue | Not wired (module on disk, not in `build.mill`) | Not wired | `scheduler/jvm` exists but not in Mill build |
| Standard library / stdlib | Not wired | Not wired | `stdlib/jvm` exists but not in Mill build |
| `lx_set_global` / `lx_get_global` binding | Direct binding via Scala.js extern | Emulated via `rawget`/`rawset` on `LUA_GLOBALSINDEX` | Functionally equivalent |

---

## 17. Open Questions for the JVM Roadmap

1. **`LuaType` extension**: Should `LuaType` be extended with `Integer`, `Vector`, `Buffer` cases matching Luau's actual type codes? Or should `typeAt` map `LX_TINTEGER` to `LuaType.Number` (treating integer as a numeric subtype) and add explicit `Vector`/`Buffer` cases? The current fallthrough is a latent data corruption bug that must be resolved before any code calls `typeAt` on a Luau integer.

2. **`pushInteger` / `toInteger` in `Binding`**: Should `Binding[H]` add `pushInteger(state: H, value: Long): Unit` and `toInteger(state: H, idx: Int): Option[Long]`? Both handles are already bound. This is required to correctly handle `int64_t` values from Luau scripts.

3. **`PanamaRef` / `PanamaScope` vs `core.Ref` / `core.Scope`**: Are `PanamaRef` and `PanamaScope` intentionally separate lower-level helpers, or should they be unified with the core types? `PanamaRef.push()` duplicates `Ref[MemorySegment].push()`.

4. **Suspend token visibility**: `lastYieldToken` is `@volatile`. The write happens in `dispatch()` (called from the C upcall, which is called inside `lx_resume`), and the read happens on the Driver thread immediately after `lx_resume` returns. Since both are on the same thread (single Driver), the volatile write is sufficient. If the Driver model ever allows cross-thread resume-handoff, an `AtomicLong` would be needed.

5. **Upcall stub arena choice**: The upcall stub is allocated in `Arena.ofShared()`. Panama's documentation states that upcall stubs allocated with a confined Arena are only callable from the owning thread. The `ofShared` choice removes that restriction. Is there any scenario where the Shim could call the upcall from a C thread other than the Driver? Current single-Driver design says no, but the rationale for `ofShared` should be stated in a code comment.

6. **`LuauShimBindings` deletion**: This file (`panama/src/generated/LuauShimBindings.scala`) is dead code with incorrect signatures and a leaking `Arena.global()`. It should be deleted. The only reason to keep it would be as a jextract regeneration target, but the actual binding surface (`LxHandles`) is hand-written and more complete.

7. **Unblocking the test suite**: All 22 tests are `.ignore`. The blocker is native library availability at test time. The Mill build has `shim.nativeBuild` which produces the library; adding a Mill task dependency from `panama.test` to `shim.nativeBuild` and passing the output path as `luau.shim.lib` would unblock the entire test suite. This is the highest-priority JVM roadmap item.

8. **Scheduler and stdlib integration**: `scheduler/jvm` and `stdlib/jvm` directories exist on disk but are not wired into `build.mill`. The JVM backend cannot schedule Tasks or use the standard Roblox-compatible libraries until these modules are connected.
