# Deterministic Ref lifetime, no GC finalizer

A **Ref** pins a Luau object in the registry so Luau's GC won't collect it while the Host holds it; it must be `luaL_unref`'d or the object leaks. We release Refs **only** explicitly — `Ref` is `AutoCloseable`, owned by a Scala `Resource` (cats-effect `Resource` / ZIO `Scoped`), `Using`, or a **Scope** that closes everything it owns on exit. We deliberately do **not** use `java.lang.ref.Cleaner` (JVM) or `FinalizationRegistry` (JS) as a backstop, even though both are available.

The JS engine's GC cannot trace into Luau's heap regardless: Luau objects live in opaque WASM linear memory with their own collector, so "link GC to JS native" can only mean firing a handle-release callback — which is exactly the non-deterministic mechanism we are rejecting.

## Considered Options

- **Explicit close + GC backstop** — finalizer unrefs anything the caller forgot. Rejected: reintroduces non-determinism and unbounded pinning (`FinalizationRegistry` may never fire), for a safety net `Resource`/`Scope` already provide deterministically.
- **GC finalizer only** — rejected outright: non-deterministic memory growth, no determinism guarantee.

## Consequences

- **A forgotten Ref leaks until the owning state/Isolate is torn down** — bounded by state lifetime, not process lifetime. Short-lived Isolates cap the blast radius.
- **Ergonomics come from Scope/Resource, not GC.** `state.scoped { … }` (on the Panama backend, a `java.lang.foreign.Arena`) closes all Refs opened inside it. Manual `close()` remains for Refs that outlive a scope.
- **A dev-mode leak detector** tracks open Refs with allocation sites and reports any still open at state teardown — a diagnostic, not a release path, so explicit-only holds.
- **All unrefs route through the Scheduler/Run queue** (a close may be requested off-Driver); a close after state teardown is a no-op.
