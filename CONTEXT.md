# Luau-Scala

A cross-platform embedding of the upstream Luau runtime, driven from Scala 3. Runs on the JVM (via Panama FFM) and in JS (via Luau compiled to WASM). Execution is task-based: the Host never makes protected calls into Luau; it only resumes and yields.

## Language

**Runtime**:
The upstream Roblox Luau engine (C++), embedded as-is. We do not fork or reimplement its semantics, lexer, compiler, or VM.
_Avoid_: VM, engine, interpreter (when referring to the whole thing).

**Host**:
The Scala 3 side that embeds the **Runtime** — loads scripts, drives execution, registers callbacks, marshals values.
_Avoid_: client, embedder (as a noun), driver.

**Shim**:
The slim C++ layer compiled directly against the Luau C API. Exposes a narrow, task-shaped ABI and guarantees the native stack never unwinds across the FFI boundary. Compiled once to a native library and once to WASM from the same source.
_Avoid_: wrapper, glue, binding (use "Shim" for the C++ artifact specifically).

**Binding backend**:
The platform-specific Scala code that calls the **Shim**'s ABI. Two exist: the **Panama backend** (JVM, `java.lang.foreign`) and the **WASM backend** (JS, Scala.js interop).
_Avoid_: FFI layer, adapter.

**Coroutine**:
The Luau-native thread primitive (`coroutine.create/resume/yield/wrap/status`), exposed to scripts as-is per Roblox parity. It is the *substrate*: a **Task** is a Coroutine the **Scheduler** owns and drives. A Coroutine resumed by a script (not the Scheduler) yields to that script, never to the Scheduler — which is what keeps the two layers cleanly separated.
_Avoid_: green thread, fiber.

**Task**:
A **Coroutine** the **Scheduler** owns and drives via resume/yield, plus Host-side scheduling state the raw Coroutine lacks. Runs until it yields, returns, or errors; the **Scheduler** then parks it. Whatever the script registered before yielding (a timer, a child Task, an **Async primitive** completion) decides if and when it wakes.
_Avoid_: coroutine (reserve for the raw primitive), fiber, job.

**Task library**:
The Roblox-style `task` API (`task.spawn`, `task.defer`, `task.wait`, `task.delay`, …) — Host code built *on top of* the **Coroutine** substrate plus the **Scheduler**. Its await paths yield through Luau's continuation (`k`) form so a suspension survives an enclosing script `pcall` or yielding metamethod.
_Avoid_: scheduler API.

**Scheduler**:
The Host loop that drains the **Run queue**: pop a ready **Task**, `lua_resume` it, park it on yield/return/error. Single-threaded for now (one **Driver**). It never inspects a yield payload to decide rescheduling — side effects the script registered before yielding do that.
_Avoid_: event loop, dispatcher, runtime.

**Resume boundary**:
The single sanctioned entry point for executing Luau code from the **Host**. All Luau code runs inside a resume so that errors return as a status rather than `longjmp`-ing across the FFI boundary.
_Avoid_: call, invoke, pcall.

**Ref**:
A stable **Host**-held handle to a Luau-heap object (registry reference). Lets the **Host** reference Luau-owned tables and functions across **Resume boundary** crossings without keeping them on the stack. (Refs point Host→Luau only; host objects never cross the other way by reference — see **Codec**.) `AutoCloseable`: released **only** by explicit `close()`, by exiting the **Scope** that owns it, or by tearing down the state — never by a GC finalizer. The idiomatic owner is a Scala `Resource` (cats-effect `Resource` / ZIO `Scoped`) or `Using`; `core` keeps Ref as bare `AutoCloseable` and lets the effect modules wrap it. A leaked Ref pins its Luau object until the state closes.
_Avoid_: pointer, handle (use "Ref"), reference.

**Codec**:
The `LuauEncoder[A]` / `LuauDecoder[A]` typeclass pair that defines how a Scala type lowers to / lifts from a Luau value (primitive · string · array · table). Only types with an encoder may cross **Host → Luau**, enforced at compile time (`push[A: LuauEncoder]`). Crossing always **copies**: Luau owns its copy, the Host owns the original, nothing is shared. Encoders write to a **Sink** (stream push) rather than building an intermediate tree, to stay single-copy.
_Avoid_: serializer, marshaller, converter.

**Sink**:
The streaming push target an encoder writes into (begin-table / key / value / end-table, push-primitive, push-string). Backend-agnostic so encoders live in `core`; the **Panama backend** and **WASM backend** implement it over Shim push ops.
_Avoid_: builder, writer, emitter.

**Scope**:
A confined region that owns the **Ref**s opened inside it and closes them all on exit (`state.scoped { … }`). The everyday, deterministic way to release Refs without per-Ref bookkeeping. On the **Panama backend** it is a `java.lang.foreign.Arena`.
_Avoid_: arena (except when naming the JVM impl), region, pool.

**Suspension**:
What a **Task** produces when it yields to the **Host**: a request describing why it parked (await a value, sleep, spawn). The native C stack is fully unwound at this point — the **Task** is stackless while parked. The **Host** completes the request, then resumes the **Task** with the result or an error.
_Avoid_: pause, await, blocking.

**Async primitive**:
The callback-based completion model in `core`: a **Native function** suspends by handing back `Suspend(register)`, where `register: Resume => Cancel` wires an async op against a one-shot, thread-safe `resume` callback and returns a `Cancel` for teardown. Effect-system-agnostic — it is the bedrock ZIO/CE adapters build on (their `async`/`asyncInterrupt` are already callback-shaped); a declarative `Future`/`F[A]` form layers on top. Calling `resume` only *enqueues* onto the **Run queue**; it never resumes inline.
_Avoid_: effect, IO, monad, Future (in `core`).

**Native function**:
A Scala function exposed to scripts, reached via the **Shim** trampoline upcall. Returns one of three outcomes: `Return(n)` (n results pushed), `Fail(value)` (the Shim raises it in pure C — ADR-0001), or `Suspend(register)` (the Shim yields the **Task** in pure C — see **Async primitive**). Reads its arguments only with non-raising accessors.
_Avoid_: C function, callback (use "Native function"), foreign function.

**Driver**:
The serial execution context that owns a Luau state. NOT a fixed OS thread — it is whichever pool worker currently holds the state, and exactly one holds it at a time. `lua_resume` runs only on the current **Driver**. State migrates across pool workers via the **Run queue**; the queue handoff (release/acquire) establishes the happens-before edge that makes the migration safe, including for the state's native memory.
_Avoid_: scheduler thread, pinned thread, owner thread.

**Run queue**:
The concurrent queue a state flows through between resumes. A state is either parked in the queue (owned by no worker) or checked out by exactly one **Driver**. **Async primitive** completions enqueue a resume here rather than running inline. Serves as both the work distributor and the memory-handoff fence.
_Avoid_: mailbox, channel, executor.

**Isolate**:
An independent Luau state with its own `global_State`, scheduled as its own actor. Parallelism across cores comes from running many **Isolate**s on a shared worker pool — never from sharing one state across threads. **Isolate**s share no Lua objects; they communicate by copying/message-passing only.
_Avoid_: VM instance, sandbox, context.

## Flagged ambiguities

- **"Coroutine" vs "Task"** — a **Coroutine** is the raw Luau primitive (exposed to scripts). A **Task** is a Coroutine the **Scheduler** owns plus its scheduling state. Every Task is backed by a Coroutine; not every Coroutine is a Task (a script can create its own, and the Scheduler ignores it). Use the precise term.
- **"pcall"** — Luau's `pcall` (the Lua stdlib function, still available to scripts) vs. the C-level `lua_pcall` protected call (banned at the FFI boundary). Scripts may still call `pcall`; the **Host** never calls `lua_pcall`.

## Behavioral spec

**Roblox Luau is the reference.** When a runtime-visible behavior is ambiguous, match Roblox — including its quirks (e.g. `task.wait` inside a script-created **Coroutine** behaves as Roblox's does). Deviations must be deliberate and recorded as ADRs.

## Example dialogue

> **Dev**: When a script calls a Scala function and it fails, does it just `error()`?
> **Expert**: The Scala side can't raise — it can't `longjmp` back through the JVM frame. It returns an error result to the **Shim**, and the **Shim** raises it in pure C.
> **Dev**: And that error — does it crash the **Task**?
> **Expert**: It propagates up the Luau thread like any error. The **Resume boundary** turns it into an error *status* handed back to the **Host** scheduler. The scheduler decides whether the parent **Task** sees it or the whole **Task** dies.
> **Dev**: So the **Host** never wraps anything in `lua_pcall`?
> **Expert**: Never. Scripts can still use `pcall` internally — that's Lua-side, stays on the Lua stack. The ban is only on the C **Resume boundary**.
