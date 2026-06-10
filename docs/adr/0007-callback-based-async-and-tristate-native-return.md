# Callback-based async primitive and tri-state Native-function return

A **Native function** reached via the Shim trampoline returns one of three outcomes, and the Shim acts on each in pure C (so nothing unwinds across the FFI boundary):

```
Return(nResults)   → trampoline returns N to Luau
Fail(value)        → trampoline calls lua_error      (ADR-0001)
Suspend(register)  → trampoline calls lua_yield(k); Task parks
```

The async model in `core` is **callback-based**, not Future-based:

```scala
type Resume = Either[LuaError, Result] => Unit
type Cancel = () => Unit
final case class Suspend(register: Resume => Cancel)
```

`register` wires the async op against the one-shot `resume` callback and returns a `Cancel`. We chose callbacks as the bedrock because they are the lowest common denominator — a declarative `Future`-shaped form can be layered on top without changing the kernel.

## Consequences

- **`resume` is one-shot and thread-safe by construction.** It only enqueues a resume onto the Run queue (never resumes inline), so completion may fire on any thread. A second call no-ops (dev-mode throws) — a double-resume would resume a Task twice and corrupt its coroutine.
- **Cancellation is first-class.** `Cancel` fires when the Task or state is torn down before completion (abort the in-flight HTTP call, cancel the timer).
- **The trampoline ABI carries a yield outcome**, not just ok/err — fixed early because changing the Shim↔backend return protocol later is expensive.
- **A native author cannot `lua_yield` or `lua_error` directly** — only the Shim does, in C. The native fn just describes the outcome.
