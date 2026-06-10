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

### 2.1 One shared accessor abstraction: `LuaAccess[H, K]`

Globals, table fields, and array elements are the same thing: a **keyed place
holding a Lua value**. Instead of duplicating typed accessors on `LuaState`
(globals), `LuaTbl` (string fields), and again for indices, every typed
operation is written once over two abstract stack primitives:

```scala
/** A keyed place holding Lua values. Concrete cases: globals (K = String),
  * table fields (K = String), array elements (K = Int). All typed access —
  * copy-out, copy-in, handle minting — is implemented here once.
  */
abstract class LuaAccess[H, K]:
  /** Push the value at `key` onto the main stack, run `f` with it at -1,
    * then restore the stack (the impl pops everything it pushed).
    */
  protected def withValueAt[A](key: K)(f: => A): A

  /** Run `push` (which must push exactly one value), then consume it into
    * the slot at `key`.
    */
  protected def storeAt(key: K)(push: => Unit): Unit

  final def get[V: LuauDecoder](key: K): Try[V]
  final def set[A: LuauEncoder](key: K, value: A): Unit
  final def getFn(key: K)(using s: RefScope[H]^):  Try[LuaFn[H]^{s}]
  final def getTbl(key: K)(using s: RefScope[H]^): Try[LuaTbl[H]^{s}]
```

Concrete cases:

- **`LuaState extends LuaAccess[H, String]`** — globals.
  `withValueAt` = `getGlobal` + cleanup; `storeAt` = encode + `setGlobal`.
  The existing `global` / `setGlobal` / `globalFn` / `globalTbl` collapse
  into the inherited `get` / `set` / `getFn` / `getTbl` (breaking rename,
  pre-1.0, tests migrate in the same change).
- **`LuaTbl extends LuaAccess[H, String]`** — fields.
  `withValueAt` = `ref.push; pushString; rawGet; f; pop(2)` — the impl owns
  cleanup, so no `lua_remove` is needed in the shim ABI.
- **`tbl.at: LuaAccess[H, Int]`** — array elements, via `rawGeti`/`rawSeti`.
  An inner member rather than a second parent: inheriting the same trait
  twice with different `K` is illegal. Usage: `tbl.at.get[Double](3)`,
  `tbl.at.getFn(1)`.

What does NOT generalize: `eval*` (compiles chunks — only a state does
that), `evalFn`, `coro`, and the bulk/array extras below stay on their
owning class.

Scoping note for the minted handles: the handle is pinned in whichever scope
is in context at the mint site, **not** the scope owning the source table.
Registry pins are independent, so `tbl^{s1}` minting `fn^{s2}` in a nested
scope is sound. Nested chains compose:
`st.getTbl("config").flatMap(_.getTbl("callbacks")).flatMap(_.getFn("onTick"))`.

### 2.2 `LuaTbl`-only extras

```scala
def length: Long                              // rawLen on the pinned table
def toSeq[V: LuauDecoder]: Try[Seq[V]]        // push table, decodeAt(-1), pop
def toMap[V: LuauDecoder]: Try[Map[String, V]]
```

`toSeq`/`toMap` are **copies-at-call-time** (snapshot), reusing the existing
`Seq`/`Map` decoders — zero new decoding machinery. They fail on ref-data
members, same rule as the value plane.

### 2.3 Multi-result: explicit arities, not a tuple decoder

Two options considered:

- **O1 — fixed-arity variants**: `eval2[A, B](src): Try[(A, B)]` …
  `eval6[...]`, and matching `call2`…`call6` / `resume2`…`resume6` on
  handles. Decode at `-n`, `-n+1`, ... — mechanical, no new abstractions.
- **O2 — window decoder**: a `ResultsDecoder[T <: Tuple]` that consumes a
  stack *window* rather than one index. Generalizes to any arity, but it is a
  second decoder concept living next to `LuauDecoder`, and every instance
  must agree on window-position bookkeeping.

**Decision: O1, arities 2–6.** Six is enough for now; O2 can subsume O1
later without breaking callers. `defineGlobal` argument arities extend from
0–4 to 0–6 in the same change for symmetry. Single-result `eval[V]` keeps
first-result semantics (documented), no strict mode for now.

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

Extends `ApiSuite` (runs on both backends): the LuaAccess contract exercised
through all three concrete cases (global / field / array elem) — get/set
round-trips, handle mint + call, type-mismatch failures; nested handle
chains; `toSeq`/`toMap` snapshot + ref-data rejection; `eval2`/`call2`
happy paths and an arity-6 case; `into` call-site ergonomics
(compile-level). CC escape negatives for tbl-minted handles
verified the established way (cc rejection recorded, blind spot pinned in
CcCompileSpec).

## 5. Open questions (grill here)

1. Naming for the array-element view: `tbl.at` (`tbl.at.get[Double](3)`) vs
   `tbl.arr` vs index overloads directly on `LuaTbl`?
2. The `LuaState` rename is breaking: `global`/`setGlobal`/`globalFn`/
   `globalTbl` become inherited `get`/`set`/`getFn`/`getTbl`. OK to break
   now (pre-1.0), or keep deprecated aliases for one cycle?
3. Should `set` accept a handle (`tbl.set("cb", fn)`) — writing ref data INTO
   a table? Sound (push via pin, rawSet), but it lets a short-lived scope
   install a long-lived reference; the Lua side keeps it alive after the pin
   drops, which is fine GC-wise but may surprise. Include or defer?
4. Is first-result-wins for plain `eval[V]` acceptable long-term, or should
   extra results be a `Failure` under a strict flag?
5. `LuaAccess` name — alternatives: `LuaSlots`, `LuaFields`, `KeyedAccess`.
