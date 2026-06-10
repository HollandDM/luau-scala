# Plan 09 — Handle Composition (facade gap-fill)

**Status:** Draft for discussion — nothing here is implemented.
**Depends on:** the shipped `luau.api` facade (value plane + `useRef` handle plane).

## 1. Problem

Handles (`LuaFn`, `LuaTbl`, `LuaCoro`) can only be minted from two sources:
`evalFn`/`globalFn`/`globalTbl` (globals or whole-chunk results). Four gaps
users hit immediately:

1. **No handle out of a table.** `config.callbacks.onTick` is unreachable: the
   value-plane decoder rejects it (function = reference data, correct), and
   the handle plane has no `tbl.getFn(...)`. Today's workaround is global
   juggling (`run("__tmp = config.callbacks.onTick")` + `globalFn("__tmp")`),
   which is exactly the kind of stringly escape hatch the facade exists to
   kill.
2. **`LuaTbl` has no array surface.** No indexed get/set, no length, no bulk
   snapshot. A Lua array table can only be copied out by decoding the whole
   global.
3. **Multi-result drop is silent.** `eval[V]` / `fn.call[V]` decode the first
   result and drop the rest without telling anyone.
4. **`LuaArg` wrapping is verbose.** `fn.call[Double](LuaArg(21.0))` — the
   wrapper is pure ceremony at every call site.

## 2. Proposal

### 2.1 Handle getters on `LuaTbl`

```scala
final class LuaTbl[H] ...:
  def getFn(key: String)(using s: RefScope[H]^):  Try[LuaFn[H]^{s}]
  def getTbl(key: String)(using s: RefScope[H]^): Try[LuaTbl[H]^{s}]
  def getFnAt(i: Int)(using s: RefScope[H]^):  Try[LuaFn[H]^{s}]
  def getTblAt(i: Int)(using s: RefScope[H]^): Try[LuaTbl[H]^{s}]
```

Implementation is the existing chain internalized: `ref.push` → push key /
`rawGeti` → type-check top → pin → rebind Ref to the main-thread handle
(same `pinTop` helper `LuaState` already uses; it moves to a `private[api]`
shared spot).

Scoping note: the minted handle is pinned in whichever scope is in context at
the mint site, **not** the scope that owns the source table. Registry pins are
independent objects, so `tbl^{s1}` minting `fn^{s2}` inside a nested scope is
sound — the fn handle dies with `s2` regardless of `s1`. Nested chains
compose: `cfg.getTbl("callbacks").flatMap(_.getFn("onTick"))`.

### 2.2 Array / bulk ops on `LuaTbl`

```scala
def get[V: LuauDecoder](i: Int): Try[V]      // rawGeti + decode + pop
def set[A: LuauEncoder](i: Int, value: A): Unit
def length: Long                              // rawLen on the pinned table
def toSeq[V: LuauDecoder]: Try[Seq[V]]        // push table, decodeAt(-1), pop
def toMap[V: LuauDecoder]: Try[Map[String, V]]
```

`toSeq`/`toMap` are **copies-at-call-time** (snapshot), reusing the existing
`Seq`/`Map` decoders — zero new decoding machinery. They fail on ref-data
members, same rule as the value plane.

### 2.3 Multi-result: explicit arities, not a tuple decoder

Two options considered:

- **O1 — fixed-arity variants**: `eval2[A, B](src): Try[(A, B)]`,
  `eval3[...]`, and matching `call2`/`call3` / `resume2` on handles. Decode at
  `-n`, `-n+1`, ... — ~30 lines, no new abstractions.
- **O2 — window decoder**: a `ResultsDecoder[T <: Tuple]` that consumes a
  stack *window* rather than one index. Generalizes to any arity, but it is a
  second decoder concept living next to `LuauDecoder`, and every instance
  must agree on window-position bookkeeping.

**Recommendation: O1 now.** Arity >3 returns are rare in embedding practice;
O2 can subsume O1 later without breaking callers. Single-result `eval[V]`
keeps first-result semantics (documented), no strict mode for now.

### 2.4 `LuaArg` ergonomics via `into`

`-experimental` is already on globally. SIP-66's `into` modifier
(`import language.experimental.into`) lets conversions fire without the
caller importing `implicitConversions`:

```scala
def call[V: LuauDecoder](args: (into LuaArg)*): Try[V]
// caller: fn.call[Double](21.0, "label", true)
```

Needs a spike: `into` + varargs interaction on 3.8.3 is the unverified part.
Fallback if it misbehaves: status quo (`LuaArg(...)` explicit, given
Conversion for users who opt into implicitConversions).

## 3. Non-goals (this plan)

- Path-style access (`tbl.getFn("callbacks.onTick")`) — string parsing hides
  errors; nested `getTbl` chains are explicit and cheap.
- Metatable operations.
- Passing Lua functions *into* host fns as handles — that is async-era design
  (the host fn would need a scope whose lifetime is the call).
- Table iteration as a lazy cursor — `toSeq`/`toMap` snapshots only.

## 4. Test plan

Extends `ApiSuite` (runs on both backends): nested handle mint + call; array
get/set/length round-trip; `toSeq`/`toMap` snapshot + ref-data rejection;
`eval2`/`call2` happy path + arity-mismatch failure; `into` call-site
ergonomics (compile-level). CC escape negatives for tbl-minted handles
verified the established way (cc rejection recorded, blind spot pinned in
CcCompileSpec).

## 5. Open questions (grill here)

1. `getFn`/`getTbl` naming — or overload `get` and disambiguate by type
   param? (`tbl.get[LuaFn[H]]` reads nicely but makes the decoder/type-class
   story muddier; separate names keep planes visibly distinct.)
2. Should `set` accept a handle (`tbl.set("cb", fn)`) — writing ref data INTO
   a table? Sound (push via pin, rawSet), but it lets a short-lived scope
   install a long-lived reference; the Lua side keeps it alive after the pin
   drops, which is fine GC-wise but may surprise. Include or defer?
3. `eval2`/`call2` vs waiting for a real tuple-window decoder — anyone feel
   strongly the other way?
4. Is first-result-wins for plain `eval[V]` acceptable long-term, or should
   extra results be a `Failure` under a strict flag?
