# Garbage Collection Strategy for a Lua/Luau Runtime on the JVM

## Overview

Lua and Luau maintain their own incremental tri-color mark-sweep GC over a custom C heap. Implementing a Lua/Luau interpreter on the JVM (in Scala 3, targeting JDK 21+) raises a fundamental choice: **port the custom Lua GC** or **delegate entirely to the JVM heap**. This document analyzes both paths in depth, surveys how existing host-GC runtimes (LuaJ, gopher-lua, MoonSharp) handle the mismatch, and provides concrete guidance for the `luau-scala` implementation.

---

## 1. Lua/Luau GC: Algorithm and Semantics

### 1.1 Incremental Tri-Color Mark-Sweep

Lua 5.4 (and Luau) use a **tri-color mark-sweep** collector interleaved with program execution. Each GC object carries a color encoded in a bitfield (`GCheader::marked`):

| Color  | Bits set    | Meaning                                    |
|--------|-------------|--------------------------------------------|
| White  | `white0` or `white1` | Unreachable candidate; two alternating whites distinguish live-cycle whites from dead objects |
| Gray   | no color bits | Reachable; children not yet scanned        |
| Black  | `black`     | Reachable; all children scanned            |

The **tri-color invariant**: a black object may never point to a white object. Write barriers maintain this during mutation:

- **Forward barrier** (`luaC_barriert`): when a field is written, the new child is marked non-white (promoted to gray or black).
- **Backward barrier**: the parent is re-grayed and queued in a `grayagain` list for rescan.

A full GC cycle has three phases:

1. **Mark** — incremental; traverses the gray queue, blackens objects.
2. **Atomic** — single stop-the-world step that finalizes marking, processes weak tables, and builds the finalizer list.
3. **Sweep** — incremental; walks all heap objects, reclaims whites, advances the "current white" generation bit.

**Luau** adds a **paged sweeper**: GC-managed objects of equal size share 16 KB pages; the sweep traverses page-at-a-time rather than per-object, reducing sweep overhead. Luau also supports both incremental and generational modes (since Lua 5.4), tunable via `collectgarbage`.

Sources: [Lua 5.4 Reference Manual §2.5](https://www.lua.org/manual/5.4/manual.html), [Luau Memory Management & GC (DeepWiki)](https://deepwiki.com/luau-lang/luau/5.3-memory-management-and-garbage-collection), [Lua GC Notes (poga)](https://poga.github.io/lua53-notes/gc.html).

### 1.2 Weak Tables and Ephemerons

Weak references are declared via the `__mode` metafield on a table's metatable:

| `__mode` value | Keys   | Values  |
|----------------|--------|---------|
| `"k"`          | weak   | strong  |
| `"v"`          | strong | weak    |
| `"kv"`         | weak   | weak    |

The GC **clears weak references during the atomic phase**, after marking, before sweep. An entry whose weakly-held key or value is white (unreachable) is removed from the table.

**Ephemeron tables** (`__mode = "k"` with strong values) impose a stricter rule: the *value* is not considered reachable unless the *key* is independently reachable (not only through the value itself). This breaks cycles of the form `key → value → key` that would otherwise prevent collection. The Lua GC handles ephemerons by iterating to a fixpoint during the atomic phase: after marking, any gray ephemerons are rescanned until no new objects become reachable through newly-live keys.

Luau additionally supports `"s"` in `__mode` to mark a table as *shrinkable*: GC resizes weak tables to optimal capacity after clearing dead entries. Iteration during GC with `"s"` can miss keys (documented caveat).

Sources: [Lua 5.4 Reference Manual §2.5.2](https://www.lua.org/manual/5.4/manual.html), [Wikipedia: Ephemeron](https://en.wikipedia.org/wiki/Ephemeron), [Lua GC Docs (cyevgeniy)](https://cyevgeniy.github.io/luadocs/02_basic_concepts/ch05.html).

### 1.3 `__gc` Finalizer Semantics

Lua's `__gc` metamethod runs when the GC determines an object is dead. The complete lifecycle:

1. An object's metatable is set with a `__gc` field before the object becomes unreachable.
2. During the atomic phase, dead objects with `__gc` are moved to a **finalizer list** rather than immediately swept.
3. Finalizers are called **in reverse order of marking for finalization** (LIFO within a single collection cycle).
4. During finalization the object is **resurrected** — temporarily made reachable again so the finalizer can access it. If the finalizer stores the object somewhere globally, resurrection is permanent and the object survives. Otherwise it is re-collected in the next cycle.
5. An object can be re-registered for finalization (e.g., by setting `__gc` again inside the finalizer), causing it to be finalized again in a subsequent cycle.
6. `collectgarbage("collect")` may need to run **twice** to fully reclaim an object that was resurrected once.
7. Errors inside `__gc` generate a warning; they are not propagated.
8. **Finalizers cannot yield** and must not call `collectgarbage`.

**Key ordering guarantee**: within one GC cycle, finalizers fire in reverse-marking order. Across cycles, ordering between unrelated objects is not guaranteed.

**Luau diverges significantly**: Luau does **not** support `__gc` on tables or userdata at the Lua level. Instead it exposes **tag-based destructors** accessible only to the C/C++ host, which fire synchronously immediately before the memory block is freed. This is by design — Luau's sandbox model forbids user-level finalizers because they can cause memory-safety violations (access to already-freed objects from weak tables) and break isolation between sandboxed scripts.

Sources: [Lua 5.4 Reference Manual §2.5.3](https://www.lua.org/manual/5.4/manual.html), [Luau Sandbox docs](https://luau.org/sandbox/), [Lua GC Docs (cyevgeniy)](https://cyevgeniy.github.io/luadocs/02_basic_concepts/ch05.html).

---

## 2. JVM Heap vs Custom Lua GC: Trade-offs

### 2.1 Using the JVM Heap Directly

**Strategy**: represent every Lua value as a plain JVM object (or a sealed-trait ADT in Scala 3). No custom allocator or collector. The JVM GC (G1, ZGC, Shenandoah) handles all reclamation.

**Pros**:

- **Zero allocator code**: no bump-pointer arena, no free-list, no sweep logic. Enormous reduction in implementation complexity.
- **JVM GC is state-of-the-art**: G1 and ZGC offer concurrent, low-pause, generational collection tuned over decades. No Lua runtime can match ZGC's <1 ms pause guarantees at scale.
- **Thread safety for free**: Lua's incremental GC requires write barriers to maintain the tri-color invariant; on the JVM, barrier logic is embedded in the GC itself.
- **Interop is trivial**: Lua objects and Java objects share the same heap; no marshalling or lifetime tracking at the boundary.
- **Memory pressure signals**: `SoftReference` allows the JVM to evict caches under memory pressure without explicit tuning.
- **Virtual threads (JDK 21)**: Lua coroutines map naturally to `java.lang.Thread` (virtual or platform), with stack frames managed by the JVM.
- **Debugging and profiling**: standard JVM tools (JFR, async-profiler, VisualVM) work on Lua objects without custom GC instrumentation.

**Cons**:

- **Loss of `collectgarbage` semantics**: Lua programs can call `collectgarbage("count")` to measure Lua heap usage, `collectgarbage("step", n)` to drive incremental collection, and `collectgarbage("stop")`/`"restart"` for pause control. These do not map to JVM GC APIs. `System.gc()` is advisory; `Runtime.totalMemory() - freeMemory()` counts the entire JVM heap, not just Lua objects.
- **`__gc` ordering non-determinism**: JVM finalizers/cleaners have no ordering guarantees across objects. Lua's LIFO-within-cycle guarantee cannot be replicated without an explicit finalizer queue.
- **Resurrection is impossible**: Lua `__gc` can store the object into a global, making it permanently reachable. JVM PhantomReference referents have already been cleared by the time the cleanup action runs; there is no resurrection mechanism.
- **Weak table clearing order**: Lua guarantees that weak tables are cleared *before* finalizers fire in the same cycle. JVM reference processing clears `WeakReference`s, then enqueues `FinalReference` objects (for `Object.finalize()`), then clears `PhantomReference`s — but this ordering applies to the reference *type level*, not to individual object relationships across tables and finalizers.
- **Ephemeron fixpoint**: `WeakHashMap` in Java has weak-key/strong-value semantics identical to Lua ephemerons conceptually, but the JVM GC does not run an ephemeron fixpoint. If a value's reachability would be broken by clearing its key, the JVM's WeakHashMap clears the key entry — but if the value itself holds a strong reference back to the key, the entry is never cleared (the key is strongly reachable through the value). Lua's ephemeron algorithm resolves this correctly; JVM `WeakHashMap` does not.
- **`collectgarbage` control surface lost**: incremental GC tuning (pause, step multiplier) cannot be exposed to Lua scripts.
- **GC pressure from object proliferation**: if every Lua integer/float is boxed as a JVM object, GC pressure increases dramatically. Sealed-trait representations with `Long`/`Double` specialization (or Scala 3 opaque types) are critical.

### 2.2 Porting a Custom Lua GC to the JVM

**Strategy**: allocate Lua objects in a `java.nio.ByteBuffer` (off-heap) or a manually-managed `sun.misc.Unsafe` region, implement tri-color mark-sweep in Scala.

**Pros**:

- Full semantic fidelity: `collectgarbage` APIs, `__gc` LIFO ordering, resurrection, ephemeron fixpoint all implementable.
- Precise Lua heap accounting for sandboxed memory limits (relevant for Luau's `lua_setmemorylimit` equivalent).

**Cons**:

- **Extreme implementation cost**: the Lua GC in `lgc.c` is ~1500 lines of carefully tuned C with subtle invariants. A correct Scala port is months of work.
- **Dual-lifetime management**: JVM objects (strings, closures, userdata) that wrap Lua objects still live on the JVM heap; the custom GC must coordinate with the JVM GC to avoid dangling pointers. This requires finalizers or `Cleaner` registrations on JVM wrapper objects.
- **No JVM tooling**: custom heap is opaque to JFR, heap dumps, profilers.
- **GIL equivalent needed**: incremental GC requires write barriers on every table write and upvalue mutation — all hot paths — plus synchronization for coroutines.
- **Off-heap memory leaks**: `ByteBuffer.allocateDirect` regions are finalized non-deterministically; `Unsafe` regions leak unless explicitly freed.
- **JVM value objects (JEP 401, preview)**: not yet stable in JDK 21; cannot rely on them.

**Verdict**: porting the custom GC is not justified for a hosted JVM runtime. The semantic gaps (primarily `__gc` ordering and `collectgarbage` control) can be addressed with documented caveats rather than a full custom GC.

---

## 3. Existing Host-GC Lua Runtimes

### 3.1 LuaJ (Java)

Repository: [github.com/luaj/luaj](https://github.com/luaj/luaj)

LuaJ delegates entirely to the JVM GC. Implementation highlights:

- **WeakTable** (`org.luaj.vm2.WeakTable`): subclass of `LuaTable` that wraps non-primitive `LuaValue` instances in `java.lang.ref.WeakReference`. Three slot types — `WeakKeySlot`, `WeakValueSlot`, `WeakKeyAndValueSlot` — wrap keys and/or values depending on `__mode`. Primitive-like values (numbers, booleans, strings) are never weakened.
- **No `ReferenceQueue`**: dead entries are not eagerly cleaned. Cleanup is **lazy**: during table operations (`set`, `get`, `next`), slots check `WeakReference.get() == null` and skip/remove dead entries. This means stale slots persist until the table is next accessed.
- **`__gc` not supported**: as documented in [issue #55](https://github.com/luaj/luaj/issues/55), `__gc` metamethods are silently ignored. The issue author's recommendation: "You better not rely on the garbage collector in any way."
- **Coroutines**: LuaJ uses `OrphanedThread` error to signal that a coroutine's LuaThread object has been GC'd. This maps Lua coroutine lifetime to JVM thread reachability.
- **`collectgarbage`**: parameters differ; behavior is JVM-dependent. No incremental step control.

**Key lesson**: weak tables work approximately, `__gc` is absent, and GC semantics are best-effort.

### 3.2 gopher-lua (Go)

Repository: [github.com/yuin/gopher-lua](https://github.com/yuin/gopher-lua)

Go's GC (concurrent tri-color mark-sweep) manages all Lua objects. `collectgarbage` with no arguments calls `runtime.GC()` for the entire Go program. **Weak tables are not supported** — Go lacked weak reference primitives until `go 1.24` added `weak.Pointer`, and even then implementing Lua weak-table semantics requires substantial plumbing. `__gc` is also unsupported. The [go-lua (Shopify)](https://shopify.engineering/announcing-go-lua) implementation explicitly states weak tables will never be supported because the Go heap is used for Lua objects and weak reference support was absent.

### 3.3 MoonSharp (.NET/Mono)

Website: [moonsharp.org](https://www.moonsharp.org/), repo: [github.com/moonsharp-devs/moonsharp](https://github.com/moonsharp-devs/moonsharp)

MoonSharp targets Lua 5.2 compatibility on the CLR. The [differences page](https://www.moonsharp.org/moonluadifferences.html) documents:

- **Weak tables: not supported**. The "unlikely to ever be supported" list is short and includes weak tables at the top.
- **`__gc`: will never be implemented**. Rationale: adding a finalizer to every table object just to support `__gc` tracking is "extremely expensive." The CLR GC has non-deterministic finalization and no ordering guarantees, making correct `__gc` semantics impossible without an explicit finalizer queue with high overhead.
- **GC is different**: MoonSharp relies entirely on .NET/Mono standard GC.

MoonSharp's pragmatic position: document the gap and move on. This is the right call for a hosted runtime.

**Summary across runtimes**:

| Feature            | LuaJ      | gopher-lua | MoonSharp |
|--------------------|-----------|------------|-----------|
| `__mode "k"/"v"`   | Yes (lazy cleanup, no ReferenceQueue) | No         | No        |
| Ephemeron fixpoint | No        | No         | No        |
| `__gc` metamethod  | No        | No         | No        |
| `collectgarbage` control | Partial | Minimal | Partial  |
| Host GC delegation | JVM       | Go GC      | CLR GC    |

---

## 4. Weak Tables: JVM Implementation

### 4.1 `__mode "v"` — Weak Values

Use a plain `HashMap[LuaValue, WeakReference[LuaValue]]` with a `ReferenceQueue`. On each table write, poll the queue and remove dead entries.

```scala
import java.lang.ref.{WeakReference, ReferenceQueue}
import scala.collection.mutable

class WeakValueTable:
  private val queue = new ReferenceQueue[LuaValue]()
  // Map from identity key to (key, WeakRef(value))
  private val map = mutable.HashMap.empty[LuaValue, WeakReference[LuaValue]]

  def expunge(): Unit =
    var ref = queue.poll()
    while ref != null do
      // Need reverse map or identity-keyed wrapper to find the key
      ref = queue.poll()

  def set(k: LuaValue, v: LuaValue): Unit =
    expunge()
    map(k) = new WeakReference(v, queue)

  def get(k: LuaValue): LuaValue | Null =
    expunge()
    map.get(k).flatMap(r => Option(r.get())).getOrElse(null)
```

In practice, store the key alongside the `WeakReference` (in a wrapper class) so the `ReferenceQueue` callback can look it up.

### 4.2 `__mode "k"` — Weak Keys (Ephemeron Semantics)

`java.util.WeakHashMap` provides exactly weak-key/strong-value semantics, making it the natural primitive. The JVM's `WeakHashMap` clears entries when keys become weakly reachable, which matches Lua's `__mode "k"` behavior for the common case.

```scala
import java.util.WeakHashMap
import scala.jdk.CollectionConverters.*

class WeakKeyTable:
  private val map = new WeakHashMap[LuaValue, LuaValue]()

  def set(k: LuaValue, v: LuaValue): Unit = map.put(k, v)
  def get(k: LuaValue): LuaValue | Null = map.get(k)
  def next(k: LuaValue | Null): (LuaValue, LuaValue) | Null = ???
```

**Critical caveat — not true ephemerons**: `WeakHashMap` does not implement the Lua ephemeron fixpoint. If a value strongly references its own key (directly or transitively), the entry will **never** be cleared because the key is reachable through the value — the JVM `WeakHashMap` sees a strongly-reachable key. Lua's `__mode "k"` with ephemeron semantics would clear such an entry. This is a semantic gap that cannot be closed without a custom fixpoint algorithm.

The gap matters for: memoization caches where a cached closure captures the key object; object-property registries where properties reference back to the owner. In practice, most `__mode "k"` use cases are not cyclic, so `WeakHashMap` is a pragmatically acceptable approximation.

### 4.3 `__mode "kv"` — Weak Keys and Weak Values

Combine: use a `WeakHashMap[LuaValue, WeakReference[LuaValue]]` with a `ReferenceQueue` for the values. Poll the queue on every access to expunge stale entries. Alternatively, maintain a `WeakReference`-wrapped key in an identity-keyed map.

### 4.4 Lazy vs Eager Expungement

LuaJ uses lazy expungement (check on access). `ReferenceQueue`-based eager expungement is preferable for long-lived tables with high churn: background GC enqueues references, and a table `next()` / iteration can trigger a sweep. A dedicated expunge step at the start of `rawset`/`next` is sufficient.

### 4.5 Iteration Hazards

`WeakHashMap.entrySet()` iterator is not stable — entries disappear between iterations if GC runs concurrently. Lua's `next()` guarantees that all entries present at the start of a `pairs()` traversal are visited exactly once, which cannot be guaranteed over a `WeakHashMap`. Mitigation: snapshot the entry set into a strong `Array[(LuaValue, LuaValue)]` at the start of `pairs()` (losing the Lua GC's streaming-iteration property), or document the deviation.

---

## 5. `__gc` Finalizers: JVM Implementation

### 5.1 JVM Reference Types Recap

| Type              | Cleared when                     | ReferenceQueue enqueued when |
|-------------------|----------------------------------|------------------------------|
| `SoftReference`   | Memory pressure before OOM       | After clearing               |
| `WeakReference`   | Object weakly reachable (before finalization) | After clearing  |
| `PhantomReference`| After finalization and before memory free | After object unreachable and finalized |
| `Cleaner` (JDK 9+)| Phantom reachable                | Handled internally by Cleaner daemon |

JVM processing order within a GC cycle: SoftRef → WeakRef → FinalReference (for `Object.finalize()`) → remark reachable from finalizees → PhantomRef.

`Object.finalize()` is deprecated since JDK 9 ([JEP 421](https://openjdk.org/jeps/421), targeted for removal). Do not use.

### 5.2 `java.lang.ref.Cleaner` (JDK 9+)

`Cleaner` is the recommended JDK 9+ replacement for finalizers. It uses `PhantomReference` + `ReferenceQueue` internally, driven by a dedicated daemon thread.

```scala
import java.lang.ref.Cleaner

object LuaGC:
  // One Cleaner per VM; share across all registered objects
  val cleaner: Cleaner = Cleaner.create()

// Cleanup state must NOT hold a reference to the LuaTable being registered
class FinalizerState(val gcCallback: LuaValue => Unit, val tableRef: WeakRef[LuaTable]):
  def run(): Unit = gcCallback(/* reconstruct minimal LuaValue */)

class LuaTable(val meta: LuaTable | Null):
  private var cleanable: Cleaner.Cleanable | Null = null

  def registerFinalizer(gcMeta: LuaValue): Unit =
    // Extract __gc function from metatable before registering
    // State class must be static/top-level to avoid capturing `this`
    val state = new FinalizerState(...)
    cleanable = LuaGC.cleaner.register(this, state)
```

**Critical constraint**: the `Runnable` cleanup action passed to `Cleaner.register` must **not** reference the registered object (directly or via closure). If it does, the object never becomes phantom-reachable and the cleanup never runs. Use a static nested class or top-level class to hold cleanup state.

### 5.3 Semantic Gaps: Lua `__gc` vs JVM `Cleaner`

| Semantic Property            | Lua `__gc`                                      | JVM `Cleaner`                                      |
|------------------------------|-------------------------------------------------|----------------------------------------------------|
| **Ordering within cycle**    | Reverse of marking order (LIFO)                 | No ordering guarantee; multiple cleanups run concurrently |
| **Ordering across objects**  | Deterministic within a cycle                    | Non-deterministic across objects                   |
| **Resurrection**             | Finalizer can store object globally; object survives | No resurrection; referent already cleared by time Runnable runs |
| **Re-registration**          | `__gc` can be set inside finalizer for re-finalization next cycle | `Cleaner.register` can be called again from within cleanup action (manual re-registration only) |
| **GC cycle coupling**        | Finalizers run in the same GC cycle that collects the object (deferred one cycle) | Cleanup runs asynchronously after GC, in daemon thread, timing indeterminate |
| **Error handling**           | Errors produce a warning; not propagated        | Exceptions are ignored by Cleaner daemon            |
| **Yield inside finalizer**   | Forbidden                                       | N/A (daemon thread, cannot yield Lua coroutines)    |
| **Weak table interaction**   | Weak tables cleared before `__gc` fires         | WeakRef entries cleared before Cleaner fires (same GC cycle ordering) |
| **Memory freed**             | After second collection (post-resurrection)     | Memory freed in GC cycle that detects phantom-reachability |

### 5.4 Explicit Finalizer Queue (Alternative)

To approximate Lua's LIFO ordering, maintain an explicit queue:

```scala
class LuaFinalizerQueue:
  // Registration order = append; finalization order = reverse
  private val pending = mutable.ArrayDeque.empty[(LuaValue, LuaValue)] // (object, __gc fn)
  private val registry = new java.util.WeakHashMap[LuaValue, FinalizerRecord]()

  // Called by Cleaner when object dies
  def enqueue(record: FinalizerRecord): Unit =
    synchronized { pending.prepend((record.snapshotValue, record.gcFn)) }

  def runPending(vm: LuaVM): Unit =
    // Drain queue, calling __gc in enqueue order (LIFO approximation)
    while pending.nonEmpty do
      val (obj, gcFn) = synchronized { pending.removeHead() }
      vm.pcall(gcFn, obj)
```

This approximates Lua's LIFO-within-cycle ordering but cannot guarantee it because JVM GC cycles do not map 1:1 to Lua GC cycles, and objects may be collected in different JVM GC generations at different times.

### 5.5 Resurrection: Unimplementable on the JVM

Lua's resurrection requires that during finalization, the object is still accessible as a live `LuaValue`. On the JVM, by the time the `Cleaner` daemon thread runs the cleanup `Runnable`, the object's `PhantomReference` referent has been cleared — the object memory may still exist but is no longer accessible through any Java reference. There is no mechanism to "un-clear" a phantom reference.

Consequence for `luau-scala`:

1. `__gc` finalizers **cannot** save objects from collection.
2. Code that stores `self` inside `__gc` to resurrect objects will silently fail.
3. Document this as a known deviation. Luau itself has removed `__gc` entirely, so this gap only affects Lua 5.x compatibility.

---

## 6. Luau Sandboxing and Readonly Tables: GC Impact

### 6.1 `table.freeze` Semantics

`table.freeze(t)` marks a table as read-only at the VM level ([RFC](https://rfcs.luau.org/function-table-freeze.html)). All `rawset`, field assignment, and `setmetatable` calls on a frozen table raise an error. The frozen flag is shallow — nested tables are not frozen. `table.freeze` is irreversible.

Default Luau globals (`string`, `math`, `table`, string metatable, the global table itself) are all frozen during VM initialization.

**GC implications of frozen tables**:

- Frozen tables are effectively **immutable after construction**. No write barriers are needed for frozen tables after the initial setup phase because no new references can be written into them.
- In a JVM implementation, frozen tables can be `final` (no `var` fields), allowing the JIT to constant-fold lookups and eliminating write-barrier overhead entirely.
- Frozen tables that contain only other frozen tables form a **closed immutable graph** that the GC can traverse once and then treat as roots (or ignore entirely if they contain no weak references). This is a potential optimization: tag frozen tables as permanently black in any GC traversal.
- **Impact on weak tables**: a frozen table cannot have its `__mode` changed after freezing, so weak-table status is fixed at creation. This simplifies the "mode change triggers rehash" path in LuaJ.

### 6.2 Luau Sandbox Memory Model

Each sandboxed script gets its own environment table (a per-script globals proxy using `__index` to access builtin globals). The global table and standard library tables are shared across scripts but frozen, so they are common GC roots — traversed once, not per-script.

Luau's sandbox does not expose `__gc` to Lua scripts. Tag-based destructors are C-host-only and fire synchronously before memory deallocation, which is not emulable on the JVM (JVM objects are not freed synchronously). For `luau-scala`, implement tag-based destructors as a `CleanupHook` interface registered in the `LuaUserdata` object, invoked from a `Cleaner`-backed mechanism, with the documented caveat that invocation is asynchronous.

### 6.3 No `__gc` in Luau: Simplification

Since Luau removes `__gc` from the Lua surface, the `luau-scala` implementation targeting Luau semantics does **not** need to implement `__gc` at all. The `Cleaner`-based finalizer machinery is only needed for:

1. Userdata objects wrapping non-heap JVM resources (file handles, sockets, native pointers via JNA/Panama).
2. Compatibility with Lua 5.x scripts if the runtime chooses to support them.

For a pure Luau runtime, skip the finalizer queue entirely and implement `AutoCloseable`-based resource cleanup instead.

---

## 7. GC Ordering Gaps: Summary Table

| Lua/Luau Semantic                                 | JVM Equivalent                          | Gap Severity |
|---------------------------------------------------|-----------------------------------------|--------------|
| Weak keys cleared before `__gc` fires             | WeakRef cleared before PhantomRef → acceptable approximation | Low |
| LIFO `__gc` ordering within a cycle               | No ordering across Cleaner actions      | High |
| Resurrection: `__gc` stores `self`                | Impossible with PhantomReference        | Critical (but N/A for Luau) |
| Ephemeron fixpoint for cyclic key→value→key       | `WeakHashMap` does not solve the cycle  | Medium (uncommon in practice) |
| `collectgarbage("count")` precise Lua heap usage  | `Runtime.freeMemory()` counts JVM heap  | Medium |
| `collectgarbage("step", n)` incremental control   | `System.gc()` advisory only             | Medium |
| `collectgarbage("stop")`/`"restart"`              | No JVM equivalent                       | Medium |
| Object finalized exactly once unless re-registered| `Cleaner.Cleanable.clean()` runs at most once | Low (same behavior) |

---

## 8. Recommendation for `luau-scala` (Scala 3, JDK 21+)

### 8.1 Architecture Decision

**Use the JVM heap directly. Do not port a custom GC.**

Rationale:

1. Luau itself has eliminated `__gc` and resurrection — the most semantically problematic gap.
2. The remaining gaps (`collectgarbage` control surface, ephemeron fixpoint for cyclic references) are edge cases that can be documented as deviations.
3. Implementing a custom GC on the JVM would require off-heap memory management, dual-lifetime coordination, and manual write barriers on every hot path — a prohibitive complexity cost.
4. LuaJ, gopher-lua, and MoonSharp all reached the same conclusion independently.

### 8.2 Value Representation

Use a sealed trait hierarchy to represent Lua values:

```scala
sealed trait LuaValue
case object LuaNil extends LuaValue
case class LuaBoolean(value: Boolean) extends LuaValue
case class LuaInt(value: Long) extends LuaValue       // Lua 5.4 integer subtype
case class LuaFloat(value: Double) extends LuaValue
case class LuaString(value: String) extends LuaValue  // interned
class LuaTable extends LuaValue { ... }
class LuaClosure extends LuaValue { ... }
class LuaUserdata(val data: AnyRef) extends LuaValue { ... }
class LuaThread extends LuaValue { ... }              // coroutine
```

- **`LuaInt`/`LuaFloat`**: box `Long`/`Double`. JVM JIT will scalar-replace in hot paths via escape analysis. For extreme throughput, explore Scala 3 opaque types or value class wrappers.
- **`LuaString`**: intern via `String.intern()` or a `WeakHashMap[String, LuaString]` cache to achieve Lua's identity-equality-for-strings semantics.
- **`LuaNil`/`LuaBoolean`**: singletons; zero allocation.

### 8.3 Weak Tables

| Mode         | JVM Implementation                                    | Notes |
|--------------|-------------------------------------------------------|-------|
| `"v"` weak values | `HashMap[LuaValue, WeakReference[LuaValue]]` + `ReferenceQueue` | Eager expunge on `rawset`/`next` |
| `"k"` weak keys   | `java.util.WeakHashMap[LuaValue, LuaValue]`           | Not true ephemerons; document cyclic-key gap |
| `"kv"` both       | `WeakHashMap[LuaValue, WeakReference[LuaValue]]` + `ReferenceQueue` | Combine both |

Use a **single shared `ReferenceQueue`** and a weak-value wrapper that carries the associated map key:

```scala
class WeakValueEntry(
  val key: LuaValue,
  value: LuaValue,
  queue: ReferenceQueue[LuaValue]
) extends WeakReference[LuaValue](value, queue)
```

This allows the `ReferenceQueue` drain loop to remove dead entries by key without a reverse lookup.

For the `"k"` mode, override `LuaValue.hashCode` and `equals` to use object identity (not value equality) for table/userdata/closure/thread types, so `WeakHashMap` correctly tracks object identity. Primitive `LuaInt`/`LuaFloat`/`LuaString` must not be used as weak keys (they are value-semantic; use strong references).

### 8.4 `__gc` Finalizers (Lua 5.x compatibility layer only)

If Lua 5.x `__gc` compatibility is needed:

1. Register a `Cleaner` action when a metatable with `__gc` is set on a `LuaTable`/`LuaUserdata`.
2. The cleanup `Runnable` must be a static class that holds only:
   - The `__gc` function value (as a strong reference, captured at registration time).
   - A minimal snapshot of the object's identity (e.g., a string key or integer id for logging).
   - A reference to the `LuaVM` or a `FinalizerQueue`.
3. The `Runnable` enqueues the `(__gc fn, snapshot id)` pair to a `LinkedBlockingQueue`.
4. A dedicated finalizer-runner coroutine/thread (or a hook in the Lua scheduler) drains the queue between Lua instructions, calling `__gc(snapshotId)`.
5. **Do not attempt LIFO ordering** unless you maintain a global sequence counter per GC cycle — and since JVM GC cycles are not Lua GC cycles, this is an approximation only.
6. Document clearly: resurrection is unsupported; LIFO ordering is best-effort; timing is non-deterministic.

```scala
object LuaFinalizerRegistry:
  val cleaner: Cleaner = Cleaner.create()
  val queue: java.util.concurrent.LinkedBlockingDeque[() => Unit] = 
    new java.util.concurrent.LinkedBlockingDeque()

class TableFinalizerState(
  gcFn: LuaValue,     // strong ref to __gc closure
  objId: Long,        // unique id, NOT a ref to the LuaTable
  vm: LuaVM
) extends Runnable:
  override def run(): Unit =
    // Enqueue for execution on Lua scheduler thread
    LuaFinalizerRegistry.queue.addFirst(() => vm.callFinalizer(gcFn, objId))
```

### 8.5 `collectgarbage` Emulation

| Call                          | JVM Implementation                               |
|-------------------------------|--------------------------------------------------|
| `collectgarbage("collect")`   | `System.gc()` (advisory; document non-determinism) |
| `collectgarbage("count")`     | Track allocation with `AtomicLong` counter in `LuaTable`/`LuaClosure` constructors; approximate |
| `collectgarbage("stop")`      | No-op with warning; or maintain a `gcEnabled: Boolean` flag that suppresses `System.gc()` calls |
| `collectgarbage("restart")`   | No-op or re-enable flag                          |
| `collectgarbage("step", n)`   | No-op; document                                  |
| `collectgarbage("isrunning")`  | Return `true` always                             |

For Luau specifically, `collectgarbage` is disabled by default in the sandbox. Removing it entirely from the exposed API is appropriate.

### 8.6 Frozen/Readonly Tables

Implement `table.freeze` as a `@volatile var frozen: Boolean` or a `val` promoted via a builder pattern:

```scala
class LuaTable:
  @volatile private var _frozen: Boolean = false

  def freeze(): Unit = _frozen = true
  def isFrozen: Boolean = _frozen

  def rawset(k: LuaValue, v: LuaValue): Unit =
    if _frozen then throw LuaRuntimeError("attempt to modify a read-only table")
    // ... normal set logic
```

Frozen tables:
- Need no write-barrier logic after freezing.
- Can be marked as GC roots if they contain no weak references (optimization).
- Standard library tables (`LuaMath`, `LuaString`, etc.) should be constructed once and frozen; they are effectively static JVM singletons.

### 8.7 Coroutines

Map Lua coroutines to JDK 21 **virtual threads** (`Thread.ofVirtual()`). Virtual threads:
- Are cheap to create (microseconds vs milliseconds for platform threads).
- Suspend/resume via `LockSupport.park`/`unpark` — maps to Lua's `coroutine.yield`/`coroutine.resume`.
- Are managed by the JVM scheduler, not pinned to OS threads.
- Are GC'd normally when no longer reachable, matching Lua's "dead coroutine is collected" semantics.

```scala
class LuaThread(body: LuaValue) extends LuaValue:
  private val thread: Thread = Thread.ofVirtual().unstarted(luaBody)
  // Park/unpark for yield/resume
```

Coroutine GC: when the `LuaThread` object becomes unreachable, its virtual thread will also become unreachable (assuming no other strong references). The JVM will GC it. A `Cleaner` can be registered to detect abandoned coroutines and log/clean up resources.

---

## 9. Complete Semantic Gap Register

The following deviations from standard Lua 5.4 GC semantics are inherent to a JVM-hosted implementation and should be documented in the `luau-scala` compatibility notes:

1. **`__gc` LIFO ordering**: not guaranteed. Cleaners execute concurrently with no ordering.
2. **`__gc` resurrection**: unsupported. Objects passed to finalizers are not live JVM objects.
3. **`__gc` timing**: non-deterministic. May run seconds after GC, or not at all before JVM exit.
4. **`collectgarbage` control**: `"stop"`, `"restart"`, `"step"` are no-ops. `"count"` is approximate.
5. **Ephemeron fixpoint**: `WeakHashMap` does not resolve key→value→key cycles. Such cycles will never be collected.
6. **Weak table iteration stability**: `WeakHashMap` entries can disappear mid-iteration. Snapshot before `pairs()` to avoid skipping entries.
7. **Lazy weak-entry expungement**: without `ReferenceQueue` polling, stale entries persist until next table access (LuaJ behavior). Use `ReferenceQueue` for prompt cleanup.
8. **`__gc` on Luau**: Luau never supported `__gc`; none of the above apply to a pure Luau runtime.

---

## 10. Sources

- [Lua 5.4 Reference Manual §2.5 (Garbage Collection)](https://www.lua.org/manual/5.4/manual.html)
- [Luau Memory Management and GC (DeepWiki)](https://deepwiki.com/luau-lang/luau/5.3-memory-management-and-garbage-collection)
- [Luau Sandboxing Documentation](https://luau.org/sandbox/)
- [table.freeze RFC (luau-lang)](https://rfcs.luau.org/function-table-freeze.html)
- [LuaJ GitHub Repository](https://github.com/luaj/luaj)
- [LuaJ GC Issue #55](https://github.com/luaj/luaj/issues/55)
- [LuaJ WeakTable Javadoc](http://luaj.org/luaj/3.0/api/org/luaj/vm2/WeakTable.html)
- [MoonSharp GitHub Repository](https://github.com/moonsharp-devs/moonsharp)
- [MoonSharp vs Lua Differences](https://www.moonsharp.org/moonluadifferences.html)
- [gopher-lua GitHub Repository](https://github.com/yuin/gopher-lua)
- [Announcing go-lua (Shopify)](https://shopify.engineering/announcing-go-lua)
- [Lua GC Implementation Notes (poga)](https://poga.github.io/lua53-notes/gc.html)
- [Lua GC Documentation (cyevgeniy)](https://cyevgeniy.github.io/luadocs/02_basic_concepts/ch05.html)
- [Wikipedia: Ephemeron](https://en.wikipedia.org/wiki/Ephemeron)
- [Java Cleaner API (JDK 21)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ref/Cleaner.html)
- [Replacing Finalizers with Cleaners (inside.java)](https://inside.java/2022/05/25/clean-cleaner/)
- [JEP 421: Deprecate Finalization for Removal](https://openjdk.org/jeps/421)
- [WeakReferences and GC (Poonam Parhar)](https://poonamparhar.github.io/weakreferences_and_gc/)
- [Rembulan JVM Lua Implementation (Hacker News)](https://news.ycombinator.com/item?id=12611255)
- [Understanding Lua's Garbage Collection (ACM, arXiv:2005.13057)](https://dl.acm.org/doi/fullHtml/10.1145/3414080.3414093)
- [LuaJIT 3.0 New GC (Tarantool wiki)](https://github.com/tarantool/tarantool/wiki/LuaJIT-3.0-new-Garbage-Collector)
