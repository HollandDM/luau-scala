# Coroutine substrate, Task library on top, single-threaded Scheduler

We model script execution on the Luau **Coroutine** primitive (exposed to scripts, per Roblox parity) and build the Roblox **Task library** on top of it plus a single-threaded **Scheduler**. This mirrors how Roblox itself works and resolves the host-yield-vs-script-yield disambiguation for free: coroutine resume/yield nests, so the Scheduler only ever observes a yield from a thread it resumed. Script-created coroutines yield to their own resumer and the Scheduler never sees them.

The Scheduler does not interpret yield payloads. It drains a ready-queue: pop a Task, `lua_resume`, park on yield/return/error. Rescheduling is driven entirely by side effects a script registers *before* yielding — `task.wait` enqueues a timer, `task.spawn` pushes a thread, an **Async primitive** await registers a completion that re-enqueues the Task, a bare `coroutine.yield` registers nothing and simply parks.

**Roblox Luau is the behavioral spec.** Ambiguous runtime behavior matches Roblox, quirks included — e.g. `task.wait` inside a script-created coroutine behaves as Roblox's does (accepted and documented, not "fixed").

## Considered Options

- **Remove `coroutine`, expose only `task`** (a prior direction) — rejected: breaks upstream-Luau and Roblox parity, and discards the substrate that `task` is naturally built on. Removing it bought nothing once nesting solved disambiguation.
- **Tag host-await yields with a sentinel** — unnecessary once coroutine nesting + register-before-yield handles disambiguation.

## Consequences

- **Single-threaded MVP.** One Driver drains the Run queue. Cross-core parallelism via Isolates (ADR-0002) is deferred; that ADR's visibility caveat does not apply while single-threaded.
- **Off-Driver completions still enqueue.** An async-IO `Future` completing on another thread posts a resume onto the single Driver rather than resuming inline.
- **Stackless still required** (ADR-0003): Luau coroutines do not switch the C stack, so a parked Task is heap data — consistent with the model and with the future migration path.
- **Known Roblox-ism inherited:** `task.wait`/`task.*` inside a raw script coroutine reschedule against the wrong resumer. Documented, not worked around, for MVP.
