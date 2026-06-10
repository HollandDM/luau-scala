# Piccolo: A Stackless Lua VM in Rust — Technical Deep Dive

**Primary sources:**
- Repository: https://github.com/kyren/piccolo
- gc-arena crate: https://github.com/kyren/gc-arena
- Design blog post: https://kyju.org/blog/piccolo-a-stackless-lua-interpreter/
- Rust forum announcement: https://users.rust-lang.org/t/piccolo-stackless-lua-vm-implemented-in-pure-rust/104720

---

## 1. Project Overview

Piccolo (formerly "luster") is an experimental Lua 5.4-compatible interpreter written in pure Rust. Design priorities, in descending order:

1. Working, useful Lua interpreter
2. Safe sandboxing of untrusted scripts
3. DoS resistance — scripts must not be able to panic the host or consume unbounded memory
4. Safe Rust↔Lua bindings with GC integration
5. Pragmatic PUC-Rio Lua compatibility
6. Avoid unnecessary performance regressions

The defining architectural choice: the entire execution model is **stackless** — the implemented language's call stack is never 1:1 with the host (Rust) call stack.

---

## 2. gc-arena: Compile-Time-Safe Garbage Collection

### 2.1 Core Concept

`gc-arena` does not add a global GC to Rust. Instead it provides garbage collection within **isolated, self-contained arenas**. All `Gc<>` pointers must stay inside their originating arena — there is no cross-arena sharing.

Source: `gc-arena/src/arena.rs`, `gc.rs`, `collect.rs`

### 2.2 `Gc<'gc, T>` — The Pointer Type

```rust
pub struct Gc<'gc, T: ?Sized + 'gc> {
    ptr: NonNull<GcBoxInner<T>>,
    _invariant: Invariant<'gc>,  // PhantomData<Cell<&'gc ()>>
}
```

Key properties:
- **Machine-pointer-sized** — no fat pointers, no reference counting overhead
- **Implements `Copy`** — semantically a raw pointer, copying bits is safe
- **Invariant over `'gc`** — prevents lifetime variance exploits
- **Zero mutation bookkeeping** — no ref-count increment on copy
- `as_ptr()` extracts `*const T` by computing offset within `GcBoxInner`
- `as_ref()` returns `&'gc T` valid for the entire arena callback scope
- `write()` triggers an **unrestricted backwards write barrier** — required before adopting new child `Gc` pointers

### 2.3 The `'gc` Lifetime — Generativity / Branding

The `'gc` lifetime is **invariant** and **unique per `mutate()` callback invocation**. This is the key soundness mechanism.

```rust
// Arena::mutate signature
pub fn mutate<F, T>(&self, f: F) -> T
where
    F: for<'gc> FnOnce(&'gc Mutation<'gc>, &'gc Root<'gc, R>) -> T
```

The `for<'gc>` higher-rank bound generates a fresh, unique lifetime for each call. The borrow checker guarantees:
- `Gc<'gc, T>` pointers cannot escape the callback (the closure's return type `T` must be `'gc`-free)
- No `Gc` pointers can exist on the Rust stack outside a `mutate()` call
- Therefore, between `mutate()` calls, the GC can safely collect — no dangling pointers possible

### 2.4 The `Collect` Trait — Safe Tracing

```rust
pub unsafe trait Collect<'gc> {
    const NEEDS_TRACE: bool = true;

    fn trace<T: Trace<'gc>>(&self, cc: &mut T) {}
}
```

Safety requirements:
1. `trace()` **must** trace every `Gc<'gc, _>` and `GcWeak<'gc, _>` held in the type
2. Held GC pointers must not be accessed in `Drop` — they may be dangling at drop time
3. Internal mutability adopting new pointers must call appropriate write barriers in the same mutation

`gc-arena-derive` provides a procedural macro that generates correct `Collect` implementations for structs/enums automatically, making this safe to use without writing `unsafe impl` manually.

The `Trace<'gc>` parameter type has two methods:
- `trace_gc(&mut self, gc: Gc<'gc, ()>)` — register a strong GC pointer
- `trace_gc_weak(&mut self, gc: GcWeak<'gc, ()>)` — register a weak GC pointer

### 2.5 `Mutation<'gc>` vs `Context`

`Mutation<'gc>` is the capability token passed to arena callbacks:

```rust
pub struct Mutation<'gc> {
    context: Context,
    _invariant: Invariant<'gc>,
}
```

`Mutation` exposes: allocation of new `Gc` pointers, write barrier invocation, metrics access.

`Context` is the internal GC state manager — collection phases, gray-object queues, allocation debt tracking. Not directly accessible from safe code.

### 2.6 Incremental Mark-and-Sweep Algorithm

Collection phases (`enum Phase { Mark, Sweep, Sleep, Drop }`):

| Phase | Description |
|-------|-------------|
| **Sleeping** | Awaiting restart; allocation debt accumulates |
| **Marking** | Tracing reachable objects (tri-color algorithm) |
| **Marked** | Tracing complete; write barriers still active |
| **Sweeping** | Freeing unreachable allocations |

Algorithm is "very similar to PUC-Rio Lua 5.4's incremental collector, optimized for low pause time." Allocation debt controls how much marking/sweeping work is done per mutation. Write barriers during Mark phase prevent black objects from referencing white objects (the tri-color invariant).

### 2.7 `DynamicRootSet` — Escaping `'gc` for Async

Since async futures cannot implement `Collect` (their state is not inspectable by the GC tracer), a separate escape hatch exists:

`DynamicRootSet<'gc>` — a GC-rooted container managed via `Rc<RefCell<Slots>>`.

`DynamicRoot<R>` — a handle holding a `'static`-transmuted `Gc` pointer plus a weak back-reference to its `DynamicRootSet` for validity checking.

Unsafety is justified: the `DynamicRootSet` itself is rooted in the arena; the weak reference prevents use-after-free; verification at fetch time that handle belongs to the right set ensures pointer validity.

Used by piccolo's async callback system to hold GC values across `await` points.

### 2.8 Write Barriers

`Write<T>` — a transparent wrapper providing interiorly-mutable access to a GC-managed field:

```rust
pub struct Write<T: ?Sized> { value: T }  // transparent layout
```

Obtained through `Gc::write()` or field projection macros. Required when:
- Mutating fields inside GC-managed objects
- Adopting new `Gc` child pointers during interior mutation

`Unlock` trait — types supporting additional mutation operations when behind a write barrier. The `Unlocked` associated type provides interior mutability. The `field!` macro safely projects `Write` references to struct fields.

---

## 3. The Stackless Execution Model

### 3.1 Why Stackless?

Two intertwined motivations:

**GC safety**: `gc-arena` requires "Mutation XOR Collection" — the GC can only run when no mutation callback is executing. A deeply recursive, stack-based interpreter would only allow GC at the outermost return point. Stackless design creates natural GC points between every `step()` call.

**Embedding power**: In a stack-based embedding (PUC-Rio Lua, LuaJIT), when Lua calls Rust which calls Lua again, the chain of invocations is pinned on the C/Rust call stack. You cannot:
- Pause mid-execution and return to the event loop
- Switch to a different coroutine without OS-level stack switching
- Bound CPU time without OS threads or signals
- Yield across a C callback boundary (`lua_yield` from inside `lua_pcall` is undefined behavior in PUC-Rio)

Piccolo's stackless design makes all of these trivial.

### 3.2 The Trampoline Pattern

The fundamental execution loop, from outside the arena:

```rust
// Pseudocode of Lua::finish()
loop {
    lua.arena.mutate(|ctx, state| {
        let done = state.executor.step(ctx, &mut fuel);
        if done { break; }
    });
    // GC runs HERE — between mutate() calls
    // Can also: switch executors, check cancellation, yield to event loop
}
```

"Trampoline" = control always returns to the outer loop. No matter how deep the Lua call graph is, the Rust call stack stays shallow. `Executor::step()` processes one "step" of work and returns.

`Lua::finish()` uses 4096 fuel units per GC cycle. `Lua::execute()` wraps `finish()` and extracts typed return values outside the arena.

### 3.3 `Fuel` — Bounding Execution

```rust
pub struct Fuel {
    fuel: i32,
    interrupted: bool,
}
```

Key methods:
- `with(n: i32)` — initialize with `n` units
- `consume(n: i32)` — subtract using saturating arithmetic
- `adjust(n: i32)` — add or subtract
- `interrupt()` — signal immediate stop without consuming fuel
- `should_continue()` — returns `fuel > 0 && !interrupted`
- `refill(amount, max)` — replenish up to max, clear interrupt flag

Fuel unit ≈ one VM instruction, but variable actual cost. The VM burns fuel for:
- Each bytecode instruction: roughly 1 unit
- Each callback invocation: `FUEL_PER_CALLBACK = 8` units
- Each sequence poll step: `FUEL_PER_SEQ_STEP = 4` units
- Each outer trampoline iteration: `FUEL_PER_STEP = 4` units
- Long-running operations (table sort, string ops): `count_fuel(per_item, len)`

`Executor::step()` returns `false` when fuel exhausted but more work remains, `true` when execution is complete. This creates a cooperative yield point that the embedder controls.

`count_fuel(per_item: i32, len: usize)` — helper for O(N) operations, prevents integer overflow.

### 3.4 `Executor` — The Outer Driver

```rust
pub struct Executor<'gc>(Gc<'gc, RefLock<ExecutorState>>);
```

`ExecutorState` holds a stack of `Thread` handles — supports networks of threads yielding control back and forth.

`ExecutorMode` states:
- `Stopped` — no active threads; must restart
- `Result` — Lua completed with returnable values
- `Normal` — active thread ready for stepping
- `Suspended` — main thread yielded, awaiting resumption
- `Running` — currently inside `Executor::step()`

`step()` signature:
```rust
pub fn step(self, ctx: Context<'gc>, fuel: &mut Fuel) -> Result<bool, BadExecutorMode>
```

The trampoline loop inside `step()`:
1. Pull current thread's top frame
2. If callback frame: invoke `CallbackFn::call()`, consume `FUEL_PER_CALLBACK`
3. If sequence frame: call `Sequence::poll()`, consume `FUEL_PER_SEQ_STEP`
4. If Lua frame: run `run_vm()` for up to 64 instructions, consuming proportional fuel
5. If error frame: unwind through call stack
6. Process the `CallbackReturn`/`SequencePoll` result — push new frames or transition state
7. Check `fuel.should_continue()` — if false, return `false`
8. Repeat

Multiple executors can be multiplexed trivially:
```rust
loop {
    executor1.step(ctx, &mut fuel1);  // advance task A
    executor2.step(ctx, &mut fuel2);  // advance task B
}
```

This is genuine pre-emptive scheduling from Lua's perspective — execution is interrupted at fuel-proportional points regardless of Lua code cooperation.

---

## 4. Value Representation

### 4.1 `Value<'gc>` Enum

From `src/value.rs`:

```rust
#[derive(Debug, Copy, Clone, Collect)]
#[collect(no_drop)]
pub enum Value<'gc> {
    Nil,
    Boolean(bool),
    Integer(i64),
    Number(f64),
    String(String<'gc>),
    Table(Table<'gc>),
    Function(Function<'gc>),
    Thread(Thread<'gc>),
    UserData(UserData<'gc>),
}
```

Key properties:
- **`Copy`** — all variants are either primitive or `Gc<>` pointers (which are `Copy`)
- **`Collect` derived** — the macro auto-generates tracing for the GC-pointer variants
- **`#[collect(no_drop)]`** — opt-out of the default `Drop` constraint; values may be dropped by GC
- **`Default` = `Nil`**

`Function<'gc>` is itself an enum:
```rust
pub enum Function<'gc> {
    Closure(Closure<'gc>),
    Callback(Callback<'gc>),
}
```

`From` impls allow transparent conversion from Rust types (`bool`, `i64`, `f64`, `String<'gc>`, `Table<'gc>`, `Closure<'gc>`, `Callback<'gc>`, `Thread<'gc>`, `UserData<'gc>`) into `Value`.

`Constant<S>` represents compile-time constant values; converts to `Value` with string type parameter.

### 4.2 Instruction-Level Operand Addressing

From `src/types.rs` — newtype wrappers used in opcodes:

| Type | Wraps | Meaning |
|------|-------|---------|
| `RegisterIndex` | `u8` | Stack register relative to current frame base |
| `ConstantIndex8` | `u8` | 8-bit index into prototype constant table |
| `ConstantIndex16` | `u16` | 16-bit index into prototype constant table |
| `UpValueIndex` | `u8` | Index into closure upvalue table |
| `PrototypeIndex` | `u8` | Index into nested prototype list |
| `VarCount` | `Opt254` | Argument/return count; 255 = "variable" |
| `Opt254` | `u8` | `Option<u8>` packed into one byte |

Binary operations have four variants — `RR`, `RC`, `CR`, `CC` — encoding whether each operand is a register or constant, avoiding runtime dispatch on operand kind.

---

## 5. Opcode Set

From `src/opcode.rs`. Grouped by category:

**Data movement**: `Move`, `LoadConstant`, `LoadBool`, `LoadNil`

**Arithmetic** (each with `RR`/`RC`/`CR`/`CC` variants): `Add`, `Sub`, `Mul`, `Div`, `IDiv`, `Mod`, `Pow`, `Minus` (unary)

**Bitwise** (same 4-variant pattern): `BitAnd`, `BitOr`, `BitXor`, `ShiftLeft`, `ShiftRight`, `BitNot` (unary)

**Comparison** (with skip-next semantics, 4-variant): `Eq`, `Less`, `LessEq`

**Table ops**: `NewTable`, `GetTable`/`GetTableR`/`GetTableC`, `SetTable`/`SetTableRR`/etc., `GetUpTable`/`GetUpTableR`/etc., `SetUpTable`/etc., `SetList`, `Length`

**Control flow**: `Jump`, `Test`, `TestSet`, `NumericForPrep`, `NumericForLoop`, `GenericForCall`, `GenericForLoop`

**Functions**: `Call`, `TailCall`, `Return`, `Closure`, `VarArgs`

**Upvalues**: `GetUpValue`, `SetUpValue`

**Methods**: `Method`/`MethodR`/`MethodC` (load method + self onto stack)

**String**: `Concat`

The `RR`/`RC`/`CR`/`CC` suffix encoding eliminates runtime operand-type branches in the dispatch loop hot path.

---

## 6. Thread / Fiber Architecture

### 6.1 `Thread<'gc>` — Coroutine Unit

```rust
pub struct Thread<'gc>(Gc<'gc, RefLock<ThreadState<'gc>>>);
```

`ThreadMode` states:
- `Stopped` — idle, ready to start a function
- `Normal` — active, has frames on stack
- `Suspended` — yielded, awaiting `resume()`
- `Waiting` — blocked waiting for another thread's completion
- `Running` — a callback/sequence owned by this thread is executing
- `Result` — has error or return values ready to be collected

### 6.2 Thread Stack Frames

The thread maintains a **single shared value stack** across all frames. Each frame type records its bottom index:

**`LuaFrame`** — contains:
- Program counter (`pc`)
- Base register offset within the stack
- Reference to `Closure<'gc>` (prototype + upvalues)
- Variable argument list

**`CallbackFrame`** — single synchronous Rust function call

**`SequenceFrame`** — holds a `BoxSequence<'gc>` (heap-allocated, `Pin`ned `Sequence` trait object)

**Control frames** (no stack content): `Start`, `Yielded`, `WaitThread`, `Error`, `Result`

### 6.3 Yield Mechanism

Yield pushes a `Yielded` frame marker onto the thread's frame stack, transitions thread to `Suspended` mode. Stack values are preserved in place. `Thread::resume()` pops the `Yielded` marker and calls `return_to()`, restoring execution. No stack unwinding, no `setjmp`/`longjmp`, no OS-level context switch.

This is why `coroutine.yield` works from arbitrary callback depth in piccolo — it's just a mode transition on the thread state machine.

### 6.4 `coroutine.yieldto` — Symmetric Coroutines

PUC-Rio Lua cannot implement true symmetric coroutines without a stack leak:

```lua
-- PUC-Rio: resumer frame stays on C stack until co completes
function yieldto(co)
    coroutine.yield(coroutine.resume(co))
end
```

Piccolo's `CallbackReturn::Yield` with a target thread is a direct state machine transition — no intermediate frame needed. The resumer's call frame is not pinned to any Rust stack frame, so yielding to another coroutine costs O(1) with no accumulation.

---

## 7. Sequence / Callback Traits

### 7.1 `CallbackFn` Trait

Immediate, synchronous Rust functions callable from Lua:

```rust
pub trait CallbackFn<'gc>: Collect {
    fn call(
        &self,
        ctx: Context<'gc>,
        exec: Execution<'gc, '_>,
        stack: Stack<'gc, '_>,
    ) -> Result<CallbackReturn<'gc>, Error<'gc>>;
}
```

`CallbackReturn<'gc>` variants:
- `Return` — push stack values, return to caller
- `Sequence(BoxSequence)` — hand off to a `Sequence` for multi-step work
- `Call { function, then: Option<BoxSequence> }` — invoke Lua function, optionally collect result into sequence
- `Yield { to: Option<Thread>, then: Option<BoxSequence> }` — yield current coroutine
- `Resume { thread, then: Option<BoxSequence> }` — resume another thread

### 7.2 `Sequence` Trait

Multi-step async-like operations:

```rust
pub trait Sequence<'gc>: Collect {
    fn poll(
        self: Pin<&mut Self>,
        ctx: Context<'gc>,
        exec: Execution<'gc, '_>,
        stack: Stack<'gc, '_>,
    ) -> Result<SequencePoll<'gc>, Error<'gc>>;

    fn error(
        self: Pin<&mut Self>,
        ctx: Context<'gc>,
        exec: Execution<'gc, '_>,
        error: Error<'gc>,
        stack: Stack<'gc, '_>,
    ) -> Result<SequencePoll<'gc>, Error<'gc>> {
        Err(error)  // default: propagate
    }
}
```

`SequencePoll<'gc>` variants:
- `Pending` — continue polling next step
- `Call { function, then: Option<BoxSequence> }` — invoke Lua function
- `Yield { to: Option<Thread>, then: Option<BoxSequence> }` — yield
- `Resume { thread, then: Option<BoxSequence> }` — resume thread
- `TailCall` / `TailYield` / `TailResume` — terminal variants (sequence finishes)

The `Sequence` is `Pin<&mut Self>` — it's owned by the `Thread` and never moved. Implements `Collect` — the GC traces any `Gc` pointers held in its state.

Manually implementing `Sequence` requires writing an explicit state machine. For complex multi-step operations this is painful — async sequences solve this.

### 7.3 `Callback<'gc>` — GC-Managed Callback Handle

```rust
pub struct Callback<'gc>(Gc<'gc, CallbackInner<'gc>>);
```

Constructed via:
- `Callback::from_fn()` — `'static` closure (no GC capture)
- `Callback::from_fn_with()` — closure capturing GC objects (provided as separate arg to bound lifetimes correctly)
- `Callback::new()` — generic, takes any `CallbackFn` implementation

Uses vtable dispatch (`CallbackInner` holds a trait object pointer).

---

## 8. Async Sequences — The Shadow Stack

### 8.1 The Problem

Rust async blocks (`async { ... }`) cannot implement `Collect` — the compiler-generated future state machine is opaque; the GC tracer cannot walk it to find `Gc<>` pointers. But writing manual `Sequence` state machines for complex multi-step Lua callbacks is extremely painful.

### 8.2 `async_sequence` — The Solution

`async_sequence` wraps a Rust `async` block into a `Sequence` implementation:

```rust
pub fn async_sequence<'gc, F, G>(ctx: Context<'gc>, f: F) -> BoxSequence<'gc>
where
    F: FnOnce(AsyncSequence) -> G,
    G: Future<Output = Result<SequencePoll<'gc>, Error<'gc>>> + 'gc,
```

The async block writes code in natural Rust async style, calling back into Lua via `AsyncSequence::call()`, `yield_to()`, `resume()` methods.

### 8.3 `SharedSlot` — The Shadow Stack Mechanism

Inside the generated `SequenceImpl`, a `SharedSlot` holds a raw pointer to the current `(Context, Execution, Stack)` triple. When `poll()` is called on the outer `Sequence`:

1. Store current `(ctx, exec, stack)` in `SharedSlot`
2. Poll the inner future
3. The future's `await` points call `AsyncSequence::enter(|ctx, exec, stack| { ... })`
4. `enter()` reads from `SharedSlot` and exposes the resources to the callback closure
5. Between `enter()` calls, `SharedSlot` is empty — no `'gc` references exist in the future's state

The future never stores `Gc<'gc, T>` directly — they are borrowed ephemerally within `enter()` closures.

### 8.4 `Locals<'gc, 'a>` — Stashing Across Await Points

When the async block needs to hold a GC value across an `await` point, `Locals` provides a per-sequence stash:

```rust
// Inside async sequence:
let my_val: StashedValue = locals.stash(&ctx, some_gc_value);
some_await_point.await;
// After resumption:
let recovered: Value<'gc> = locals.fetch(&ctx, &my_val);
```

`Locals::stash()` converts `Value<'gc>` → `StashedValue` (using `DynamicRootSet` owned by the sequence). `Locals::fetch()` reconverts using the same root set. The phantom `'a` lifetime prevents `Locals` from escaping the async block.

`DynamicRootSet` is included in the `Sequence`'s `Collect` implementation, so stashed values are visible to the GC tracer. The async future's raw bytes are not traced — only the shadow stack root set.

Primitive values (`Nil`, `Boolean`, `Integer`, `Number`) are stored directly in stashed form without GC involvement.

---

## 9. Source File Map

| File | Content |
|------|---------|
| `src/value.rs` | `Value<'gc>` enum, From impls, StaticValue |
| `src/types.rs` | `RegisterIndex`, `ConstantIndex8/16`, `UpValueIndex`, `VarCount`, `Opt254`, `UpValueDescriptor` |
| `src/opcode.rs` | Full `OpCode` enum with all instruction variants |
| `src/fuel.rs` | `Fuel` struct, consumption/interrupt API |
| `src/callback.rs` | `CallbackFn`, `Callback`, `Sequence`, `CallbackReturn`, `SequencePoll` |
| `src/async_callback.rs` | `async_sequence`, `AsyncSequence`, `Locals`, `SharedSlot` pattern |
| `src/stash.rs` | `Stashable`, `Fetchable` traits, stashed type aliases |
| `src/closure.rs` | `Closure`, `FunctionPrototype`, `UpValue`, `UpValueState` |
| `src/thread/mod.rs` | Re-exports from executor, thread, vm submodules |
| `src/thread/executor.rs` | `Executor`, `ExecutorMode`, `Execution`, `step()` trampoline |
| `src/thread/thread.rs` | `Thread`, `ThreadMode`, frame types, yield/resume |
| `src/thread/vm.rs` | `run_vm()` dispatch loop, opcode execution |
| `src/lua.rs` | `Lua`, `enter()`, `finish()`, `execute()` |
| `src/string.rs` | Interned GC string type |
| `src/table/` | Hash table implementation (uses hashbrown internals) |
| `src/userdata.rs` | `UserData<'gc>`, safe downcasting |
| `src/error.rs` | `Error<'gc>`, `VMError`, `RuntimeError`, etc. |
| `src/registry.rs` | `Registry` for global value storage |
| `src/meta_ops.rs` | Metamethod dispatch |

gc-arena source:

| File | Content |
|------|---------|
| `src/gc.rs` | `Gc<'gc, T>` struct, write barrier |
| `src/collect.rs` | `Collect` trait, `Trace` trait |
| `src/arena.rs` | `Arena<R>`, `mutate()`, `CollectionPhase` |
| `src/context.rs` | `Context`, `Mutation<'gc>` |
| `src/dynamic_roots.rs` | `DynamicRootSet`, `DynamicRoot` |
| `src/barrier.rs` | `Write<T>`, `Unlock` trait, field projection |
| `src/gc_weak.rs` | `GcWeak<'gc, T>` for weak references |
| `src/metrics.rs` | Allocation tracking, debt computation |

---

## 10. Closure and Prototype Internals

`FunctionPrototype<'gc>` fields:

| Field | Type | Purpose |
|-------|------|---------|
| `chunk_name` | `String<'gc>` | Source file identifier |
| `reference` | source reference | Debug/error location |
| `fixed_params` | `u8` | Required parameter count |
| `has_varargs` | `bool` | Accepts `...` |
| `stack_size` | `u16` | Register count needed |
| `constants` | `Vec<Constant<String<'gc>>>` | Constant pool |
| `opcodes` | `Vec<OpCode>` | Bytecode |
| `opcode_line_numbers` | `Vec<u32>` | Debug line map |
| `upvalues` | `Vec<UpValueDescriptor>` | Capture descriptors |
| `prototypes` | `Vec<Prototype<'gc>>` | Nested functions |

`UpValueDescriptor` variants:
- `Environment` — captures `_ENV` (the global table)
- `ParentLocal(RegisterIndex)` — captures a register from the enclosing function
- `Outer(UpValueIndex)` — closes over an upvalue from the enclosing closure (upvalue-of-upvalue)

`UpValue<'gc>` states:
- **Open** — `OpenUpValue` pointing to a live stack slot; multiple closures sharing the same open upvalue reference the same slot
- **Closed** — value copied out of stack when scope ends; stored in `Lock<Value<'gc>>` inside the upvalue object

---

## 11. Lessons for a Clean Scala VM Implementation

Piccolo's design encodes several transferable insights about VM execution models that don't rely on the host call stack.

### 11.1 Reify the Call Stack as a Data Structure

PUC-Rio Lua's call frames live on the C stack — they are implicit in the C runtime's activation records. Piccolo makes frames explicit:

```
ThreadState {
    frames: Vec<Frame>,  // LuaFrame | CallbackFrame | SequenceFrame | ...
    values: Vec<Value>,  // single contiguous value stack
}
```

Each `LuaFrame` stores its own program counter, base register offset, and closure reference. The VM loop runs exactly one frame at a time, returning to the trampoline when a Call/Return/Yield opcode is encountered.

**Scala lesson**: A JVM-based Lua (or similar) VM should maintain its own `ArrayDeque[Frame]` and `Array[Value]` rather than mapping Lua frames to JVM stack frames. This is the fundamental structural decision — everything else follows.

### 11.2 Break on Control Transfer Opcodes

Piccolo's `run_vm()` loop executes instructions until it hits `Call`, `TailCall`, `Return`, `GenericForCall`, or any metamethod-triggering operation. At that point it **breaks** out of the loop and returns a signal to the trampoline.

The trampoline processes the signal (push new frame, pop frame, transition thread state) and calls `run_vm()` again on the new top frame.

**Scala lesson**: The inner bytecode loop should only handle straight-line execution. Control-transfer instructions should `return` a `StepResult` algebraic type. The outer `step()` method handles state transitions. Clean separation of "execute instructions" from "manage call graph."

### 11.3 Fuel / Instruction Budget as First-Class Parameter

`Executor::step(ctx, fuel: &mut Fuel)` — fuel is a mutable reference passed through every layer. Callbacks consume it. The loop checks `fuel.should_continue()` before each iteration.

**Scala lesson**: Pass a mutable `Fuel` or `Budget` object through the execution hierarchy. Don't use global state or thread-locals. This enables per-executor budgets and precise DoS prevention without OS-level mechanisms.

### 11.4 Callbacks Return Instructions, Not Results

`CallbackReturn` / `SequencePoll` are not "results of computation" — they are **instructions to the executor**:
- `Call { function, then }` — "please invoke this function, then give results to `then`"
- `Yield { to, then }` — "please suspend this coroutine (optionally targeting another)"
- `Resume { thread, then }` — "please resume this thread"

The executor obeys these instructions. Callbacks never directly invoke other Lua functions — they request the executor to do it.

**Scala lesson**: Define a `StepSignal` sealed trait (or enum) for all control transfers. Callbacks return `StepSignal`, never call into the VM directly. This keeps the call graph depth O(1) in the host language regardless of Lua call depth.

### 11.5 Thread / Fiber as State Machine

`Thread` is a state machine with explicit modes (`Stopped`, `Normal`, `Suspended`, `Waiting`, `Running`, `Result`). Yield is a mode transition, not a stack unwind. Resume is a mode transition, not a function call.

**Scala lesson**: Model coroutines as a `sealed trait ThreadMode` or `enum`. Yield = `mode = Suspended; return`. Resume = `mode = Normal; trampoline.step()`. No need for `Thread.interrupt()`, `Fiber`, or JVM virtual threads for the scripting VM's coroutine semantics.

### 11.6 Async Sequences = Free State Machines for Embedders

The `async_sequence` / `Sequence` duality lets embedders write natural sequential code (the async block) while the compiler generates the state machine. The shadow stack / `Locals` pattern shows how to bridge a GC system that needs explicit tracing with opaque async state.

**Scala lesson**: a callback/continuation abstraction can play the same role for complex callback chains — a `Sequence` that returns `StepSignal` steps which the trampoline drives to completion.

### 11.7 Separation of Mutation and Collection

gc-arena's "Mutation XOR Collection" forced the stackless design. The invariant is: **no GC-managed pointers exist on the host stack between steps**. Collection can occur freely at step boundaries.

**Scala lesson**: For a GC-backed value representation in Scala (using a managed object pool or off-heap arena), arrange for all host-stack references to GC'd values to be released before triggering a collection cycle. Explicit handles (`StashedValue` style) that are rooted in a registry, not on the call stack, enable this.

### 11.8 Pre-emptive Scheduling is Round-Robin at Step Level

```rust
loop {
    for executor in &mut executors {
        executor.step(ctx, &mut fuel);
    }
}
```

Each executor represents an independent concurrent task. Fairness is achieved by step-level interleaving, not OS threads. The fuel parameter controls granularity.

**Scala lesson**: For sandboxed multi-script environments, maintain a `Queue[Executor]` and round-robin `step()` calls. No JVM threading overhead. Lua concurrency emerges from scheduling policy, not OS primitives.

---

## 12. Summary of Design Invariants

| Invariant | Mechanism |
|-----------|-----------|
| GC pointers never outlive mutation callback | Invariant `'gc` lifetime, HRTB `for<'gc>` |
| All GC-reachable objects traced | `Collect` trait + derive macro |
| No dangling pointers from dropped GC objects | `no_drop` + prohibition on GC access in `Drop` |
| Pointer mutation preserves tri-color invariant | `Write<T>` + explicit write barrier |
| Host stack depth O(1) regardless of Lua nesting | Explicit frame stack; break-on-control-transfer dispatch |
| Execution bounded in time | `Fuel` parameter; checked every instruction group |
| Coroutine yield is O(1), no stack unwinding | `Thread` state machine; `Yielded` frame marker |
| GC values safe across async await points | `DynamicRootSet` + `Locals` shadow stack |
| Collection only at safe points | Between `Arena::mutate()` calls |

---

## Sources

- [GitHub - kyren/piccolo](https://github.com/kyren/piccolo)
- [piccolo/README.md](https://github.com/kyren/piccolo/blob/master/README.md)
- [Piccolo - A Stackless Lua Interpreter (kyju.org)](https://kyju.org/blog/piccolo-a-stackless-lua-interpreter/)
- [GitHub - kyren/gc-arena](https://github.com/kyren/gc-arena)
- [Rust Forum Announcement](https://users.rust-lang.org/t/piccolo-stackless-lua-vm-implemented-in-pure-rust/104720)
- [Lobsters Discussion](https://lobste.rs/s/64s62v/piccolo_stackless_lua_interpreter)
- [docs.rs/piccolo](https://docs.rs/piccolo/latest/piccolo/)
- Source files: `src/value.rs`, `src/callback.rs`, `src/fuel.rs`, `src/async_callback.rs`, `src/stash.rs`, `src/closure.rs`, `src/types.rs`, `src/opcode.rs`, `src/lua.rs`, `src/thread/executor.rs`, `src/thread/thread.rs`, `src/thread/vm.rs`
- gc-arena source files: `src/collect.rs`, `src/gc.rs`, `src/arena.rs`, `src/context.rs`, `src/dynamic_roots.rs`, `src/barrier.rs`
