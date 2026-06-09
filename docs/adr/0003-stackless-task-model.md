# Stackless Task model

A **Task** is a unit of Luau execution the Host drives via resume/yield, backed by a Luau thread. When a Task awaits something, it **yields all the way out** of `lua_resume`: the native C stack unwinds completely back to the Host, leaving the Lua-level continuation (stack arrays, pc, `CallInfo`) intact in the heap. The Host parks the Task, registers an **Async primitive** completion, and calls `lua_resume` again when the result is ready. Nothing native is held across a suspension.

We chose this over the thread-per-coroutine model (one OS thread parked per suspended Task, as LuaJ does).

## Considered Options

- **Thread-per-coroutine** — a parked Task = a blocked OS thread holding its continuation in the thread stack. Rejected: ~1 MB stack per Task (catastrophic at scale — see the SwitchCraft 250k-thread crash in `docs/research/topic-coroutines-on-jvm.md`), and the continuation is pinned to one thread.
- **Loom virtual threads** — JVM-only, nonexistent on JS/WASM. Rejected for breaking platform parity.

## Consequences

- **Tasks are migratable** — a parked Task is pure heap data, so it can move across worker threads (ADR-0002 depends on this).
- **`setjmp`/`longjmp` stays within a single resume** — the error-jump buffer is only live during an in-progress `lua_resume` on one thread, never persisted across a yield. This is what keeps the no-`pcall`-across-the-boundary rule (ADR-0001) sound.
- **Host callbacks that need to await must yield, not block** — a Scala function reached via upcall cannot park the Task itself (it has no continuation to hold); it requests a suspension and the Task yields. Blocking the upcall would pin the Driver thread and defeat migration.
- **Identical execution model on JVM and JS** — no platform-specific coroutine primitive.
