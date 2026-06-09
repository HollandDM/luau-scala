# Lua Coroutines on the JVM: Implementation Deep Dive

## 1. The Problem

Lua coroutines are **stackful** and **symmetric-ish**. A coroutine can call `coroutine.yield()` from arbitrary nesting depth — inside a metamethod, inside `table.unpack`, inside `pcall`, inside a debug hook. When it yields, the entire Lua call stack must be preserved. When it resumes, execution must restart exactly where it left off.

The JVM has no native mechanism for this. The call stack is the OS/JVM thread stack; there is no `setjmp`/`longjmp`, no native green-thread primitive in the public API, and no way to serialize and replay stack frames across arbitrary call boundaries — at least not without tricks.

This document covers four real-world implementation strategies, what each existing Lua runtime does, and a concrete recommendation for a new Scala (JDK 21+) implementation.

---

## 2. Background: What Lua Coroutines Actually Need

From the Lua 5.4 spec:

- `coroutine.create(f)` — allocates a new coroutine object wrapping function `f`
- `coroutine.resume(co, ...)` — transfers control into `co`, passing arguments; if `co` has never run, calls `f(...)`; otherwise resumes from the yield point
- `coroutine.yield(...)` — suspends the current coroutine, returning values to the matching `resume`; the current coroutine's stack is frozen
- `coroutine.status(co)` — `"suspended"`, `"running"`, `"dead"`, `"normal"`
- `coroutine.wrap(f)` — convenience iterator form

States: `suspended → running → suspended | dead`. A coroutine calling `resume` on another becomes `"normal"` (suspended but not resumable by others).

The core difficulty: `yield` must work even when Lua has called back into a host function (`coroutine.yield` called from C in PUC Lua; on JVM, called from a Java/Scala method on the call stack). This is the "yield-through-Java" problem.

---

## 3. Existing Implementations

### 3.1 LuaJ — One Java Platform Thread per Coroutine

**Repository:** [github.com/luaj/luaj](https://github.com/luaj/luaj)  
**Key file:** `src/core/org/luaj/vm2/LuaThread.java`

#### Mechanism

`LuaThread` extends `LuaValue` and wraps a `java.lang.Thread`. The internal `State` class holds the status (`STATUS_SUSPENDED`, `STATUS_RUNNING`, `STATUS_DEAD`) and the function to execute.

Synchronization uses Java intrinsic monitor locks (`synchronized` + `wait()` / `notify()`):

- `lua_resume()`:
  1. Sets the new thread as globally running
  2. First call: spawns a new platform `Thread` that executes the Lua function
  3. Calls `state.notify()` to wake the coroutine thread
  4. Calls `state.wait()` on the caller thread — blocks until the coroutine yields or completes

- `lua_yield()`:
  1. Sets status to `STATUS_SUSPENDED`
  2. Calls `state.notify()` — wakes the blocked caller
  3. Calls `state.wait()` on the coroutine thread — waits for next `resume`
  4. Periodically wakes up (default 5000ms polling interval) to check a `WeakReference` to itself; if the reference is gone, throws `OrphanedThread` to clean up the zombie coroutine

#### Orphan GC

The `WeakReference` trick: if no one holds a reference to the `LuaThread`, its backing Java thread will keep looping forever. Every 5 seconds it checks whether the weak ref is cleared; if so it throws `OrphanedThread` to terminate itself. This is inherently racy and slow — it means dead coroutines are not collected promptly.

#### Problems

1. **One platform thread per coroutine** — platform threads consume ~1 MB stack each. At scale this is catastrophic. The SwitchCraft Minecraft server ([CC:Tweaked Tweaking blog](https://www.squiddev.cc/2019/03/08/tweaking-cc-tweaked.html)) had 250,000 threads created at 50/s before crashing.
2. **Context-switch cost** — `wait()`/`notify()` involve OS scheduler involvement even when only one coroutine runs at a time.
3. **Orphan cleanup latency** — 5-second polling window; dead coroutines hold threads for up to 5s.
4. **No preemption** — to interrupt a misbehaving coroutine requires `Thread.interrupt()`, which has limited effect inside pure Lua loops.

#### Verdict

Correct and simple to implement. Completely unscalable beyond a few dozen simultaneous coroutines.

---

### 3.2 GopherLua — One Goroutine per Coroutine with Channel Handoff

**Repository:** [github.com/yuin/gopher-lua](https://github.com/yuin/gopher-lua)  
**Design writeup:** [0value.com/implementing-lua-coroutines-in-go](https://www.0value.com/implementing-lua-coroutines-in-go)

#### Mechanism

This is Go, not JVM, but it's instructive because Go goroutines are cheap (~4 KB stack, growable), making thread-per-coroutine viable in a way it isn't on the JVM.

Each Lua coroutine maps to a goroutine. Two **unbuffered channels** provide synchronization:

- `yld chan LuaValue` — coroutine sends yielded values, then blocks waiting on `rsm`
- `rsm chan LuaValue` — caller sends resume values, unblocking the goroutine

The handoff protocol:
1. Caller calls `L.Resume(co, ...)` — sends on `rsm`
2. Goroutine receives from `rsm`, executes until `coroutine.Yield(v)`
3. Goroutine sends `v` on `yld`, then blocks on `rsm`
4. Caller receives from `yld`, gets the yield value
5. Repeat

States: `StSuspended`, `StRunning`, `StDead`.

#### Limitation: Goroutine Leaks

Unlike LuaJ's WeakReference polling, Go has no finalizer-based cleanup. A goroutine stuck waiting on `rsm` is never collected. Callers **must** call `Cancel()` explicitly. This maps to the general Lua problem of coroutines that are never resumed — they become garbage in pure Lua but leaking goroutines in Go.

#### JVM Relevance

The goroutine model maps cleanly to JDK 21 virtual threads (Section 5.2). Virtual threads start at ~1 KB, grow on demand, and can be created by the millions. The channel-per-coroutine pattern maps to `java.util.concurrent.SynchronousQueue` or `Exchanger`. This is the modern JVM equivalent of the goroutine approach.

---

### 3.3 Rembulan — CPS / Exception-Based Stackless

**Repository:** [github.com/mjanicek/rembulan](https://github.com/mjanicek/rembulan)  
**Design doc:** [doc/CoroutinesOverview.md](https://github.com/mjanicek/rembulan/blob/master/doc/CoroutinesOverview.md)

#### Mechanism

Rembulan compiles Lua 5.3 to Java bytecode and implements coroutines **without any threads**. The model:

Every `LuaFunction` subclass provides two methods:

```java
// Normal entry (first call or tail call)
void invoke(ExecutionContext ctx, Object[] args) throws ...;

// Re-entry after suspension
void resume(ExecutionContext ctx, Object[] args) throws ...;
```

Suspension is triggered by throwing a special control throwable. The pattern inside every potentially-yieldable call site:

```java
try {
    callee.invoke(ctx, args);
} catch (UnresolvedControlThrowable ct) {
    // Capture local state into the throwable
    throw ct.resolve(this, localState);
}
```

`ct.resolve(this, localState)` produces a `ResolvedControlThrowable` that holds the entire captured call chain (linked list of `(function, saved_locals)` frames). This propagates up the stack, each frame adding itself, until it reaches the top-level executor.

To resume a suspended coroutine, the executor reconstructs the call stack from the linked list and re-enters via `resume()` on the topmost frame.

#### Properties

- **Single-threaded** — no locking, no thread context switches
- **Allocation on yield only** — the `ControlThrowable` is only allocated when a yield actually fires; hot paths have zero overhead from the coroutine machinery
- **CPU accounting** — the tick-budget mechanism plugs naturally into `invoke`/`resume`: decrement a counter at each call, throw `UnresolvedControlThrowable` when budget exhausted
- **JVM exception cost** — `throw`/`catch` is not free; JVM exceptions require capturing a stack trace by default (can be suppressed with `fillInStackTrace` override); frame propagation is O(depth)
- **No yield-through-Java** — if a Java callback doesn't follow the `try/catch ct.resolve` pattern, yield is impossible through it; every native binding must be written in the "suspendable" style

#### Comparison with Piccolo (Rust)

Piccolo ([github.com/kyren/piccolo](https://github.com/kyren/piccolo)) uses the same trampoline idea in Rust: an `Executor` calls `step(fuel)` in a loop; the entire Lua call stack is a `Sequence` object on the heap, not the Rust stack. Yields return control to the trampoline. The blog post [kyju.org/blog/piccolo-a-stackless-lua-interpreter/](https://kyju.org/blog/piccolo-a-stackless-lua-interpreter/) describes this as enabling full symmetric coroutines (`coroutine.yieldto`) since you're always at the outermost call frame.

Rembulan's approach is analogous but uses Java bytecode compilation + exceptions instead of Rust's ownership/Future model.

---

### 3.4 Cobalt — Exception Unwind + Selective Thread Fallback

**Repository:** [github.com/cc-tweaked/Cobalt](https://github.com/cc-tweaked/Cobalt)  
**Design posts:** [squiddev.cc/2019/03/08](https://www.squiddev.cc/2019/03/08/tweaking-cc-tweaked.html), [squiddev.cc/2023/03/29](https://squiddev.cc/2023/03/29/coroutines-and-bytecode.html)

Cobalt is a re-entrant fork of LuaJ developed for CC:Tweaked (Minecraft mod). It evolved through two major architectural revisions.

#### Phase 1: Exception-Based Yield

Replaced LuaJ's `wait()`/`notify()` protocol with exception throwing:

- `coroutine.yield()` throws a `LuaError` subclass that unwinds the Java call stack to the top-level interpreter
- The interpreter catches it, stores the coroutine state, and switches to another coroutine
- Resume restores state and calls back in

This dropped thread count from 2000 to ~50 on the target server (250 Minecraft computers). The trick: all Lua functions and most standard library functions are "re-entrant aware" — they catch the unwind exception and re-throw it after saving their frame state.

#### Phase 2: Bytecode Rewriting

[2023 blog post](https://squiddev.cc/2023/03/29/coroutines-and-bytecode.html) describes automating the "re-entrant aware" transformation via ASM bytecode rewriting at class-load time.

The transformer:

1. **Build control flow graph** of the target method
2. **Identify suspension points** — any call site that may transitively yield (call to another suspendable method, metamethod invocation, etc.)
3. **Assign state numbers** to each suspension point
4. **Generate a state machine**:
   - Entry adds a `TABLESWITCH` on a state field; state 0 = initial entry, state N = resume after suspension point N
   - Before each suspension point, save all live local variables into a continuation object
   - After each suspension point, inject code to restore locals from the continuation

The result is ugly generated bytecode but executes at near-native speed: **zero allocation on the hot path** (no continuation objects created unless a yield actually fires), performance "within a few percentage points of the original." Comparison against Kotlin coroutines on the `load` parser: Cobalt 2x faster, 3x fewer allocations.

#### Hybrid Fallback

For Java-native functions that haven't been transformed (third-party code, JNI, etc.), Cobalt falls back to the LuaJ thread model for that call depth. A yield through an untransformed frame triggers thread creation. This maintains compatibility at the cost of occasional thread allocation.

---

### 3.5 MoonSharp — C# Thread-per-Coroutine (Unity-compatible variant)

**Repository:** [github.com/moonsharp-devs/moonsharp](https://github.com/moonsharp-devs/moonsharp)  
**Coroutine page:** [moonsharp.org/coroutines.html](https://www.moonsharp.org/coroutines.html)

MoonSharp (C#/.NET) uses a `Processor` class as the coroutine backing. On platforms where threads are available, it uses one C# `Thread` per coroutine with `ManualResetEvent` handoff — essentially the same pattern as LuaJ. On Unity (where thread creation is restricted), it uses a stackless approach where the `Processor` acts as an explicit stack and coroutines only yield at explicit `coroutine.yield` call sites (no yield-through-C# functions).

The `AutoYieldCounter` property enables **preemptive suspension**: set it to N instructions; after N instructions the Processor returns a `DataType.YieldRequest`, caller must re-resume. This implements cooperative multitasking without threads.

**JVM relevance:** the AutoYieldCounter mechanism is directly applicable — count VM dispatch iterations, yield the `resume()` call after N steps. Useful for sandboxing untrusted scripts.

---

## 4. JVM-Specific Approaches: Detailed Analysis

### 4.1 Thread-per-Coroutine + Blocking Handoff (Platform Threads)

**Mechanism:**

```
resume:  caller.wait() + coroutine.notify()
yield:   coroutine.wait() + caller.notify()
```

Or with `SynchronousQueue<LuaValue[]>`:

```scala
val toCoroutine   = new SynchronousQueue[Array[LuaValue]]()
val fromCoroutine = new SynchronousQueue[Array[LuaValue]]()

// caller resume:
toCoroutine.put(args)           // unblocks coroutine
val yieldedVals = fromCoroutine.take()  // blocks caller

// coroutine yield:
fromCoroutine.put(vals)         // unblocks caller
val resumeArgs = toCoroutine.take()   // blocks coroutine
```

**Properties:**
- Dead simple, correct by construction
- Stack depth unlimited — platform thread has ~512 KB–8 MB stack
- Yield-through-Java works for free — no special handling of native frames
- **1 platform thread per coroutine** — each thread 512 KB–2 MB resident
- **Context switch ~1–10 µs** — OS scheduler, TLB flushing
- At 1000 concurrent coroutines: ~500 MB stack RAM, scheduler pressure, high latency tail
- Not viable above ~10,000 coroutines on a typical JVM

**When acceptable:** dev/test use, very small coroutine counts (< 100), prototype implementations.

---

### 4.2 Project Loom Virtual Threads / `jdk.internal.vm.Continuation` (JDK 21+)

#### Virtual Thread Approach

JDK 21 ships virtual threads as a stable public API (JEP 444). Virtual threads:

- ~100 bytes initial metadata (stack grows on demand from heap)
- Scheduled by `ForkJoinPool` in work-stealing mode
- When a virtual thread blocks (on `SynchronousQueue.take()`, I/O, locks), the JVM **unmounts** it from the carrier thread — saves the stack frames to heap — and the carrier thread picks up another virtual thread
- Resume: frames are remounted to a carrier thread from heap

This means the goroutine/channel pattern from GopherLua works directly on JDK 21:

```scala
import java.util.concurrent.SynchronousQueue

class LuaCoroutine(body: CoroutineContext => Unit) {
  private val toCoroutine   = new SynchronousQueue[Array[LuaValue]]()
  private val fromCoroutine = new SynchronousQueue[Array[LuaValue]]()

  private val vthread: Thread = Thread.ofVirtual().start(() => {
    val ctx = new CoroutineContext(
      yield_ = vals => {
        fromCoroutine.put(vals)          // unblock the resumer
        toCoroutine.take()               // wait for next resume
      }
    )
    body(ctx)
    fromCoroutine.put(DEAD_SENTINEL)     // signal completion
  })

  def resume(args: Array[LuaValue]): Array[LuaValue] = {
    toCoroutine.put(args)
    fromCoroutine.take()
  }
}
```

Key: `SynchronousQueue.take()` causes a virtual thread to unmount (it's a blocking operation backed by a `LockSupport.park()`-compatible path). No OS thread is consumed while the coroutine is suspended.

**Pinning hazard:** virtual threads are **pinned** (cannot unmount) during:
1. `synchronized` blocks — use `java.util.concurrent.locks.ReentrantLock` instead
2. Native frames / JNI — unavoidable; keep native calls outside coroutine bodies
3. `@jdk.internal.vm.annotation.ReservedStackAccess` critical sections

Inside a Lua VM built in pure JVM code, pinning is avoidable if `synchronized` is replaced with `ReentrantLock` everywhere in the VM's hot path.

**Cost model:**
- Virtual thread creation: ~1 µs, ~few hundred bytes heap
- `SynchronousQueue` handoff: ~200–500 ns (no OS involvement when threads stay on-JVM)
- 1,000,000 suspended virtual threads: ~few hundred MB heap (stack frames on heap)
- **Same code structure as LuaJ** but scales to millions of coroutines

#### Raw Continuation API: `jdk.internal.vm.Continuation`

The lower-level primitive underlying virtual threads. API:

```java
// jdk.internal.vm.Continuation (internal, JDK 21)
public Continuation(ContinuationScope scope, Runnable target)
public void run()                         // mount and execute until yield
public static void yield(ContinuationScope scope)  // suspend
public boolean isDone()
```

`ContinuationScope` is a delimiter — `yield(scope)` walks the parent continuation chain until it finds one with the matching scope.

To use from Scala on JDK 21:

```
--add-opens java.base/jdk.internal.vm=ALL-UNNAMED
```

This is an **internal, unstable API**. It was available in early Loom previews and still exists in JDK 21 but Oracle has explicitly not committed to its stability. The virtual thread API is the supported surface.

**Advantage of raw Continuation:** avoids even the `SynchronousQueue` overhead. One `Continuation` per Lua coroutine; `run()` and `yield()` are the sole control transfer operations. No threads at all — `Continuation` does not require a Thread to drive it; any code can call `continuation.run()`.

```scala
val scope = new ContinuationScope("lua-coroutine")

val cont = new Continuation(scope, () => {
  // Lua VM body runs here
  // yield:
  Continuation.yield(scope)
  // resume continues here
})

// resume:
cont.run()  // returns at yield point
// cont.isDone() is false -> suspended
cont.run()  // resumes from yield point
```

This gives **true coroutine semantics** without any threads. Stack frames are saved to the heap when `yield` fires.

**Risk:** `jdk.internal.vm.Continuation` may change or be hidden in future JDK versions. JEP 429 (Scoped Values), JEP 453 (Structured Concurrency) build on virtual threads but do not expose raw Continuation. Betting on this API is risky for a production library.

---

### 4.3 CPS / Stackless Interpreter — VM Loop as State Machine

**Core idea:** the interpreter's main dispatch loop is the only thing on the Java stack. All Lua call frames are on the heap as an explicit stack data structure. Suspension = return from `step()`. Resume = call `step()` again with the heap-allocated frame stack.

#### Data Structures

```scala
sealed trait Frame
case class LuaFrame(
  fn: LuaFunction,
  pc: Int,          // program counter into bytecode
  locals: Array[LuaValue],
  openUpvals: List[UpvalRef],
  continuation: Array[LuaValue] => Frame  // what to do with return values
) extends Frame

case class NativeFrame(
  fn: NativeFunction,
  savedState: Any,  // native-specific resumption state
  continuation: Array[LuaValue] => Frame
) extends Frame
```

The VM holds a `Stack[Frame]` (a `var stack: List[Frame]` or `ArrayDeque`). On each `step()`:

```
while (fuel > 0 && stack.nonEmpty) {
  stack.top match {
    case LuaFrame(fn, pc, locals, ...) =>
      val instr = fn.code(pc)
      instr match {
        case CALL(f, args) =>
          stack.push(LuaFrame(f, 0, args, ...))
        case RETURN(vals) =>
          stack.pop()
          stack.top.continuation(vals)  // pass return values to caller frame
        case YIELD(vals) =>
          yieldedValues = vals
          return Suspended  // exit step(), stack preserved on heap
        ...
      }
      fuel -= 1
  }
}
```

Resume: just call `step()` again.

#### Native Function Handling

The hard part is native functions. If a Scala function calls back into Lua which eventually yields:

**Option A — Prohibit yield-through-native:** throw `LuaError("attempt to yield across metamethod/C-call boundary")`. This is what Lua 5.1 did. Simple but breaks `table.sort` with yielding comparators.

**Option B — CPS-style native bindings:** every native function that might yield must be written as a state machine with an explicit saved-state:

```scala
class TableSort extends NativeFunction {
  // initial entry
  def invoke(args: Array[LuaValue]): NativeResult = {
    val tbl = args(0).asTable
    // start sort, invoke comparator
    NativeResult.CallLua(comparatorFn, Array(a, b), 
      savedState = SortState(tbl, sortProgress),
      continuation = this)  // call me back with comparator result
  }
  
  // re-entry after comparator returned or yielded
  def resume(savedState: Any, result: Array[LuaValue]): NativeResult = {
    val state = savedState.asInstanceOf[SortState]
    // integrate comparator result, advance sort
    ...
  }
}
```

This is the Rembulan model. It makes standard library functions complex to write but produces correct, alloc-free behavior.

**Option C — Hybrid:** stackless for pure Lua frames; fall back to a virtual thread when a native frame that doesn't implement the resumable protocol appears on the stack (Cobalt's approach).

#### Performance Characteristics

- **Zero OS thread overhead** — entire runtime is single-threaded from the OS perspective
- **GC pressure** — frame objects allocated per call; short-lived, should be nursery-collected
- **Allocation only on cross-language boundaries** — pure Lua loops do not allocate frame objects (the loop counter fits in `locals` array, updated in place)
- **No yield-through-opaque-Java** without native cooperation
- **Preemption trivial** — `step(fuel)` returns after `fuel` instructions; outer scheduler resumes it

---

### 4.4 Bytecode Rewriting — Quasar / Kotlin / Cobalt Style

**Core idea:** write the VM and standard library in normal (blocking) Java/Kotlin/Scala style. An ASM-based transformation post-processes the `.class` files to make any method that might transitively yield into a state machine.

#### Quasar's Approach

[Quasar](https://docs.paralleluniverse.co/quasar/) (pre-Loom JVM fiber library) ran as a Java agent. Every method annotated `@Suspendable` (or throwing `SuspendExecution`) was instrumented:

1. **Instrument call sites:** before each call to another `@Suspendable` method, inject:
   ```
   IF (fiber is resuming AND state == N) GOTO resume_point_N
   ```
2. **Save state:** at each potential suspension point, spill all live locals into the `Fiber.stack`:
   ```java
   stack.sp = 5;
   stack.dataLong[0] = localLong0;
   stack.dataObject[1] = localRef1;
   ```
3. **Restore state:** at resume points, reload from `Fiber.stack`

The `Fiber.stack` is a `Stack` object with `long[]` and `Object[]` arrays for primitives and references.

Quasar uses `ForkJoinPool` as the scheduler, intercepting blocking calls (`Thread.sleep`, `Object.wait`, socket I/O) to yield instead of block the carrier thread.

#### Cobalt's Bytecode Rewriting (2023)

Cobalt's transformer ([squiddev.cc/2023/03/29](https://squiddev.cc/2023/03/29/coroutines-and-bytecode.html)) differs from Quasar in an important way: it transforms **only the methods known to be suspendable** in the Cobalt standard library, not arbitrary user code. The transformation:

1. **Control flow graph** of the bytecode
2. **Identify yield points** — call sites to other `@Suspendable` methods (metamethod invocations, `invoke` on `LuaFunction`)
3. **State numbering** — assign integer state to each yield point
4. **Generate**:
   - Entry dispatch: `TABLESWITCH` on `resumeState` field
   - Before yield point N: store all live locals into a heap-allocated `ResumePoint` object; set `resumeState = N`; throw `YieldException` (no stack trace — override `fillInStackTrace`)
   - At state N entry: restore locals from `ResumePoint`

The generated code is ugly but the hot path (no yield) has minimal overhead: the `TABLESWITCH` on state 0 goes directly to normal execution; no objects allocated.

**Performance comparison (from Cobalt blog):**
- Cobalt's transformed `load` parser vs Kotlin coroutine-based equivalent:
  - Cobalt: 2x faster throughput
  - Cobalt: 3x fewer allocations
- The Kotlin coroutine compiler generates similar state machines but allocates a `Continuation` object up front; Cobalt's transformation defers allocation until yield occurs

#### Tradeoffs

| Factor | Quasar | Cobalt Transform |
|--------|--------|-----------------|
| Annotation burden | `@Suspendable` on every method in chain | Only standard library methods, not user bytecode |
| Transformation scope | All user code at load time | Fixed set of VM-internal methods |
| Yield-through-Java | Works for annotated frames | Falls back to thread model for unannotated frames |
| Allocation | On yield | On yield |
| Hot-path overhead | Low | Near-zero |
| JDK compatibility | Works pre-Loom | Works any JDK |
| Maintenance | Complex ASM code | Complex ASM code |

---

## 5. Recommendation for a Modern Scala / JDK 21+ Implementation

### 5.1 Primary Recommendation: Virtual Thread per Coroutine with `SynchronousQueue` Handoff

**Use `Thread.ofVirtual()` + `SynchronousQueue` (or `Exchanger`) as the coroutine handoff primitive.**

This is the cleanest, most maintainable, highest-correctness approach for a new Scala implementation on JDK 21+.

#### Rationale

1. **Yield-through-Scala works for free.** Any call depth, any Scala function in the middle — if the virtual thread is blocked on `SynchronousQueue.take()`, the JVM unmounts it. No special annotation, no bytecode transformation, no CPS wrapping of native functions.

2. **Scales to millions.** Virtual threads use ~few hundred bytes heap per suspended coroutine. 1,000,000 suspended coroutines ≈ hundreds of MB, not terabytes.

3. **Identical code structure to LuaJ.** The existing mental model (one thread per coroutine, blocking handoff) is correct — just swap platform threads for virtual threads. No new concurrency model to learn.

4. **Avoids internal APIs.** Unlike `jdk.internal.vm.Continuation`, `Thread.ofVirtual()` is a stable public API since JDK 21.

5. **Preemption possible.** A separate watchdog can call `vthread.interrupt()` to break out of infinite loops. The Lua VM catches `InterruptedException` and throws a Lua error.

#### Pinning Avoidance Checklist

The VM implementation must not pin virtual threads:

- Replace all `synchronized` blocks in the VM's hot path (table access, value coercion, metamethod dispatch) with `java.util.concurrent.locks.ReentrantLock`
- Keep JNI calls (if any) outside coroutine bodies or accept pinning there
- `SynchronousQueue` itself uses `LockSupport.park()` internally, which does not pin

#### Skeletal Scala Implementation

```scala
import java.util.concurrent.{SynchronousQueue, Exchanger}
import scala.util.control.NonFatal

enum CoroutineStatus:
  case Suspended, Running, Normal, Dead

class LuaCoroutine(body: LuaCoroutine => Array[LuaValue] => Array[LuaValue]) {
  // Values flowing into coroutine (resume args)
  private val incoming = new SynchronousQueue[Either[Throwable, Array[LuaValue]]]()
  // Values flowing out (yield args or final return)
  private val outgoing = new SynchronousQueue[Either[Throwable, Array[LuaValue]]]()

  @volatile var status: CoroutineStatus = CoroutineStatus.Suspended

  private val vthread: Thread = Thread.ofVirtual().unstarted(() => {
    try {
      val firstArgs = incoming.take().getOrElse(throw new RuntimeException("cancelled"))
      val result = body(this)(firstArgs)
      outgoing.put(Right(result))
    } catch {
      case e: LuaError => outgoing.put(Left(e))
      case NonFatal(e) => outgoing.put(Left(LuaError.wrap(e)))
    }
  })

  /** Called from the Lua VM when executing coroutine.yield() */
  def yield_(vals: Array[LuaValue]): Array[LuaValue] = {
    status = CoroutineStatus.Suspended
    outgoing.put(Right(vals))         // send yield values to resumer
    incoming.take() match {           // wait for next resume (UNMOUNTS virtual thread here)
      case Right(args) =>
        status = CoroutineStatus.Running
        args
      case Left(e) => throw e
    }
  }

  /** Called from another Lua coroutine or main thread */
  def resume(args: Array[LuaValue]): Either[String, Array[LuaValue]] = {
    status match {
      case CoroutineStatus.Dead => Left("cannot resume dead coroutine")
      case CoroutineStatus.Running => Left("cannot resume non-suspended coroutine")
      case CoroutineStatus.Normal => Left("cannot resume normal coroutine")
      case CoroutineStatus.Suspended =>
        status = CoroutineStatus.Running
        if (!vthread.isAlive) vthread.start()
        incoming.put(Right(args))     // unblock virtual thread
        outgoing.take() match {       // wait for yield or completion (UNMOUNTS caller if also virtual)
          case Right(vals) =>
            if (!vthread.isAlive) status = CoroutineStatus.Dead
            Right(vals)
          case Left(e) =>
            status = CoroutineStatus.Dead
            Left(e.getMessage)
        }
    }
  }
}
```

The `yield_` method is injected into the Lua VM's `YIELD` opcode handler and standard library functions that yield (e.g., `coroutine.yield`).

#### The `Normal` State

When coroutine A calls `coroutine.resume(B, ...)`, A transitions to `Normal`. A is not dead but cannot be resumed by anyone else until B yields or dies. This is tracked in `status` and enforced in `resume()`. Both A and B are virtual threads: A is blocked on `outgoing.take()`, B is running.

---

### 5.2 Alternative: Raw `jdk.internal.vm.Continuation`

If the internal API risk is acceptable (fork-and-maintain JDK version, or use a JDK you control):

```scala
import jdk.internal.vm.{Continuation, ContinuationScope}

class LuaCoroutine(body: () => Unit) {
  val scope = new ContinuationScope("lua")
  val cont  = new Continuation(scope, () => body())

  // Current yield values (shared state — only one runs at a time)
  var yieldedValues: Array[LuaValue] = _
  var resumeValues:  Array[LuaValue] = _

  def resume(args: Array[LuaValue]): Array[LuaValue] = {
    resumeValues = args
    cont.run()      // returns when yield fires or body completes
    yieldedValues
  }

  // Called from inside the Lua VM when coroutine.yield fires
  def yield_(vals: Array[LuaValue]): Array[LuaValue] = {
    yieldedValues = vals
    Continuation.yield(scope)  // suspends, control returns to cont.run() caller
    resumeValues               // populated by the next resume()
  }
}
```

No threads at all. `cont.run()` and `Continuation.yield(scope)` are pure stack-capture/restore operations. Zero synchronization needed since coroutines are cooperative (only one runs at a time on the caller's thread).

**Access required:**
```
--add-opens java.base/jdk.internal.vm=ALL-UNNAMED
```

**Risk:** not a public API. JEP draft for "Scoped Continuations" (as of mid-2025) has not advanced to standard. `jdk.internal.vm.Continuation` exists in JDK 21–24 but is not guaranteed beyond.

**Advantage:** ~100 ns resume/yield roundtrip vs ~200–500 ns for `SynchronousQueue`; no threads allocated; no scheduler involvement.

---

### 5.3 Alternative: Stackless VM + CPS Native Bindings

For maximum portability (JDK 8+, GraalVM native image, Android), implement the stackless approach:

- Lua bytecode dispatch loop owns no Java stack frames
- `LuaCallStack` is a heap `ArrayDeque[Frame]`
- `step(fuel: Int): StepResult` returns `Suspended(yieldVals)` or `Completed(retVals)` or `NeedMore`
- `coroutine.yield` sets a flag/returns `Suspended` from `step()`
- Native library functions implement `SuspendableFunction` trait with `invoke` + `resume` methods

**Tradeoffs vs virtual-thread approach:**
- (+) No JDK version dependency; works on GraalVM native-image (virtual threads are not available in GraalVM native image as of 2025)
- (+) Predictable allocation profile
- (-) Every standard library function touching Lua (sort, pcall with coroutine, string.gmatch with iterator coroutines) must implement the two-method protocol
- (-) Non-trivial to implement correctly; Rembulan had multiple correctness bugs in its continuation chain

---

### 5.4 Approach Comparison Matrix

| Approach | Complexity | Scale | Yield-through-native | JDK req | Alloc/yield | Resume latency |
|----------|-----------|-------|----------------------|---------|-------------|----------------|
| Platform thread + `wait/notify` (LuaJ) | Low | ~1K coroutines | Free | Any | OS thread | ~10 µs |
| Virtual thread + `SynchronousQueue` | Low | ~1M coroutines | Free | JDK 21 | ~0 | ~500 ns |
| `jdk.internal.vm.Continuation` | Low | ~1M coroutines | Free | JDK 21 (internal) | ~0 | ~100 ns |
| Stackless / CPS (Rembulan) | High | Unlimited | Requires protocol | Any | Frame objects | ~100 ns |
| Bytecode rewriting (Cobalt/Quasar) | Very high | Unlimited | Partial / hybrid | Any | On yield only | ~100 ns |
| Goroutine/virtual-thread channel (GopherLua pattern) | Low | ~1M coroutines | Free | JDK 21 | ~0 | ~500 ns |

---

## 6. Implementation Notes for a Scala Codebase

### Coroutine Isolation and the Global State Problem

LuaJ has a single `Globals` shared across all coroutines on a thread. In a virtual-thread model, `Globals` can be shared read-only; mutable state (current running coroutine pointer, open upvalues) must be thread-local or per-coroutine.

```scala
class LuaState {
  // Shared (immutable after init)
  val globals: LuaTable = new LuaTable()
  val stringMeta: LuaTable = ...

  // Per-coroutine (each virtual thread has its own LuaCallStack)
  val currentCoroutine: ThreadLocal[LuaCoroutine] = new ThreadLocal()
}
```

`ThreadLocal` works correctly with virtual threads in JDK 21 (each virtual thread has its own `ThreadLocal` storage).

### Coroutine.wrap Implementation

`coroutine.wrap(f)` returns a function that resumes the coroutine on each call and raises an error if the coroutine dies. In the virtual-thread model:

```scala
def wrapCoroutine(f: LuaValue): LuaValue = {
  val co = new LuaCoroutine(_ => luaCall(f, _))
  LuaFunction { args =>
    co.resume(args) match {
      case Right(vals) => vals
      case Left(msg)   => throw LuaError(msg)
    }
  }
}
```

### Orphaned Coroutine Cleanup

With virtual threads, an orphaned suspended coroutine (no one holds a reference, no one will ever resume it) is a virtual thread blocked forever on `incoming.take()`. Unlike LuaJ's 5-second polling, JVM `WeakReference` + `ReferenceQueue` plus a background cleanup thread can handle this:

```scala
private val refQueue = new java.lang.ref.ReferenceQueue[LuaCoroutine]()

// When creating a coroutine, register weak reference
val ref = new java.lang.ref.WeakReference(co, refQueue)
liveCoroutines.add(ref -> co.vthread)

// Background daemon thread
Thread.ofVirtual().name("lua-coroutine-gc").start(() => {
  while (true) {
    val ref = refQueue.remove()  // blocks until a coroutine is GC'd
    val thread = liveCoroutines.remove(ref)
    thread.foreach(_.interrupt())  // interrupt blocks SynchronousQueue.take()
  }
})
```

The coroutine's `incoming.take()` wraps the result and handles `InterruptedException` → clean exit.

### Error Propagation Through Coroutines

Lua 5.4 spec: if a coroutine body raises an error, `coroutine.resume` returns `false, errorMessage`. `pcall` inside a coroutine catches errors within that coroutine. The virtual-thread model naturally propagates exceptions: the body's `try/catch` sends `Left(error)` through `outgoing`, and `resume()` returns `Left(message)`.

### `coroutine.isyieldable` and `coroutine.running`

- `coroutine.running`: return the current coroutine (or `nil` + true if main thread). Store current coroutine in a `ThreadLocal[Option[LuaCoroutine]]`.
- `coroutine.isyieldable`: `true` if running inside a coroutine (not the main thread) AND the coroutine's resumer is reachable. In the virtual-thread model, always `true` inside a coroutine body.

---

## 7. GraalVM / Native Image Caveat

If the target includes GraalVM native-image compilation (ahead-of-time, no JIT), virtual threads have limited support as of GraalVM CE 21 (experimental, not all `LockSupport.park()` paths are handled). For native-image targets, the **stackless/CPS approach** (Section 4.3) is the only portable option. Kotlin/Native and Scala Native face the same constraint.

---

## 8. Summary

For a Lua implementation targeting JDK 21+ on the JVM in Scala:

**Use virtual threads + `SynchronousQueue`** as the primary approach. It is:
- Architecturally simple (same as LuaJ, replace `Thread` with `Thread.ofVirtual()`)
- Correct by default (yield-through-Scala works, no annotation discipline required)
- Scalable to millions of concurrent coroutines
- Backed by a stable, committed public API

Supplement with:
- `ReentrantLock` instead of `synchronized` to avoid carrier thread pinning
- `WeakReference` + interrupt for orphaned coroutine cleanup
- `AutoYieldCounter` / instruction budget in `step()` for script sandboxing

Only consider `jdk.internal.vm.Continuation` if you need sub-microsecond yield latency and can accept API instability. Only consider the stackless/CPS approach if you need GraalVM native-image or JDK 8/11 compatibility.

---

## References

- [LuaJ LuaThread source](https://github.com/luaj/luaj/blob/master/src/core/org/luaj/vm2/LuaThread.java)
- [LuaJ README](https://github.com/luaj/luaj/blob/master/README.md)
- [Cobalt: A re-entrant fork of LuaJ](https://github.com/cc-tweaked/Cobalt)
- [SquidDev: Tweaking the internals of CC:Tweaked (2019)](https://www.squiddev.cc/2019/03/08/tweaking-cc-tweaked.html)
- [SquidDev: Efficient coroutines by rewriting bytecode (2023)](https://squiddev.cc/2023/03/29/coroutines-and-bytecode.html)
- [Rembulan CoroutinesOverview.md](https://github.com/mjanicek/rembulan/blob/master/doc/CoroutinesOverview.md)
- [Piccolo: A Stackless Lua Interpreter (blog)](https://kyju.org/blog/piccolo-a-stackless-lua-interpreter/)
- [Piccolo GitHub](https://github.com/kyren/piccolo)
- [GopherLua](https://github.com/yuin/gopher-lua)
- [Implementing Lua coroutines in Go](https://www.0value.com/implementing-lua-coroutines-in-go)
- [MoonSharp coroutines](https://www.moonsharp.org/coroutines.html)
- [OpenJDK Loom Continuation.java](https://github.com/openjdk/loom/blob/fibers/src/java.base/share/classes/jdk/internal/vm/Continuation.java)
- [Virtual Threads basis (foojay.io)](https://foojay.io/today/the-basis-of-virtual-threads-continuations/)
- [Quasar documentation](https://docs.paralleluniverse.co/quasar/)
- [Project Loom proposal](https://cr.openjdk.org/~rpressler/loom/Loom-Proposal.html)
- [Kotlin coroutine CPS transformation](https://www.sobyte.net/post/2022-01/kotlin-coroutine-cps/)
- [Piccolo on Hacker News](https://news.ycombinator.com/item?id=40239029)
