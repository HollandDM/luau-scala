# PUC-Rio Lua C Runtime: Technical Reference

> Covers Lua 5.1 (Luau's direct ancestor), with diffs for 5.3 and 5.4 where relevant.
> Primary sources: [lua.org/source/5.1](https://www.lua.org/source/5.1/), [lua.org/source/5.4](https://www.lua.org/source/5.4/), [The Implementation of Lua 5.0 (JUCS)](https://www.jucs.org/jucs_11_7/the_implementation_of_lua/jucs_11_7_1159_1176_defigueiredo.html).

---

## 1. Register-Based VM (Lua 5.0 and Later)

### 1.1 Historical Context

Lua 1.x–4.x used a stack-based VM. Lua 5.0 (2003) switched to a register-based design — at the time described as "the first register-based virtual machine to have wide use." The key motivation: in a stack VM, moving values around requires repeated `PUSH`/`POP`; tagged values in Lua are 12–16 bytes each on 32-bit platforms, making those moves expensive. Register instructions encode source and destination operands directly in a 32-bit word, eliminating redundant moves.

**Benchmark results from the paper** (Intel Pentium IV, gcc 3.3, Lua 5.0 vs Lua 4.0):

| Benchmark | Lua 5.0 / Lua 4.0 |
|-----------|-------------------|
| sum (2×10⁷ iters) | 44% (more than 2× speedup) |
| fibo(30) | 73% |
| sieve(100) | 61% |
| heapsort(5×10⁴) | 65% |
| matrix(50) | 70% |

### 1.2 Virtual Registers

Registers are not CPU registers — they are slots in the Lua runtime stack, which is a C array of `TValue`. The VM maps the current function's local variable frame onto a contiguous slice of that array. `base` points to the first slot (register 0) of the current activation record. Register `n` is `base + n`.

`maxstacksize` in `Proto` (compiled at function definition time) tells the VM how much stack to reserve for that function. The VM guarantees `ci->top = base + p->maxstacksize` slots are available before entering a function.

### 1.3 Main Interpreter Loop

`lvm.c: luaV_execute(lua_State *L, int nexeccalls)`

```
for (;;) {
  const Instruction i = *pc++;
  switch (GET_OPCODE(i)) { ... }
}
```

One fetch per instruction. Opcode dispatched via C `switch`. The program counter `pc` is a `const Instruction *` pointing into `Proto::code`. On `CALL`/`RETURN`, the loop is re-entered via `goto reentry` after `luaD_precall` sets up the new frame.

**Register access macros:**

```c
#define RA(i)   (base + GETARG_A(i))
#define RB(i)   check_exp(getBMode(GET_OPCODE(i)) == OpArgR, base + GETARG_B(i))
#define RC(i)   check_exp(getCMode(GET_OPCODE(i)) == OpArgR, base + GETARG_C(i))
#define RKB(i)  /* if bit 8 set: K[B & ~BITRK], else R[B] */
#define RKC(i)  /* same for C */
```

`BITRK = 256` — bit 8 of a 9-bit B or C field marks "this is a constant index, not a register index." Constants are accessed as `Proto::k[index & ~BITRK]`.

---

## 2. Bytecode Instruction Format

### 2.1 Lua 5.1 Format (32 bits)

```
 31      23      14      6     0
  BBBBBBBBB CCCCCCCCC AAAAAAAA OOOOOO
  9 bits    9 bits    8 bits   6 bits
```

| Field | Bits | Position | Max value |
|-------|------|----------|-----------|
| OP    | 6    | 0–5      | 63        |
| A     | 8    | 6–13     | 255       |
| C     | 9    | 14–22    | 511       |
| B     | 9    | 23–31    | 511       |
| Bx    | 18   | 14–31    | 262143    |
| sBx   | 18   | 14–31    | ±131071 (excess-K, bias = 131071) |

**Three encoding modes:**

- `iABC` — three operands: A (dest), B (src1), C (src2)
- `iABx` — A and an 18-bit unsigned constant index (Bx = B<<9 | C)
- `iAsBx` — A and a signed 18-bit integer (stored as Bx − MAXARG_sBx/2)

### 2.2 All 38 Lua 5.1 Opcodes

```
MOVE      LOADK     LOADBOOL  LOADNIL
GETUPVAL  GETGLOBAL GETTABLE  SETGLOBAL
SETUPVAL  SETTABLE  NEWTABLE  SELF
ADD       SUB       MUL       DIV
MOD       POW       UNM       NOT
LEN       CONCAT    JMP       EQ
LT        LE        TEST      TESTSET
CALL      TAILCALL  RETURN    FORLOOP
FORPREP   TFORLOOP  SETLIST   CLOSE
CLOSURE   VARARG
```

Notable semantics:

- `EQ/LT/LE` have `T` (test) flag set — they skip the next `JMP` on match failure. This avoids dedicated "jump-if-not-equal" variants; a comparison is always followed by `JMP`, and on success the VM fetches the `JMP` and executes it in the same dispatch, avoiding an extra cycle.
- `NEWTABLE A B C` — B encodes array hint, C encodes hash hint, both using a floating-point byte encoding (`eeeeexxx` → `(1|xxx) << (eeeee−1)` when eeeee≠0).
- `SETLIST A B C` — fills `R(A)[C*FPF+1 .. C*FPF+B]` from registers. FPF (fields per flush) = 50. When C=0, uses `EXTRAARG` for the block number.
- `CLOSURE A Bx` — creates `LClosure` from `Proto::p[Bx]`, then processes `nups` following pseudo-instructions (`OP_MOVE` or `OP_GETUPVAL`) to bind upvalues.
- `CLOSE A` — closes all open upvalues ≥ register A (called at scope exit for captured variables).
- `VARARG A B` — copies vararg values into R(A) ... R(A+B-2); B=0 means "all of them" (adjusts stack top).

### 2.3 Opcode Metadata (`lopcodes.c`)

Each opcode carries a metadata byte encoded as:
```c
opmode(t, a, b, c, m) = (((t)<<7) | ((a)<<6) | ((b)<<4) | ((c)<<2) | (m))
```

Fields: `t` = test (conditional skip), `a` = A is a destination, `b/c` = operand type (OpArgN/U/R/K), `m` = format (iABC/iABx/iAsBx).

### 2.4 Lua 5.4 Format Changes

Lua 5.4 expands to **~90 opcodes** and changes encoding:

```
 31      24     16      8  7     0
  BBBBBBBB CCCCCCCC k AAAAAAAA OOOOOOO
  8 bits   8 bits  1  8 bits   7 bits
```

Key differences:

- Opcode now **7 bits** (was 6), supporting more opcodes.
- New `k` bit (position 8) replaces the bit-8 trick in B/C; indicates whether C operand is a constant.
- New formats: `iAx` (25-bit argument), `isJ` (25-bit signed jump offset for `JMP`).
- `LOADI`/`LOADF` — load integer/float immediates directly from sBx, no constant pool entry needed.
- `GETI`/`GETFIELD`/`SETI`/`SETFIELD` — specialized table access for integer keys and string-constant keys.
- `ADDI`/`ADDK`/`SUBK`/`MULK` etc. — immediate and constant-pool arithmetic variants.
- `EQI`/`LTI`/`LEI`/`GTI`/`GEI` — comparisons against integer immediates.
- `MMBIN`/`MMBINI`/`MMBINK` — explicit metamethod dispatch instructions inserted by codegen after arithmetic, eliminating inline dispatch from arithmetic opcodes.
- `TBC` — marks a register as "to-be-closed" (triggers `__close` metamethod on scope exit).
- `RETURN0`/`RETURN1` — specialized zero- and single-return variants.
- `TFORPREP` — creates an upvalue for the iterator state before `TFORCALL`/`TFORLOOP`.
- `VARARGPREP` — adjusts vararg parameters on function entry (replaces `adjust_varargs` call in `luaD_precall`).

---

## 3. Value Representation (TValue / Tagged Union)

### 3.1 Lua 5.1 `TValue`

Defined in `lobject.h`:

```c
typedef union {
  GCObject *gc;   /* collectable objects */
  void     *p;    /* light userdata */
  lua_Number n;   /* number (double) */
  int        b;   /* boolean */
} Value;

typedef struct lua_TValue {
  Value value;
  int   tt;       /* type tag */
} TValue;
```

Size: 8 bytes value (union sized to `lua_Number` = `double`) + 4 bytes tag = **12 bytes** on 32-bit, **16 bytes** on 64-bit (alignment).

**Type tags:**

| Constant | Value | Description |
|----------|-------|-------------|
| `LUA_TNIL` | 0 | nil |
| `LUA_TBOOLEAN` | 1 | boolean |
| `LUA_TLIGHTUSERDATA` | 2 | raw pointer, not GC'd |
| `LUA_TNUMBER` | 3 | double |
| `LUA_TSTRING` | 4 | interned string |
| `LUA_TTABLE` | 5 | table |
| `LUA_TFUNCTION` | 6 | Lua or C closure |
| `LUA_TUSERDATA` | 7 | full userdata |
| `LUA_TTHREAD` | 8 | coroutine |
| `LUA_TPROTO` | 9 | (internal) function prototype |
| `LUA_TUPVAL` | 10 | (internal) upvalue |
| `LUA_TDEADKEY` | 11 | (internal) dead hash key sentinel |

All GC-managed types (`LUA_TSTRING` through `LUA_TTHREAD`) store a `GCObject *` in the union. The `GCObject` union is a tagged union of all GC types sharing a `CommonHeader`:

```c
#define CommonHeader  GCObject *next; lu_byte tt; lu_byte marked
```

`next` chains all GC objects for the collector. `tt` redundantly stores the type tag. `marked` holds GC color/age bits.

### 3.2 Lua 5.3 Changes

Lua 5.3 added integer numbers as a first-class subtype. The `Value` union gains `lua_Integer i`. Type tags use **variant bits**:

- Bits 0–3: base type
- Bits 4–5: variant within that type

New number variants:
- `LUA_TNUMFLT` = `LUA_TNUMBER | 0` (float, `lua_Number` = double)
- `LUA_TNUMINT` = `LUA_TNUMBER | (1<<4)` (integer, `lua_Integer` = `ptrdiff_t` or `long long`)

This enables distinguishing `3` (integer) from `3.0` (float), which affects arithmetic semantics (integer division `//`, bitwise ops).

### 3.3 Lua 5.4 Changes

5.4 extends the variant system further:

```c
typedef union Value {
  GCObject *gc;
  void *p;
  lua_CFunction f;    /* light C function (new: can be stored without closure) */
  lua_Integer i;
  lua_Number n;
  lu_byte ub;         /* (internal sentinel) */
} Value;
```

Nil variants: `LUA_VNIL`, `LUA_VEMPTY` (empty hash slot), `LUA_VABSTKEY` (absent key sentinel). Boolean split: `LUA_VTRUE` / `LUA_VFALSE` — boolean value encoded directly in tag, no separate `b` field needed.

**No NaN-boxing in PUC Lua.** LuaJIT uses NaN-boxing (encoding types in the high bits of a 64-bit IEEE 754 NaN payload), but PUC-Rio's reference implementation uses explicit tagged structs throughout all versions.

---

## 4. Tables (Array + Hash Hybrid)

### 4.1 Data Structures (`lobject.h`, `ltable.c`)

```c
typedef struct Table {
  CommonHeader;
  lu_byte flags;        /* fast metamethod presence cache (bits 0..TM_EQ) */
  lu_byte lsizenode;    /* log2 of hash part size */
  struct Table *metatable;
  TValue *array;        /* array part (1-indexed: array[0] = index 1) */
  Node   *node;         /* hash part (open addressing + chaining) */
  Node   *lastfree;     /* pointer to last free hash node */
  GCObject *gclist;
  int sizearray;        /* size of array part */
} Table;

typedef union TKey {
  struct { Value value; int tt; struct Node *next; } nk;  /* key + chain */
  TValue tvk;           /* key as TValue (overlaid) */
} TKey;

typedef struct Node {
  TValue i_val;   /* value */
  TKey   i_key;   /* key (includes next pointer for collision chain) */
} Node;
```

`lsizenode` stores log₂ of hash part size, so hash size = `1 << lsizenode` (always power of 2).

### 4.2 Array vs Hash Decision

Key `k` goes to the array part iff:
1. `k` is a positive integer (1-based).
2. `k ≤ sizearray`.

The array part is sized to the largest `n` such that at least **half** the slots `[1..n]` are occupied AND at least one slot in `[n/2+1..n]` is occupied. This prevents both sparse over-allocation and under-allocation.

Non-integer keys, floating-point numbers, strings, booleans, userdata, and integers outside `[1..sizearray]` go to the hash part.

**Memory advantage:** array slots store only `TValue` (no key storage); hash nodes store `TValue` key + `TValue` value + `Node*` next pointer. So same data takes ~3× less memory in the array vs hash part.

### 4.3 Hash Part: Brent's Variation

`mainposition(t, key)` computes the primary hash slot. The hash uses:
- Integers: `lmod(n, hash_size)` (bitwise AND since power-of-2)
- Strings: precomputed `TString::tsv.hash`
- Floats: `frexp` decomposition
- Pointers: address-based hash

Collision resolution: **open addressing with chaining inside the table.** Each node has a `next` pointer embedded in `TKey::nk.next`. Invariant (Brent's variation): if a node is not in its main position, the displacing node IS in its main position. On collision, the non-main-position element moves to a free slot, and the chain is adjusted. This allows 100% load factor without degenerate lookup.

Rehash triggered in `newkey()` when `lastfree == node` (no free nodes). `rehash()` counts integer keys in logarithmic ranges, calls `computesizes()` to find optimal array size, then resizes both parts.

### 4.4 Core API

- `luaH_new(L, narray, nhash)` — allocate table, set initial sizes.
- `luaH_get(t, key)` / `luaH_getnum(t, n)` / `luaH_getstr(t, s)` — raw lookup (no metamethod).
- `luaH_set(L, t, key)` — returns `TValue*` slot to assign into; calls `newkey()` if absent.
- `luaH_next(L, t, key)` — iteration: scans array then hash sequentially.
- `luaH_getn(t)` — binary search for integer boundary (last `t[n] != nil && t[n+1] == nil`).

---

## 5. Garbage Collector

### 5.1 Lua 5.1: Tri-Color Incremental Mark-and-Sweep

Implemented in `lgc.c`. Collector runs incrementally, interleaved with mutator via `luaC_step`.

**Object colors** (in `marked` field):

| Bit | Name | Meaning |
|-----|------|---------|
| 0 | WHITE0BIT | white variant 0 |
| 1 | WHITE1BIT | white variant 1 |
| 2 | BLACKBIT | object is black |
| 3 | FINALIZEDBIT / KEYWEAKBIT | pending finalization / weak key |
| 4 | VALUEWEAKBIT | weak value table |
| 5 | FIXEDBIT | never collected (e.g., reserved strings) |
| 6 | SFIXEDBIT | super-fixed |

Two white variants alternate each cycle. The "current white" flips at the atomic phase. An object is white-dead if its white bits match the **previous** cycle's white bit (i.e., not yet reached by the current sweep).

**GC state machine** (`global_State::gcstate`):

```
GCSpause → GCSpropagate → (atomic) → GCSsweepstring → GCSsweep → GCSfinalize → GCSpause
```

**GCSpause**: idle. Waits until `totalbytes >= GCthreshold`.

**GCSpropagate**: runs `propagatemark(g)` — pops one gray object, marks its children black, children become gray. Work unit = bytes traversed. Gray list = `global_State::gray`. Objects that need re-traversal during atomic phase go on `grayagain`.

**Atomic phase** (not a named state, runs inside `singlestep` transition): non-incremental portion — remarks thread upvalues, processes weak tables, separates finalizable userdata, flips current-white bit. After this, the "sweep" can safely free unmarked objects.

**GCSsweepstring**: sweeps `strt` (global string hash table) up to `GCSWEEPMAX` entries per step.

**GCSsweep**: iterates `rootgc` list, freeing dead objects, whitening survivors.

**GCSfinalize**: invokes `__gc` on dead userdata.

**Write barriers:**

- `luaC_barrier(L, p, v)` — forward barrier: if `p` is black and `v` is white, remark `v` gray. Maintains invariant: no black→white edge.
- `luaC_barriert(L, t, v)` — table barrier (backward): turns black table `t` back to gray (add to `grayagain`). Used for tables because they're frequently mutated; re-scanning at atomic phase is cheaper than forwarding every assignment.

**Step sizing:**
```c
lim = (GCSTEPSIZE / 100) * g->gcstepmul;
g->gcdept += g->totalbytes - g->GCthreshold;
do { lim -= singlestep(L); } while (lim > 0 && g->gcstate != GCSpause);
```

`gcstepmul` (default 200) scales work per step relative to allocation rate. `gcpause` (default 200) controls how much memory grows before next cycle (200 = double post-collection size).

### 5.2 Lua 5.3: Same Incremental GC

No architectural GC change. The 5.3 GC is essentially the same incremental tri-color mark-and-sweep as 5.1, with minor refinements to handle the new integer subtype.

### 5.3 Lua 5.4: Generational GC (Optional Mode)

Lua 5.4 adds a second GC mode alongside incremental: **generational**. Controlled by `collectgarbage("incremental"|"generational")` or `KGC_INC`/`KGC_GEN` modes in `global_State::gckind`.

**Object age states** encoded in bits 0–2 of `marked` (`AGEBITS = 7 = 0b111`):

| Constant | Value | Meaning |
|----------|-------|---------|
| `G_NEW` | 0 | allocated this cycle |
| `G_SURVIVAL` | 1 | survived one minor cycle |
| `G_OLD0` | 2 | just promoted to old (touched by write barrier) |
| `G_OLD1` | 3 | first full cycle as old |
| `G_OLD` | 4 | permanently old; not re-scanned in minor cycles |
| `G_TOUCHED1` | 5 | old object modified this cycle |
| `G_TOUCHED2` | 6 | old object modified last cycle |

**Object lists in `global_State`:**
- `allgc` — all live GC objects (young)
- `survival` — objects that survived one cycle
- `old1` — first cycle as old
- `reallyold` — G_OLD objects
- `finobjsur`, `finobjold1`, `finobjrold` — finalizable objects per generation
- `grayagain` — old objects needing atomic re-scan after modification
- `weak`, `allweak`, `ephemeron` — weak table lists

**Minor collection** (`youngcollection`): sweeps only `allgc` (new), `survival`, `old1`. Survivors age via `nextage[]` table. Cost proportional to nursery size, not total heap.

**Major collection**: full mark-and-sweep across all generations when `gettotalbytes() > GCestimate * genmajormul / 100`.

**Remembered set:** write barrier `luaC_barrier_()` — when an old object points to a new object, the young object is marked `G_OLD0`, eventually aging to `G_OLD`. The `TOUCHED1`/`TOUCHED2` states handle modified old objects that must be re-scanned.

**Bad collection detection:** if a major cycle frees less than half the accumulated growth, the GC switches back to incremental mode (`enterinc()`) to avoid wasted minor cycles on non-generational workloads.

---

## 6. Metatables and Metamethods

### 6.1 Storage

Every `Table` and `Udata` has a `metatable` field (a `Table*` or `NULL`). All instances of other types share per-type metatables stored in `global_State::mt[LUA_TNUMFLT]` etc. (added in 5.3; in 5.1, numbers share one table).

### 6.2 Fast Metamethod Cache

`Table::flags` is an 8-bit bitmask caching **absent** metamethods. Bit `e` is set if metamethod `TMS(e)` is absent from this table's metatable. On lookup:

```c
#define gfasttm(g, et, e) \
  ((et) == NULL ? NULL : \
   ((et)->flags & (1u << (e))) ? NULL : \
   luaT_gettm(et, e, (g)->tmname[e]))
```

If the flag bit is set, the method is definitively absent — no table lookup needed. Only the first `TM_EQ + 1 = 5` metamethods (INDEX through EQ) qualify for fast-path caching. The rest always require full lookup.

### 6.3 Complete TMS Enumeration (Lua 5.1)

```c
typedef enum {
  TM_INDEX,       /* __index    */
  TM_NEWINDEX,    /* __newindex */
  TM_GC,          /* __gc       */
  TM_MODE,        /* __mode     */
  TM_EQ,          /* __eq       (last "fast" tag) */
  TM_ADD,         /* __add      */
  TM_SUB,         /* __sub      */
  TM_MUL,         /* __mul      */
  TM_DIV,         /* __div      */
  TM_MOD,         /* __mod      */
  TM_POW,         /* __pow      */
  TM_UNM,         /* __unm      */
  TM_LEN,         /* __len      */
  TM_LT,          /* __lt       */
  TM_LE,          /* __le       */
  TM_CONCAT,      /* __concat   */
  TM_CALL,        /* __call     */
} TMS;
```

Lua 5.4 adds `__idiv`, `__band`, `__bor`, `__bxor`, `__bnot`, `__shl`, `__shr` (bitwise), and `__close` (to-be-closed).

### 6.4 Metamethod Dispatch Semantics

**`__index`:** checked when a table lookup returns `nil` OR when the object is not a table. Can be a function (called with `(table, key)`) or a table (recursively indexed). Depth limit = 100 iterations in `luaV_gettable` loop.

**`__newindex`:** checked when assigning to an absent key in a table OR when object is not a table. Can be a function (called with `(table, key, value)`) or a table (raw assignment). If metamethod is a function, Lua does NOT perform primitive assignment.

**`__call`:** invoked when a non-function appears in call position. Receives the original object as first argument.

**Arithmetic (`__add` etc.):** checked on both operands in order; first non-nil metamethod found wins. In 5.4 this is separated into `MMBIN`/`MMBINI`/`MMBINK` instructions.

**`__eq`:** only invoked when both operands are same-type (both tables OR both full userdata) and primitively unequal. Never invoked for number/string comparisons.

**`__lt`/`__le`:** checked when operands are neither both numbers nor both strings.

**`__gc`:** only supported on full userdata in Lua 5.1; extended to tables in Lua 5.4.

**`__close`:** Lua 5.4 only. Called at block exit for variables declared `local x <close> = ...`. `OP_TBC` marks the register; `OP_CLOSE` triggers the call.

---

## 7. Function Prototypes (`Proto`)

Defined in `lobject.h`:

```c
typedef struct Proto {
  CommonHeader;
  TValue       *k;              /* constant pool (TValue array) */
  Instruction  *code;           /* bytecode (Instruction = uint32) */
  struct Proto **p;             /* nested Proto* array (for CLOSURE) */
  int          *lineinfo;       /* pc → source line mapping */
  struct LocVar *locvars;        /* local variable debug info */
  TString      **upvalues;       /* upvalue names (debug) */
  TString       *source;         /* source chunk name */
  int sizeupvalues, sizek, sizecode, sizelineinfo, sizep, sizelocvars;
  int linedefined, lastlinedefined;
  GCObject *gclist;
  lu_byte nups;           /* number of upvalues */
  lu_byte numparams;      /* number of fixed parameters */
  lu_byte is_vararg;      /* VARARG_HASARG | VARARG_ISVARARG | VARARG_NEEDSARG */
  lu_byte maxstacksize;   /* max registers needed (computed by compiler) */
} Proto;

typedef struct LocVar {
  TString *varname;
  int startpc;   /* first PC where variable is alive */
  int endpc;     /* first PC where variable is dead */
} LocVar;
```

`is_vararg` flags:
- `VARARG_HASARG = 1` — function was declared with `...`
- `VARARG_ISVARARG = 2` — function uses `...`
- `VARARG_NEEDSARG = 4` — function needs `arg` table (Lua 5.0 compat, dropped in 5.2)

---

## 8. Closures and Upvalues

### 8.1 Closure Types

```c
typedef struct LClosure {   /* Lua closure */
  CommonHeader;
  lu_byte isC;              /* = 0 */
  lu_byte nupvalues;
  GCObject *gclist;
  struct Table *env;        /* global env table (_ENV in 5.2+) */
  struct Proto *p;          /* compiled prototype */
  UpVal *upvals[1];         /* upvalue array (flexible) */
} LClosure;

typedef struct CClosure {   /* C closure */
  CommonHeader;
  lu_byte isC;              /* = 1 */
  lu_byte nupvalues;
  GCObject *gclist;
  struct Table *env;
  lua_CFunction f;          /* C function pointer */
  TValue upvalue[1];        /* C upvalues stored directly as TValue */
} CClosure;
```

C closures store upvalues as plain `TValue` (no indirection). Lua closures store `UpVal*` pointers (one level of indirection for open/close lifecycle).

### 8.2 UpVal Struct

```c
typedef struct UpVal {
  CommonHeader;
  TValue *v;          /* points to variable: stack slot (open) or u.value (closed) */
  union {
    TValue value;     /* closed value stored here */
    struct {
      struct UpVal *prev;   /* doubly-linked list of open upvalues */
      struct UpVal *next;
    } l;
  } u;
} UpVal;
```

**Open upvalue:** `v` points into the Lua stack (`v >= L->stack && v < L->top`). The value IS still on the stack; the upvalue is a transparent pointer.

**Closed upvalue:** `v == &u.value`. The value has been copied out of the stack. `luaF_close` performs this migration: `uv->u.value = *uv->v; uv->v = &uv->u.value;`.

### 8.3 Open Upvalue List

`lua_State::openupval` heads a doubly-linked list (via `UpVal::u.l.prev/next`) of all open upvalues in stack order. When `CLOSURE` executes, `luaF_findupval(L, stack_slot)` walks this list:
- If an open upvalue for that exact stack slot exists, reuse it (sharing guarantee).
- Otherwise, create new `UpVal`, insert into list in sorted order.

This guarantees that two closures capturing the same variable get the **same** `UpVal*`, so mutations are visible to both.

### 8.4 Flat Closures

Problem: if function `f` captures variable `x` from `g`, and `g` captures `x` from `h`, then when `f` instantiates, `g`'s stack frame may not exist. Lua solves this with **flat closures**: any upvalue not local to the immediate enclosing function is propagated outward into every intermediate closure as well.

`OP_CLOSURE` pseudo-instruction encoding:
- `OP_MOVE B` → upvalue `j` comes from stack register `B` of enclosing function (creates new open upvalue).
- `OP_GETUPVAL B` → upvalue `j` is inherited from upvalue `B` of enclosing function (shared directly).

In Lua 5.2+, this is encoded in `Proto::upvalues[]` with `instack` and `idx` fields:
- `instack=1, idx=n` → in enclosing function's register `n`.
- `instack=0, idx=n` → in enclosing function's upvalue `n`.

### 8.5 Scope Exit: CLOSE Instruction

When the compiler detects that any local variable in a block is captured by an inner closure, it emits `CLOSE A` at block exit where `A` is the lowest captured register. `luaF_close(L, level)` walks `openupval` and closes all upvalues with `v >= level`.

---

## 9. String Interning

### 9.1 Global String Table

All Lua strings are interned in `global_State::strt` — a `stringtable`:

```c
typedef struct stringtable {
  GCObject **hash;    /* array of hash chains */
  lu_int32  nuse;     /* number of strings in table */
  int size;           /* size of hash array (power of 2) */
} stringtable;
```

Every unique string exists exactly once in memory. String equality = pointer equality (after type check). This enables O(1) equality comparison.

### 9.2 String Creation: `luaS_newlstr`

```
1. Compute hash h by sampling characters.
2. Look up chain strt.hash[h % strt.size].
3. Walk chain: if (ts->len == l && memcmp(str, getstr(ts), l) == 0) → return ts.
   - If dead (marked for GC): resurrect with changewhite(o).
4. If not found: allocate new TString, copy bytes, insert at head of chain.
5. If nuse > size: call luaS_resize to double the table.
```

**Hash algorithm** (from `lstring.c`):
```c
unsigned int h = cast(unsigned int, l);  /* seed = length */
size_t step = (l >> LUAI_HASHLIMIT) + 1; /* LUAI_HASHLIMIT = 5 */
for (; l1 >= step; l1 -= step)
  h ^= ((h<<5) + (h>>2) + cast(unsigned char, str[l1-1]));
```

For strings longer than 32 bytes, only every `(len>>5)+1`-th character is hashed. This keeps hashing O(1) for large strings but makes collision attacks possible (known issue, mitigated in Lua 5.2+ with randomized hash seeds via `LUAI_SEED`).

### 9.3 String Memory Layout

```c
typedef union TString {
  L_Umaxalign dummy;      /* alignment */
  struct {
    CommonHeader;
    lu_byte reserved;     /* 1 = reserved keyword (for lexer speed) */
    unsigned int hash;    /* precomputed hash */
    size_t len;           /* byte length (binary-safe) */
  } tsv;
} TString;
/* string bytes follow immediately after the struct in memory */
```

`getstr(ts)` = `(const char*)((ts) + 1)` — bytes immediately follow the header. No separate allocation for string data.

Fixed strings (keywords, metamethod names) are marked with `FIXEDBIT` in `marked` — they survive GC regardless.

---

## 10. Coroutines

### 10.1 Representation

Each coroutine is a full `lua_State` — the main thread is also a `lua_State`. `global_State` is shared. Per-coroutine fields:

```c
struct lua_State {
  CommonHeader;           /* GC-managed as LUA_TTHREAD */
  lu_byte status;         /* 0=running, LUA_YIELD=suspended, etc. */
  StkId  top;             /* stack top (first free slot) */
  StkId  base;            /* base of current activation */
  global_State *l_G;      /* shared global state */
  CallInfo *ci;           /* current call info */
  const Instruction *savedpc;
  StkId  stack_last;
  StkId  stack;           /* stack array base */
  CallInfo *end_ci;
  CallInfo *base_ci;      /* CallInfo array */
  int stacksize;
  int size_ci;
  unsigned short nCcalls;     /* C call depth */
  unsigned short baseCcalls;  /* C call depth at last resume */
  TValue l_gt;            /* globals table */
  GCObject *openupval;    /* open upvalue list */
  struct lua_longjmp *errorJmp;
};
```

Each coroutine has its own stack (`stack` array), its own `CallInfo` array, and its own `openupval` list. The GC traces coroutine stacks; unreachable coroutines (and their stacks) are collected.

### 10.2 Asymmetric Coroutines

Lua implements semi-symmetric ("asymmetric") coroutines: only `resume` and `yield` — no symmetric `transfer`. A Lua coroutine can only be resumed by its parent (the caller of `coroutine.resume`), and can only yield to that same parent.

### 10.3 Resume Mechanism (`lua_resume`)

```
1. Validate: status must be LUA_YIELD or unstarted (status=0).
2. Set L->baseCcalls = ++nCcalls.
3. Call resume(L, nargs) under error protection (lua_longjmp).
4. In resume():
   - If status == 0 (unstarted): call luaD_precall to set up first frame.
   - If status == LUA_YIELD: restore L->base from saved state; complete pending CALL/TAILCALL.
5. Enter luaV_execute(L, ...).
6. On yield: luaV_execute returns, resume returns LUA_YIELD.
7. On completion: resume returns 0, moves results to caller's stack.
```

**Stack-level stackfulness:** unlike green-thread or fiber implementations, Lua coroutines do NOT switch C stacks. The main interpreter loop is re-entered via a C function call (`luaV_execute` called recursively per resume). When a coroutine yields, it returns all the way up the C call stack to the `lua_resume` call, leaving the Lua execution state (stack, pc, CallInfos) intact in the `lua_State` struct for next resume.

This means:
- Yielding from within nested Lua calls: fine (Lua call stack preserved in `CallInfo` chain).
- Yielding across a C API boundary: **forbidden** in Lua 5.1/5.2/5.3 unless using continuation-aware functions. Lua raises "attempt to yield across metamethod/C-call boundary."
- Guard: `if (nCcalls > baseCcalls) luaG_runerror(L, "attempt to yield...")`.

### 10.4 Yield Mechanism (`lua_yield`)

```c
int lua_yield(lua_State *L, int nresults) {
  if (L->nCcalls > L->baseCcalls)
    luaG_runerror(L, "attempt to yield across metamethod/C-call boundary");
  L->base = L->top - nresults;  /* protect results */
  L->status = LUA_YIELD;
  return -1;  /* signal to luaD_call to stop */
}
```

Return value `-1` propagates up through `luaD_call`, causes `luaV_execute` to return, which unwinds back to `lua_resume`, which returns `LUA_YIELD` to caller.

Lua 5.4 adds `lua_yieldk` (yield with continuation), `lua_callk`, `lua_pcallk` allowing yield from C functions by providing a C continuation callback.

### 10.5 Error Handling (`lua_longjmp` Chain)

```c
struct lua_longjmp {
  struct lua_longjmp *previous;
  luai_jmpbuf b;       /* platform-specific: setjmp buffer or C++ try block */
  volatile int status;
};
```

`luaD_rawrunprotected` pushes a new `lua_longjmp` node onto `L->errorJmp`, runs the protected function, then pops it. `luaD_throw` calls `LUAI_THROW(L, lj)` = `longjmp(lj->b, 1)`. On error, execution resumes at the nearest `setjmp` point, which reads the error object from the stack.

`luaD_pcall` additionally saves/restores: `ci`, `allowhook`, stack top, and closes pending upvalues (`luaF_close`).

---

## 11. Function Calls: Stack Frame Layout

### 11.1 `luaD_precall`

For a Lua function called with `CALL A B C`:

```
Stack before:
  [func] [arg1] [arg2] ... [argN]
   ^A

luaD_precall sets:
  ci->func = ra (function slot)
  ci->base = ra + 1 (first arg)
  L->base  = ci->base
  ci->top  = L->base + p->maxstacksize
  L->savedpc = p->code (start of bytecode)
  ci->nresults = C - 1 (or LUA_MULTRET if C=0)

Stack during execution:
  [func][arg1][arg2]...[argN][reg0][reg1]...
         ^L->base
```

Unused registers in `[base .. ci->top)` are nil-initialized. For non-vararg functions, extra actual args are discarded (`L->top = base + numparams`).

For vararg functions: `adjust_varargs` copies fixed params to `base`, places extra args below the frame, sets `arg` table if `VARARG_NEEDSARG`.

### 11.2 CallInfo Chain

```c
typedef struct CallInfo {
  StkId base;               /* base for this level */
  StkId func;               /* function index */
  StkId top;                /* top for this level */
  const Instruction *savedpc;
  int nresults;
  int tailcalls;            /* lost tail calls */
} CallInfo;
```

`base_ci` = static array. `ci` starts at `base_ci`, incremented by `inc_ci` macro (grows dynamically if needed). Maximum depth controlled by `LUAI_MAXCALLS` = 200 (default).

### 11.3 Tail Calls (`OP_TAILCALL`)

`luaD_precall` called with special handling: if function is Lua, the current `CallInfo` is reused in place, avoiding stack growth. `tailcalls` counter tracks how many tail calls were elided (for debug info). In Lua 5.4, `OP_TAILCALL` has a `k` bit indicating whether to close upvalues before tail-calling.

### 11.4 Multiple Return Values

`OP_RETURN A B`: returns `R(A) .. R(A+B-2)`. If `B=0`, returns `R(A) .. top-1` (dynamic count, used after `CALL` with C=0).

`luaD_poscall(L, firstResult)` adjusts the stack: copies results down to `func` slot, adjusts `L->top`. If caller expected fixed N results, pads with nil or truncates.

---

## 12. Version Delta Summary

| Feature | Lua 5.1 | Lua 5.3 | Lua 5.4 |
|---------|---------|---------|---------|
| Luau forked from | YES | No | No |
| Integer type | No (all numbers = double) | Yes (`lua_Integer` subtype) | Yes |
| Bitwise ops | No | Yes (via integer subtype) | Yes |
| GC mode | Incremental only | Incremental only | Incremental + Generational |
| GC tuning params | pause, stepmul | pause, stepmul | + stepsize, minormul, majormul |
| Opcodes | 38 | ~47 | ~90 |
| Instruction width | 32-bit, 6-bit opcode | 32-bit, 6-bit opcode | 32-bit, 7-bit opcode |
| Globals | `GETGLOBAL`/`SETGLOBAL` ops | Via `_ENV` upvalue (`GETTABUP`) | Same |
| `__gc` | Userdata only | Userdata only | Tables too |
| `__close` | No | No | Yes (to-be-closed vars) |
| Yield from C | Forbidden | Forbidden (except `*k` API) | Via continuation API |
| String hash seed | Fixed | Randomized (`LUAI_SEED`) | Randomized |
| Value tag bits | 1 tag field | Variant tag (base + subtype bits) | Extended variants |
| NaN-boxing | No | No | No |

---

## 13. Key Source Files (Lua 5.1.5)

| File | Role |
|------|------|
| `lobject.h` | `TValue`, `TString`, `Table`, `Proto`, `UpVal`, `CClosure`, `LClosure` structs |
| `lopcodes.h` | Opcode enum, instruction format macros, operand type constants |
| `lopcodes.c` | Per-opcode metadata table (mode, operand types) |
| `lvm.c` | `luaV_execute` main loop, arithmetic ops, `luaV_gettable`, `luaV_settable` |
| `ldo.c` | `luaD_precall`, `luaD_poscall`, `luaD_call`, `lua_yield`, `lua_resume`, error handling |
| `lfunc.c` | `luaF_newLclosure`, `luaF_newCclosure`, `luaF_findupval`, `luaF_close` |
| `ltable.c` | `luaH_new`, `luaH_get*`, `luaH_set*`, `luaH_next`, `rehash` |
| `lstring.c` | `luaS_newlstr`, `luaS_resize`, string interning |
| `lgc.c` | `luaC_step`, `singlestep`, `propagatemark`, `sweeplist`, `atomic` |
| `lgc.h` | GC color bits, write barrier macros, age constants |
| `ltm.h` | `TMS` enum, fast metamethod lookup macro |
| `lstate.h` | `lua_State`, `global_State`, `CallInfo` structs |

---

## Sources

- [The Implementation of Lua 5.0 (JUCS 2005)](https://www.jucs.org/jucs_11_7/the_implementation_of_lua/jucs_11_7_1159_1176_defigueiredo.html)
- [Lua 5.1.5 source: lobject.h](https://www.lua.org/source/5.1/lobject.h.html)
- [Lua 5.1.5 source: lopcodes.h](https://www.lua.org/source/5.1/lopcodes.h.html)
- [Lua 5.1.5 source: lopcodes.c](https://www.lua.org/source/5.1/lopcodes.c.html)
- [Lua 5.1.5 source: lvm.c](https://www.lua.org/source/5.1/lvm.c.html)
- [Lua 5.1.5 source: lfunc.c](https://www.lua.org/source/5.1/lfunc.c.html)
- [Lua 5.1.5 source: ltable.c](https://www.lua.org/source/5.1/ltable.c.html)
- [Lua 5.1.5 source: lstring.c](https://www.lua.org/source/5.1/lstring.c.html)
- [Lua 5.1.5 source: lgc.c](https://www.lua.org/source/5.1/lgc.c.html)
- [Lua 5.1.5 source: lgc.h](https://www.lua.org/source/5.1/lgc.h.html)
- [Lua 5.1.5 source: lstate.h](https://www.lua.org/source/5.1/lstate.h.html)
- [Lua 5.1.5 source: ltm.h](https://www.lua.org/source/5.1/ltm.h.html)
- [Lua 5.1.5 source: ldo.c](https://www.lua.org/source/5.1/ldo.c.html)
- [Lua 5.4 source: lobject.h](https://www.lua.org/source/5.4/lobject.h.html)
- [Lua 5.4 source: lgc.h](https://www.lua.org/source/5.4/lgc.h.html)
- [Lua 5.4 source: lgc.c](https://www.lua.org/source/5.4/lgc.c.html)
- [Lua 5.4 source: lopcodes.h](https://www.lua.org/source/5.4/lopcodes.h.html)
- [Lua 5.4 source: lstate.h](https://www.lua.org/source/5.4/lstate.h.html)
- [Lua 5.4 Reference Manual](https://www.lua.org/manual/5.4/manual.html)
- [Lua 5.3 Bytecode Reference (Ravi docs)](https://the-ravi-programming-language.readthedocs.io/en/latest/lua_bytecode_reference.html)
- [Notes on the Implementation of Lua 5.3](https://poga.github.io/lua53-notes/print.html)
- [Lua Closure Walkthrough](https://jbush001.github.io/2017/01/12/lua-closure-walkthrough.html)
- [Interesting things about the Lua interpreter (thesephist)](https://thesephist.com/posts/lua/)
