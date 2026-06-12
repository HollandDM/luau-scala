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

## Cobalt — remaining queue

- **ADAPT next (best value)**: base_spec (507 lines; drop getfenv/setfenv 5.1
  section + version-gated float formatting), vararg_spec (202; drop 5.0 `arg`
  table section), table_spec (946; drop `:lua>=5.5` length/hash sections),
  string_spec (582; drop string.pack — Luau has NO string.pack — and 5.x `%q`
  variants), operation_spec (101; relax error text).
- **SKIP**: goto_spec (Luau has no goto), bytecode_spec (string.dump),
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

## luau-java — queue (Scala-side ports, not .luau)

API tests against real Luau via FFM; most value-codec/host-fn/error cases are
already covered by our TC-API suite. Worth porting as Scala tests:
- error propagation through host-fn → Lua → host-fn chains, deep traces
- coroutine resume returning errors (not just yield/done)
- compile-error shapes (empty source, syntax error text presence)
Feature-GAP markers it surfaced (not test ports): vector/buffer codecs,
read-only tables, userdata, string atoms, preemption/interrupt, per-category
GC stats, require/module (tracked separately as task #9).

## Rembulan — mined for reference only

5.3 semantics throughout (integer subtype, goto, _ENV, integer //): ~70%
unportable by construction, and the portable remainder duplicates Cobalt/LuaJ
coverage at higher extraction cost (tests are embedded in a Scala DSL).
Decision: do not port; optionally mine TableLibFragments/MetatableFragments
ideas when writing native Luau tests.
