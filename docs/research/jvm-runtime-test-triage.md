# JVM Lua/Luau runtime test corpora: triage and porting queue

Status: research note (2026-06-12). Source repos (all probed at these commits):

| Repo | Commit | License | Lua dialect | Test style |
|---|---|---|---|---|
| cc-tweaked/Cobalt | 5df90f0 | MIT | 5.1/5.2 (+5.3/5.4 tagged) | describe/it/expect specs (assert-style) |
| luaj/luaj | daf3da9 | MIT | 5.2 | print-based scripts (output comparison) |
| hollow-cube/luau-java | 6c055d1 | MIT | real Luau (FFM binding) | JUnit API tests |
| mjanicek/rembulan | ee7cfe3 | Apache-2.0 | 5.3 | Scala DSL with embedded Lua fragments |

Porting conventions: adapted copies in `stdlib/test/resources/ported/<repo>/`
with a header naming upstream file, commit, and every edit; `-- dropped:` /
divergence markers; files end `return "OK"` (read via `TaskHandle.results`).
Error-TEXT assertions are relaxed to error-OCCURS assertions (Luau wording
differs); upstream text stays in comments.

## Batch 1 — DONE (2026-06-12): Cobalt assert-style specs

Ported to `ported/cobalt/` and green on both backends:

- `bit32.luau` (from bit32_spec, 136 lines) — passed unmodified except error-text relaxation.
- `utf8.luau` (from utf8_spec, 253 lines) — dropped `:lua>=5.4`/`:lua>=5.5`
  sections and the `load()`-based lexing test; everything else passed.
- `math.luau` — dropped 5.4 exact-PRNG values (Luau uses a different PRNG).
- `coroutine.luau` — running/isyieldable.
- `vm.luau` — type-error failures, metamethod-yield probes, error positions.

Luau divergences discovered and now pinned as assertions:

1. `math.atan` ignores a second argument (5.1 behavior); `math.atan2` is the
   two-argument spelling.
2. `coroutine.running()` returns only the running thread — no 5.2 `is_main`
   second value.
3. Yielding across VM-invoked metamethods is forbidden for ALL of __add,
   __div, __concat, __eq, __lt, __le, __len, __index, __newindex —
   "attempt to yield across metamethod/C-call boundary" (Cobalt/5.2 allow it).
4. Argument errors are worded "invalid argument #N to 'f'" (PUC: "bad
   argument"), with the chunk:line prefix present.

## Batch 2 — DONE (2026-06-12): Cobalt adaptation set

Ported to `ported/cobalt/`: `base.luau` (from base_spec; getfenv/setfenv
kept — Luau HAS them, contrary to the initial triage), `vararg.luau`,
`operation.luau`, `string.luau` (string.pack kept — Luau HAS
pack/unpack/packsize, correcting the initial triage), `table.luau`.

Further Luau divergences pinned as assertions:

5. `string.byte(s, 1, -1)` multret is stack-bounded: 2^19 raises
   "stack overflow (string slice too long)" (PUC 5.2+ allows it).
6. `%` keeps the 5.1 definition `a - floor(a/b)*b`: at ~1e105 magnitudes it
   collapses to 0 where 5.3's fmod-based float `%` gives the exact remainder.
7. The whole `table.*` library is a raw fastpath: insert/remove/move/sort/
   unpack/concat do NOT honor __index/__newindex/__len proxies (PUC 5.3 and
   Cobalt drive metamethods); `table.remove` on an empty table does not pop
   index 0 (5.2 does); `table.move` of a metamethod-only source copies
   nothing.
8. The C-call-boundary yield ban extends to gsub replacement functions,
   sort comparators, sort-driven __lt, and table.foreach/foreachi callbacks.
9. Luau's number printing is Schubfach shortest-roundtrip (e.g. 2^62 renders
   "4611686018427388000"), matching no PUC formatting branch.
10. `inext` (ipairs iterator) returns 0 values when exhausted, not nil.

## Cobalt — remaining

- **SKIP (final)**: goto_spec (Luau has no goto), bytecode_spec (string.dump),
  debug_spec (Debug lib outside Standard), compare/ (golden-output diffing vs
  PUC), perf/, assert/ (Cobalt-internal regressions — re-triage individually
  if mining deeper).

## LuaJ — queue (harder: print-based)

Tests print results rather than asserting; the repo carries no golden .out
files, so porting means re-deriving expected values per line. Candidates in
rough order: coroutinelib (126), functions (74), upvalues (97),
manyupvals (37), errors (137; relax messages, drop loadfile/dofile),
baselib/tablelib/stringlib/mathlib/metatags (drop string.dump, debug.*,
collectgarbage, platform branches, the 5.0 `arg` sections).
SKIP: iolib, debuglib, errors/ subdir (requires `require`/package framework).

## luau-java — DONE (2026-06-12, Scala-side)

API tests against real Luau via FFM; most value-codec/host-fn/error cases
were already covered by the TC-API suite. The uncovered edge cases landed as
TC-API-38..42 in the shared ApiSuite (both backends): empty-chunk compile,
host-fn body throwing (uncaught + pcall-caught + VM-stays-usable), host error
text surviving nested pcall layers, coro resume error after a yield, and the
chunkname:line prefix on script error().
Feature-GAP markers it surfaced (not test ports): vector/buffer codecs,
read-only tables, userdata, string atoms, preemption/interrupt, per-category
GC stats, require/module (tracked separately as task #9).

## Rembulan — mined for reference only

5.3 semantics throughout (integer subtype, goto, _ENV, integer //): ~70%
unportable by construction, and the portable remainder duplicates Cobalt/LuaJ
coverage at higher extraction cost (tests are embedded in a Scala DSL).
Decision: do not port; optionally mine TableLibFragments/MetatableFragments
ideas when writing native Luau tests.
