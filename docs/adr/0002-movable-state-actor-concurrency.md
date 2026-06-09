# Movable-state actor concurrency

> **Status: deferred.** The MVP runs single-threaded (one Driver draining the Run queue — see ADR-0004). This ADR records the *planned* path to multi-core parallelism, not current behavior. While single-threaded, the native-memory-visibility caveat below does not apply (one thread = trivially ordered). Revisit when parallelism is needed.

A Luau state is not thread-safe: its `global_State` holds the GC worklists, string intern table, allocator, and weak-table lists, all mutated without locking, and the incremental GC runs write barriers on whatever thread drives the state. Concurrent access corrupts the heap. We refuse to fork the engine to make a single state parallel-safe — that means rewriting GC/allocator/interning (rejected, and it would buy safety, not parallelism, since one thread is inside the VM at a time regardless).

Instead each state is an **actor**. A state flows through a concurrent **Run queue**: a shared worker pool pulls it, runs exactly one `lua_resume`, and pushes it back. Exactly one **Driver** (the current pool worker) holds a state at any instant, but the state migrates across workers over its life. Parallelism across cores comes from running many **Isolate**s (independent states) on the pool — never from sharing one state. Isolates share no Lua objects (`lua_xmove` only works within one `global_State`); they message-pass by copying.

This is only possible because Tasks are **stackless** (see ADR-0003): a parked state holds no native stack and no live `setjmp` buffer — it is pure heap data, so migrating it is moving a pointer across a memory fence. A stackful coroutine (thread-per-coroutine) could never migrate; its continuation lives in an OS thread stack.

## Consequences

- **Visibility rides the queue handoff, not manual flushing.** Caches are coherent on mainstream CPUs; the hazard is reordering. A `java.util.concurrent` queue's `put` (release) / `take` (acquire) establishes happens-before between a worker finishing a resume and the next worker starting one. No manual cache flush.
- **Native-memory caveat.** The state lives in off-heap native memory mutated by C++, which the JMM does not formally model. In practice the release/acquire compiles to CPU fences that order all memory (Java and native). Correct on HotSpot; not guaranteed by the letter of the JLS. Do not "optimize away" the fence or run a resume off the **Run queue**.
- **Off-Driver completions enqueue, never resume inline.** An **Async primitive** may complete on any thread (a ZIO fiber, an async-IO pool, a JS microtask); it posts a resume onto the **Run queue** instead of calling `lua_resume` directly.
- **JS divergence is throughput-only.** JS has one thread; cross-worker migration would need a `SharedArrayBuffer`-backed WASM linear memory + `Atomics`, gated behind COOP/COEP headers that are often unavailable. JS therefore drains the queue on a single worker. Same API, same semantics, less parallelism.
