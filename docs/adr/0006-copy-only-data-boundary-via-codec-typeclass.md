# Copy-only Host↔Luau data boundary via a Codec typeclass

Host data crosses into Luau only by **copy**, and only for types that have a `LuauEncoder[A]` instance (with `LuauDecoder[A]` for the return trip). Enforced at compile time: `push[A: LuauEncoder]` won't compile for a non-convertible type. A value lowers to the Luau value set — primitive, string, array, table — and Luau then owns that copy. Host objects never cross by reference: there is no userdata wrapping a host object.

We chose a typeclass over a marker trait so primitives, `String`, collections, and third-party types can cross via given instances rather than wrapper classes.

## Consequences

- **The userdata-`__gc` problem disappears.** Nothing host-owned lives behind a Luau value, so there is no Lua→Host lifetime to track, no `__gc` upcall, no host-object pinning. Memory management on each side is fully independent.
- **Asymmetric by design.** Host→Luau is copy-only; Luau→Host may be a **Ref** (cheap registry handle) because pinning a *Luau* object is clean (ADR-0005). Luau objects can be referenced by the Host; host objects can never be referenced by Luau.
- **Copy cost is accepted.** Large structures are copied across the boundary — not ideal for throughput, but it buys decoupled lifetimes, zero aliasing, and a trivial threading story (consistent with the Isolate copy-don't-share model).
- **Encoders write to a Sink, not an intermediate tree** — single copy into Luau rather than host→tree→Luau. Keeps `core` backend-agnostic while avoiding double allocation.
- **Strings cross as bytes** (Luau strings are byte strings); a UTF-8 `String` view and a raw-bytes escape hatch sit on top.
- Conventions for `case class` → table (field-name keys) and `List`/`Array` → 1-indexed array-part are Codec details, deferred.
