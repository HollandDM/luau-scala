# Value Representation and Table Data Structure for a JVM/Scala Lua Runtime

## Overview

Building a Lua runtime on the JVM requires mapping Lua's dynamic type system onto the JVM's object model without surrendering correctness or performance. This document covers: Lua's eight types; Luau's number model (doubles-only, confirmed); three reference implementations (PUC Lua, LuaJ, gopher-lua); the table's hybrid array+hash design; the border/length operator; string interning; and concrete Scala design recommendations for `LuaValue` and `LuaTable`.

---

## 1. Lua's Eight Types

[Lua 5.4 Reference Manual](https://www.lua.org/manual/5.4/manual.html) specifies exactly eight first-class types:

| Type | C tag | Notes |
|------|-------|-------|
| `nil` | `LUA_TNIL` | singleton; absence of value; false-y |
| `boolean` | `LUA_TBOOLEAN` | `true` / `false`; only `nil` and `false` are falsy |
| `number` | `LUA_TNUMBER` | has integer and float subtypes in Lua 5.3+ |
| `string` | `LUA_TSTRING` | immutable, 8-bit-clean byte sequence; GC-managed |
| `function` | `LUA_TFUNCTION` | Lua closures or C functions |
| `table` | `LUA_TTABLE` | associative array; only first-class composite |
| `userdata` | `LUA_TUSERDATA` | opaque C data; full (GC) or light (raw pointer) |
| `thread` | `LUA_TTHREAD` | coroutine; not OS thread |

### 1.1 Number Subtypes: Lua 5.3+ vs Luau

**PUC Lua 5.4** distinguishes two number sub-variants via `makevariant(t,v)` bits in `lobject.h`:

```c
// Lua 5.4 lobject.h
typedef union Value {
  struct GCObject *gc;
  void *p;
  lua_CFunction f;
  lua_Integer i;   // LUA_VNUMINT: 64-bit signed integer
  lua_Number n;    // LUA_VNUMFLT: 64-bit IEEE 754 double
  lu_byte ub;
} Value;

typedef struct TValue {
  Value value_;
  lu_byte tt_;     // type tag with variant bits
} TValue;
```

`LUA_VNUMINT` and `LUA_VNUMFLT` occupy bits 4–5 of `tt_`. This allows the VM to route integer arithmetic through CPU integer instructions and avoid float rounding on bitwise ops.

**Luau** (Roblox's fork) uses **doubles exclusively**. Confirmed from the [Luau integer RFC discussion](https://github.com/luau-lang/luau/discussions/242): "Currently, luau numbers are doubles, which offers 53 bits of lossless integer precision." The Luau maintainers explicitly rejected native 64-bit integers: "We do not plan to have 64-bit integers in Luau." An [open RFC](https://rfcs.luau.org/type-long-integer.html) proposes adding them, but as of 2026 it remains unimplemented. Luau's TValue has 96 bits of space total, enough to hold an `i64` without heap allocation — but the design has not shipped.

**Implication for a Scala runtime targeting Luau semantics:** no integer subtype; all numbers are `Double`. Targeting PUC Lua 5.4 semantics requires tracking integer vs. float.

---

## 2. PUC Lua's Internal Value Representation

### 2.1 TValue Tagged Union

Source: [`lobject.h`](https://www.lua.org/source/5.4/lobject.h.html)

Every Lua value on the C stack or heap is a `TValue`: a `Value` union (8 bytes, holding either a pointer or a primitive) plus a `lu_byte tt_` tag. Total: 9 bytes, but alignment pads it to 16 bytes on 64-bit systems.

The tag byte encodes both the base type (lower 4 bits) and a variant (upper bits via `makevariant`). For numbers:
- `LUA_VNUMINT = makevariant(LUA_TNUMBER, 0)` — integer
- `LUA_VNUMFLT = makevariant(LUA_TNUMBER, 1)` — float

For strings:
- `LUA_VSHRSTR = makevariant(LUA_TSTRING, 0)` — short, interned
- `LUA_VLNGSTR = makevariant(LUA_TSTRING, 1)` — long, not interned

### 2.2 LuaJIT NaN-Boxing

LuaJIT takes a different approach: every value is a single 64-bit word. IEEE 754 doubles have a large NaN space (exponent all-1s, fraction nonzero). LuaJIT encodes non-double values as NaN payloads:

```
[13 bits: 1s = NaN marker][4 bits: itype][47 bits: payload]
```

Type tags use bitwise-NOT constants (`~0u` = nil, `~1u` = false, `~2u` = true, etc.). Type extraction: `itype(o) = (o)->it64 >> 47`. GC object pointers fit in 47 bits (128 TB address space). This packs a full Lua value into 8 bytes with zero boxing overhead.

NaN-boxing is not viable on the JVM because the JVM controls object representation. Attempting to encode types in NaN payload bits would require working with raw `long` values through `Double.longBitsToDouble`, making every operation a bitwise decode — far more expensive than `instanceof` checks on a sealed type hierarchy.

---

## 3. String Interning

### 3.1 PUC Lua Strategy

Source: [`lstring.c`](https://www.lua.org/source/5.4/lstring.c.html), [`lstring.h`](https://www.lua.org/source/5.4/lobject.h.html)

PUC Lua splits strings at `LUAI_MAXSHORTLEN` (default 40 bytes):

**Short strings** (`shrlen <= 40`): stored in `strt`, a global hash table in `lua_State`. Every short string is interned — allocation walks the hash chain first; if found, returns the existing pointer. Short-string equality reduces to **pointer comparison** (`==` on `TString*`), making `rawequal` O(1).

```c
typedef struct TString {
  CommonHeader;
  lu_byte extra;        // reserved word flag or "has hash"
  lu_byte shrlen;       // length if short; 0xFF if long
  unsigned int hash;
  union {
    size_t lnglen;      // used if long
    struct TString *hnext; // hash chain if short
  } u;
  char contents[1];    // flexible array member
} TString;
```

**Long strings** (`shrlen == 0xFF`): allocated directly, not interned. Hash computed lazily on first comparison (`extra` flag tracks whether hash is valid). Equality falls back to `memcmp`.

**GC interaction**: dead short strings awaiting collection are "resurrected" by the GC cycle (color reset) before sweep; this prevents dangling intern table entries.

### 3.2 LuaJ String Handling

LuaJ wraps strings as `LuaString` objects backed by `byte[]`. No interning by default. Equality uses `Arrays.equals`. For a JVM runtime, Java's own `String.intern()` is a possible but generally poor choice (PermGen/Metaspace pressure, JVM-controlled pool). Better: a `ConcurrentHashMap<String, LuaString>` scoped to the runtime state, with soft references or explicit eviction.

### 3.3 Recommendation for Scala Runtime

Maintain a `StringTable` per `LuaState`:

```scala
class StringTable {
  private val intern = new java.util.WeakHashMap[String, LuaString]()
  
  def internString(s: String): LuaString =
    intern.computeIfAbsent(s, k => new LuaString(k))
}
```

- Short strings (≤ 40 chars): intern unconditionally, enabling identity equality.
- Long strings: skip intern table; use `equals` for comparison.
- Store raw `String` in `LuaString`; JVM already interns string literals, and `String` is immutable and hash-cached.

---

## 4. LuaJ Value Representation

Source: [`LuaValue.java`](https://github.com/luaj/luaj/blob/master/src/core/org/luaj/vm2/LuaValue.java)

LuaJ uses a **sealed class hierarchy** rooted at `LuaValue extends Varargs`. Every Lua value is a heap object. Type constants:

```java
static final int TNIL         = 0;
static final int TBOOLEAN     = 1;
static final int TLIGHTUSERDATA = 2;
static final int TNUMBER      = 3;
static final int TSTRING      = 4;
static final int TTABLE       = 5;
static final int TFUNCTION    = 6;
static final int TUSERDATA    = 7;
static final int TTHREAD      = 8;
// LuaJ internals:
static final int TINT         = -2; // integer subtype
```

Subclass hierarchy:
```
LuaValue
├── LuaNil          (singleton _NIL)
├── LuaBoolean      (singletons _TRUE, _FALSE)
├── LuaNumber
│   ├── LuaInteger  (wraps int)
│   └── LuaDouble   (wraps double)
├── LuaString       (wraps byte[])
├── LuaTable
├── LuaFunction
│   ├── LuaClosure
│   └── LibFunction variants
├── LuaUserdata
└── LuaThread
```

Instance pooling:
- `NIL`, `TRUE`, `FALSE`: singletons
- `ZERO = LuaInteger.valueOf(0)`, `ONE`, `MINUSONE`: constants
- `NILS[250]`: array of NIL values for stack initialization via `System.arraycopy`
- `LuaInteger.valueOf(int)`: caches `-1` through `~256` (JVM `Integer` pool strategy)

**Boxing cost**: every `LuaDouble` or `LuaInteger` is a heap object. In tight arithmetic loops, this generates massive GC pressure — each `a + b` allocates a new `LuaDouble`. LuaJ mitigates by providing `LuaValue.add(double)` dispatch that avoids intermediate objects where possible, but the fundamental boxing still occurs at return time.

---

## 5. Gopher-Lua Value Representation

Source: [`table.go`](https://github.com/yuin/gopher-lua/blob/master/table.go)

Gopher-lua uses a Go interface `LValue` with concrete types:

```go
type LValue interface { Type() LValueType; String() string; ... }

type LNilType     struct{}
type LBool        bool
type LNumber      float64   // doubles only
type LString      string
type *LTable      struct{ ... }
type *LFunction   struct{ ... }
type *LUserData   struct{ ... }
type *LState      struct{ ... }  // thread
```

`LNumber` is `float64` (unboxed in Go — no boxing overhead). `LBool` is `bool`. Primitives only box when assigned to `LValue` interface slots, which happens at table insertion/function call boundaries. Go's escape analysis tries to avoid heap allocation for short-lived interface assignments.

---

## 6. Lua Table: Hybrid Array + Hash Design

### 6.1 PUC Lua Table Structure

Sources: [`ltable.c` (5.4)](https://www.lua.org/source/5.4/ltable.c.html), [Notes on Lua 5.3 Implementation](https://poga.github.io/lua53-notes/table.html), [Lua 5.0 implementation paper](https://www.inf.puc-rio.br/~roberto/talks/lua5-imp.pdf)

```c
typedef struct Table {
  CommonHeader;          // GC header
  TValue *array;         // array part: 1-indexed values
  Node *node;            // hash part: flat array of Node
  Node *lastfree;        // pointer to last free node in hash
  struct Table *metatable;
  unsigned int alimit;   // "array limit" (may be non-real)
  int lsizenode;         // log2 of hash size
} Table;

typedef union TKey {
  struct { TValuefields; int next; } nk;  // next: collision chain offset
  TValue tvk;
} TKey;

typedef struct Node {
  TValue i_val;
  TKey   i_key;
} Node;
```

The `Node.i_key.nk.next` field is an integer offset (not a pointer) to the next node in the collision chain, enabling chain traversal without extra allocations. `lastfree` scans backwards through the `node` array to find free slots.

### 6.2 Array Part

Non-negative integer keys `1..n` are **candidates** for the array part. The array is a flat `TValue[]`. Access is O(1): `array[key - 1]`.

"Array part" size is chosen during rehash to maximize utilization: find the largest power-of-two N such that more than half of slots `1..N` are occupied. Keys `> N` spill to the hash part.

`alimit` is a hint, not the true array size. The true size is determined by `luaH_realasize()`.

### 6.3 Hash Part: Chained Scatter with Brent's Variation

The hash part is a flat `Node[]` of power-of-2 size. Each key maps to a **main position** via:
- integers: `hashint(t, key)` — modulo hash
- strings: power-of-2 modulo on precomputed hash
- other: pointer hash

**Main invariant**: if a node is NOT in its main position, then the node occupying its main position IS in its main position. This means displaced nodes always live at a free slot, not another node's main slot.

**Insertion via Brent's variation**:
1. Compute main position `mp` for new key.
2. If `mp` is free: insert there.
3. If `mp` is occupied by node `other`:
   - If `other` IS at its own main position: insert new key at a free slot `f`, add `f` to `other`'s chain.
   - If `other` is NOT at its own main position (it was displaced): move `other` to `f`, fix the chain that pointed to `mp`, insert new key at `mp`.

This keeps probe chains short (each chain element is at its true main position or a free position directly reachable from it).

**Free slot tracking**: `lastfree` starts at end of `node` array, scans backward. When `lastfree` hits index 0, no free slots remain → trigger `rehash`.

### 6.4 Rehash Algorithm

Triggered when no free node slot remains. `rehash(t, key)`:

1. Allocate `int nums[MAXBITS]`. `nums[i]` = count of integer keys in range `(2^(i-1), 2^i]`.
2. Call `numusearray(t, nums)`: walk array part, count non-nil values into `nums` buckets.
3. Call `numusehash(t, nums, &totaluse)`: walk hash part, count integer keys into `nums` buckets, count all keys into `totaluse`.
4. Call `computesizes(nums, &nasize)`: find largest power-of-two `nasize` where `sum(nums[0..log(nasize)]) > nasize/2`. This ensures array part is >50% full.
5. Call `luaH_resize(t, nasize, totaluse - nasize)` to reallocate.

`luaH_resize`:
1. Allocate new hash array of size `nhsize`.
2. If shrinking array: move displaced array elements to new hash.
3. Reallocate array to `nasize`.
4. Reinsert all old hash nodes into new layout.

### 6.5 The Length Operator (`#`): Border Semantics

From [Lua 5.4 manual](https://www.lua.org/manual/5.4/manual.html):

A **border** of table `t` is any non-negative integer satisfying:
```
(border == 0 or t[border] ~= nil) and (t[border + 1] == nil or border == math.maxinteger)
```

A **sequence** has exactly one border. `#t` returns any border for non-sequences — behavior is implementation-defined and may differ between runs if table internal state changes.

`luaH_getn` in `ltable.c`:
1. If `array[alimit - 1]` is nil: binary search within array for the border.
2. Else if `alimit < MAXASIZE` and `array[alimit]` is nil: `alimit` is itself a border.
3. Else: binary search in hash part.

Guaranteed O(log n). For sequence tables (no holes), returns array length directly.

**Critical**: non-sequence tables with holes give unspecified results. A correct Scala implementation must preserve this non-determinism (or document deviation).

### 6.6 LuaJ Table

Source: [`LuaTable.java`](https://github.com/luaj/luaj/blob/master/src/core/org/luaj/vm2/LuaTable.java)

```java
LuaValue[] array;     // 1-indexed, array[0] unused or nil
Slot[] hash;          // open addressing
int hashEntries;
Metatable m_metatable;
```

`Slot` is an interface with `StrongSlot`, `WeakSlot`, `DeadSlot` for weak table support. The hash uses power-of-2 sizing with linear probing (not chained scatter). `RawGet(LuaValue key)` routes:
- `LuaInteger` with `1 <= k <= array.length`: direct `array[k-1]`
- Other: `hashSlot(key)` then linear probe

`rehash()` mirrors PUC Lua: counts per log2 bucket, picks optimal array size, reallocates.

`rawlen()` / `length()`: binary search from end of array for last non-nil.

### 6.7 Gopher-Lua Table

```go
type LTable struct {
  array     []LValue
  strdict   map[string]LValue
  dict      map[LValue]LValue
  keys      []LValue
  k2i       map[LValue]int
  Metatable LValue
}
```

Three-way key routing:
- Positive integer keys → `array` slice (1-indexed, `array[k-1]`)
- String keys → `strdict`
- All others → `dict`

`keys` + `k2i` maintain insertion order for `Next()` iteration (mirrors `lua_next`). Go's built-in maps handle rehashing internally; no manual resize logic. `Len()` scans backward from `len(array)` for last non-nil.

---

## 7. Scala Value ADT Design

### 7.1 Sealed Trait Hierarchy

```scala
// core/LuaValue.scala
sealed trait LuaValue

// Singletons
case object LuaNil extends LuaValue

// Boolean: only two values
sealed abstract class LuaBoolean(val value: Boolean) extends LuaValue
case object LuaTrue  extends LuaBoolean(true)
case object LuaFalse extends LuaBoolean(false)

// Number: two subtypes for Lua 5.4; one for Luau
final class LuaInt(val value: Long) extends LuaValue    // Lua 5.4 only
final class LuaFloat(val value: Double) extends LuaValue

// String: wraps interned java.lang.String
final class LuaString private (val value: String) extends LuaValue

// Composite / heap types
final class LuaTable(/* see §8 */) extends LuaValue
sealed trait LuaFunction extends LuaValue
final class LuaClosure(val proto: Proto, val upvals: Array[UpVal]) extends LuaFunction
final class LuaNativeFunction(val fn: (LuaState, Array[LuaValue]) => Array[LuaValue]) extends LuaFunction
final class LuaUserdata(val value: AnyRef) extends LuaValue
final class LuaThread(val state: LuaState) extends LuaValue
```

For **Luau** (doubles-only), drop `LuaInt` entirely. Use `LuaFloat` for all numbers.

### 7.2 Type Dispatch Pattern

Scala 3 sealed traits + `match` compile to JVM `checkcast`/`instanceof` sequences. With 8 cases, the JIT inlines and devirtualizes after warmup. Use `@switch` only on `Int` tags; for sealed traits use pattern matching.

```scala
def typeOf(v: LuaValue): String = v match
  case LuaNil          => "nil"
  case _: LuaBoolean   => "boolean"
  case _: LuaInt       => "number"
  case _: LuaFloat     => "number"
  case _: LuaString    => "string"
  case _: LuaTable     => "table"
  case _: LuaFunction  => "function"
  case _: LuaUserdata  => "userdata"
  case _: LuaThread    => "thread"
```

### 7.3 Boxing Strategies and JVM Performance

**Problem**: `LuaFloat(3.14)` allocates a heap object. In tight Lua loops:
```lua
local sum = 0
for i = 1, 1000000 do sum = sum + i end
```
Each `sum + i` could allocate a new `LuaFloat`. With JIT escape analysis, many allocations get eliminated — but this is JIT-dependent and not guaranteed for complex control flow.

**Strategy 1: Small integer cache** (like `Integer.valueOf`)

```scala
object LuaInt {
  private val cache = Array.tabulate(257)(i => new LuaInt(i - 1L)) // -1..255
  def apply(v: Long): LuaInt =
    if v >= -1 && v <= 255 then cache((v + 1).toInt)
    else new LuaInt(v)
}
```

**Strategy 2: Unboxed arithmetic in the VM core**

Keep `LuaValue` as the user-facing type but operate on unboxed primitives in the VM dispatch loop:

```scala
// VM register file stores LuaValue, but arithmetic unpacks/repacks
def arith_add(a: LuaValue, b: LuaValue): LuaValue = (a, b) match
  case (LuaInt(x), LuaInt(y))     => LuaInt(x + y)       // no alloc if cached
  case (LuaFloat(x), LuaFloat(y)) => new LuaFloat(x + y) // allocates
  case (LuaInt(x), LuaFloat(y))   => new LuaFloat(x.toDouble + y)
  case (LuaFloat(x), LuaInt(y))   => new LuaFloat(x + y.toDouble)
  case _                          => metamethod_add(a, b)
```

For the hot path (float arithmetic), every result allocates. Mitigation:

**Strategy 3: Specialize the VM register array**

Rather than `Array[LuaValue]`, maintain parallel unboxed arrays:

```scala
class RegisterFile(size: Int) {
  val tags:    Array[Byte]   = new Array[Byte](size)   // type tag
  val longs:   Array[Long]   = new Array[Long](size)   // ints + doubles as bits
  val objects: Array[AnyRef] = new Array[AnyRef](size) // strings, tables, etc.

  inline def readDouble(r: Int): Double = java.lang.Double.longBitsToDouble(longs(r))
  inline def writeDouble(r: Int, v: Double): Unit =
    tags(r) = TAG_FLOAT
    longs(r) = java.lang.Double.doubleToRawLongBits(v)
}
```

Tags: `TAG_NIL=0, TAG_BOOL=1, TAG_INT=2, TAG_FLOAT=3, TAG_OBJ=4`. Float values stored as raw bits in `longs`, extracted via `longBitsToDouble` — zero allocation for float arithmetic in the register file. Objects (strings, tables) stored in `objects`.

This is the approach used by high-performance JVM language runtimes (e.g., TruffleRuby's frame descriptors).

**Strategy 4: Scala 3 opaque types for type aliases**

```scala
opaque type LuaNumber = Double
```

Zero runtime overhead inside the defining scope. But: opaque types box when used as generic type parameters (e.g., `Array[LuaNumber]` becomes `Array[Double]` — fine; `List[LuaNumber]` becomes `List[Object]` — boxes). Safe for the interpreter's inner loop if generics are avoided.

**Strategy 5: Value classes (AnyVal)**

```scala
final class LuaFloat(val value: Double) extends AnyVal with LuaValue
```

**Does not work.** A value class cannot extend a non-universal trait. `extends LuaValue` makes `LuaFloat` a normal class. The `AnyVal` annotation is ignored when the class extends a non-AnyVal type.

### 7.4 Boolean and Nil Singletons

Always use `object` (Scala's module singleton) for nil and boolean values. No allocation, identity equality, pattern match deoptimization avoided:

```scala
// Fast truthiness check — no allocation
def isTruthy(v: LuaValue): Boolean = v match
  case LuaNil | LuaFalse => false
  case _                 => true
```

JIT sees only two cold paths; all other values are truthy.

---

## 8. Scala LuaTable Implementation

### 8.1 Core Design

```scala
final class LuaTable(
  initArrayCap: Int = 8,
  initHashCap:  Int = 8
) extends LuaValue {

  // Array part: 1-indexed values stored at index - 1
  private var array: Array[LuaValue] = new Array[LuaValue](initArrayCap)
  private var arraySize: Int = 0      // highest populated index

  // Hash part: open-addressed with tombstones, or chained scatter
  private var keys:   Array[LuaValue] = new Array[LuaValue](initHashCap)
  private var values: Array[LuaValue] = new Array[LuaValue](initHashCap)
  private var next:   Array[Int]      = new Array[Int](initHashCap)  // chain offsets
  private var hashMask: Int = initHashCap - 1
  private var hashCount: Int = 0

  var metatable: LuaTable = null
}
```

### 8.2 Key Routing

```scala
def rawGet(key: LuaValue): LuaValue = key match
  case LuaInt(i) if i >= 1 && i <= arraySize =>
    val v = array((i - 1).toInt)
    if v == null then LuaNil else v

  case LuaFloat(f) =>
    val i = f.toLong
    if i.toDouble == f then rawGet(LuaInt(i))  // coerce to int key
    else hashGet(key)

  case _ => hashGet(key)

def rawSet(key: LuaValue, value: LuaValue): Unit = key match
  case LuaNil   => throw LuaError("table index is nil")
  case LuaFloat(f) if f.isNaN => throw LuaError("table index is NaN")
  case LuaInt(i) if i >= 1 =>
    val idx = (i - 1).toInt
    if idx < array.length then
      array(idx) = if value == LuaNil then null else value
      if idx >= arraySize && value != LuaNil then arraySize = idx + 1
    else hashSet(key, value)
  case _ => hashSet(key, value)
```

Float-to-int coercion is critical: `t[1.0]` and `t[1]` are the same key in Lua.

### 8.3 Hash Part: Chained Scatter

Following PUC Lua's approach (adapted for JVM arrays):

```scala
private def mainPosition(key: LuaValue): Int =
  (key.hashCode & 0x7FFFFFFF) & hashMask

private def hashGet(key: LuaValue): LuaValue =
  var pos = mainPosition(key)
  while pos != -1 do
    if keys(pos) != null && keys(pos) == key then
      return values(pos)
    pos = next(pos)  // -1 terminates chain
  LuaNil

private def hashSet(key: LuaValue, value: LuaValue): Unit =
  var pos = mainPosition(key)
  // check existing
  var cur = pos
  while cur != -1 do
    if keys(cur) != null && keys(cur) == key then
      values(cur) = value
      return
    cur = next(cur)
  // insert new: find free slot, apply Brent's variation
  val free = findFreeSlot()
  if free == -1 then
    rehash(key)
    rawSet(key, value)  // retry after rehash
    return
  // Brent: if main pos occupied by node not at its own main pos, move it
  if keys(pos) != null then
    val occupantMain = mainPosition(keys(pos))
    if occupantMain != pos then
      // occupant is displaced: move it to free, reclaim pos for new key
      relocate(pos, free)
      keys(pos) = key; values(pos) = value; next(pos) = -1
    else
      // occupant is at its main pos: chain new key at free
      keys(free) = key; values(free) = value
      next(free) = next(pos); next(pos) = free  // incorrect — needs end-of-chain
  else
    keys(pos) = key; values(pos) = value; next(pos) = -1
  hashCount += 1
```

(Production implementation needs careful end-of-chain traversal and the `lastfree` pointer pattern from PUC Lua.)

### 8.4 Rehash

```scala
private def rehash(newKey: LuaValue): Unit =
  // count integer keys per log2 bucket
  val nums = new Array[Int](32)
  var totalInt = countIntKeys(nums)  // scan array + hash
  // add newKey if integer
  newKey match
    case LuaInt(i) if i > 0 => addToNums(nums, i); totalInt += 1
    case _ =>

  // find optimal array size: largest 2^k with >50% utilization
  val newArraySize = computeOptimalArraySize(nums, totalInt)
  val newHashSize  = nextPowerOf2(hashCount + 1)  // conservative

  resize(newArraySize, newHashSize)
```

### 8.5 Length Operator

```scala
def length(): Long =
  // Check __len metamethod first
  if metatable != null then
    val mm = metatable.rawGet(LuaString("__len"))
    if mm != LuaNil then return callMetamethod(mm, this)

  rawLength()

def rawLength(): Long =
  // Binary search for border in array
  if arraySize == 0 then return 0L
  // t[arraySize] != nil and t[arraySize+1] == nil: border found
  val last = array(arraySize - 1)
  if last != null && last != LuaNil then
    // check if arraySize+1 is nil (in hash)
    if rawGet(LuaInt(arraySize.toLong + 1)) == LuaNil then
      return arraySize.toLong
  // binary search within array for last contiguous non-nil
  binarySearchBorder()
```

### 8.6 Next / Iteration

Lua's `next(t, k)` requires deterministic but unspecified iteration order. Implement as:
1. `k == nil`: return first array element (index 1 if non-nil, else scan).
2. Integer key in array range: advance to next non-nil array slot, then spill to hash.
3. In hash: find current node, advance to next non-nil node (linear scan from current hash index).

Do NOT rely on insertion order unless implementing a Luau extension. PUC Lua's `next` returns keys in internal storage order (array first, then hash in node-array order).

---

## 9. Performance Analysis and Recommendations

### 9.1 Boxing Cost Summary

| Value | LuaJ | Gopher-Lua | Recommended Scala |
|-------|------|------------|-------------------|
| `nil` | singleton | `LNilType{}` ptr | singleton object, zero alloc |
| `true/false` | singletons | bool (unboxed until interface) | singleton objects |
| integer | `LuaInteger(int)` heap | `LNumber(float64)` unboxed | `LuaInt(long)` — cache -1..255 |
| float | `LuaDouble(double)` heap | `LNumber(float64)` unboxed | `LuaFloat(double)` heap; use register file for hot path |
| string | `LuaString(byte[])` heap | `LString(string)` ptr | `LuaString(String)` heap; intern short |
| table | `LuaTable` heap | `*LTable` ptr | `LuaTable` heap |

### 9.2 JIT-Friendly Patterns

**Pattern match on sealed trait**: JVM JIT (HotSpot C2, GraalVM) sees `instanceof` checks. With a sealed hierarchy of ~10 leaves, C2 generates a type-check decision tree. After 10,000 iterations, branch prediction dominates. Profile-guided optimization kicks in for the most common type (typically `LuaFloat` for numeric code).

**Avoid `Any` / `AnyRef` arrays for register files**: `Array[LuaValue]` stores object references, causing pointer chasing. Use the parallel primitive arrays approach (§7.3) for the VM's register frame.

**Avoid allocating intermediate values in arithmetic**: Pass results by out-parameter or use a `LuaValue` cursor pattern. Alternatively, use Scala `inline` methods in Scala 3 to specialize arithmetic dispatch at call sites.

**String equality**: For interned short strings, use `eq` (reference equality) before `==`. Add a fast path:

```scala
def luaEquals(a: LuaValue, b: LuaValue): Boolean = (a, b) match
  case (x, y) if x eq y => true  // covers nil, booleans, interned strings
  case (LuaFloat(x), LuaFloat(y)) => x == y || (x.isNaN && y.isNaN)  // NaN != NaN per Lua
  case (LuaInt(x), LuaInt(y))   => x == y
  case (LuaInt(x), LuaFloat(y)) => x.toDouble == y
  case (LuaFloat(x), LuaInt(y)) => x == y.toDouble
  case (LuaString(x), LuaString(y)) => x == y  // String.equals, O(n) for long
  case _ => false
```

Note: Lua NaN inequality — `NaN ~= NaN` is true. `t[0/0]` throws "table index is NaN".

### 9.3 Table Memory Layout

For cache efficiency, the array part should be a flat `Array[AnyRef]` (since `LuaValue` is a reference type) — JVM lays these out contiguously. The hash part's `Node` equivalent can use parallel arrays (keys + values + next-chain) for better cache behavior than an array of Node objects:

```scala
// parallel arrays beat array-of-structs on JVM due to object header overhead
private var hashKeys:   Array[LuaValue] = _  // null = empty slot
private var hashVals:   Array[LuaValue] = _
private var hashNext:   Array[Int]      = _  // -1 = end of chain
```

### 9.4 Thread Safety

Lua tables are single-threaded by design. Do not add synchronization to `LuaTable`. If the runtime supports multiple coroutines, they share the same OS thread via cooperative scheduling — no concurrent table access.

---

## 10. Complete Scala Value ADT (Final Recommendation)

```scala
package lua.runtime

// ---- Value hierarchy ----

sealed trait LuaValue

object LuaNil extends LuaValue:
  override def toString = "nil"

sealed abstract class LuaBoolean(val value: Boolean) extends LuaValue
object LuaTrue  extends LuaBoolean(true)
object LuaFalse extends LuaBoolean(false)

object LuaBoolean:
  def apply(b: Boolean): LuaBoolean = if b then LuaTrue else LuaFalse

// Lua 5.4 integer subtype; omit for Luau-only target
final class LuaInt private (val value: Long) extends LuaValue:
  override def hashCode: Int = java.lang.Long.hashCode(value)
  override def equals(o: Any): Boolean = o match
    case other: LuaInt => value == other.value
    case _ => false

object LuaInt:
  private val CacheLow  = -1L
  private val CacheHigh = 255L
  private val cache: Array[LuaInt] =
    Array.tabulate((CacheHigh - CacheLow + 1).toInt)(i => new LuaInt(i + CacheLow))
  def apply(v: Long): LuaInt =
    if v >= CacheLow && v <= CacheHigh then cache((v - CacheLow).toInt)
    else new LuaInt(v)
  def unapply(v: LuaInt): Some[Long] = Some(v.value)

final class LuaFloat(val value: Double) extends LuaValue:
  override def hashCode: Int = java.lang.Double.hashCode(value)
  override def equals(o: Any): Boolean = o match
    case other: LuaFloat => value == other.value
    case _ => false

object LuaFloat:
  def unapply(v: LuaFloat): Some[Double] = Some(v.value)

// Interned string: use companion factory for short strings
final class LuaString private[runtime] (val value: String) extends LuaValue:
  override def hashCode: Int = value.hashCode
  override def equals(o: Any): Boolean = o match
    case other: LuaString => (this eq other) || value == other.value
    case _ => false

sealed trait LuaFunction extends LuaValue

final class LuaClosure(
  val proto:   Proto,
  val upvals:  Array[UpVal]
) extends LuaFunction

final class LuaNativeFunction(
  val name: String,
  val fn:   (LuaState, Varargs) => Varargs
) extends LuaFunction

final class LuaUserdata(val obj: AnyRef, var metatable: LuaTable) extends LuaValue

final class LuaThread(val coro: Coroutine) extends LuaValue

// LuaTable: see §8

// ---- Convenience extractors for number coercion ----

object LuaNumber:
  def unapply(v: LuaValue): Option[Double] = v match
    case LuaFloat(d) => Some(d)
    case LuaInt(i)   => Some(i.toDouble)
    case _           => None
```

---

## 11. Summary of Design Decisions

| Concern | Recommendation | Rationale |
|---------|---------------|-----------|
| Number model | `LuaFloat` only for Luau; `LuaInt + LuaFloat` for PUC 5.4 | Luau rejects integers by design |
| Boxing strategy | Sealed trait hierarchy + integer cache + parallel register arrays | JIT deoptimization risk lower than tagged `Long`; heap alloc for floats mitigated by escape analysis in register file |
| String interning | `WeakHashMap<String, LuaString>` per state, ≤40 chars | Enables O(1) equality for short strings; avoids global `String.intern()` heap pressure |
| Table array part | `Array[LuaValue]` with null for absent | Flat, cache-friendly; null sentinel avoids `LuaNil` allocation |
| Table hash part | Parallel arrays (keys/values/next) + Brent's variation | Better cache behavior than array-of-Node objects |
| Length operator | Binary search; document non-sequence non-determinism | Must match PUC Lua semantics |
| Thread safety | None on `LuaTable` | Lua's coroutine model is cooperative, single-threaded |

---

## Sources

- [Lua 5.4 Reference Manual](https://www.lua.org/manual/5.4/manual.html)
- [Lua 5.4 `lobject.h` source](https://www.lua.org/source/5.4/lobject.h.html)
- [Lua 5.4 `ltable.c` source](https://www.lua.org/source/5.4/ltable.c.html)
- [Lua 5.4 `lstring.c` source](https://www.lua.org/source/5.4/lstring.c.html)
- [Notes on the Implementation of Lua 5.3 — Table](https://poga.github.io/lua53-notes/table.html)
- [Notes on the Implementation of Lua 5.3 — String](https://poga.github.io/lua53-notes/string.html)
- [LuaJ `LuaValue.java`](https://github.com/luaj/luaj/blob/master/src/core/org/luaj/vm2/LuaValue.java)
- [LuaJ README](https://github.com/luaj/luaj/blob/master/README.md)
- [LuaJ `LuaValue` API docs (3.0)](http://luaj.org/luaj/3.0/api/org/luaj/vm2/LuaValue.html)
- [Gopher-Lua `table.go`](https://github.com/yuin/gopher-lua/blob/master/table.go)
- [Luau native integers discussion #242](https://github.com/luau-lang/luau/discussions/242)
- [Luau integer issue #217](https://github.com/Roblox/luau/issues/217)
- [Luau 64-bit integer RFC](https://rfcs.luau.org/type-long-integer.html)
- [LuaJIT NaN-boxing analysis (Medium)](https://medium.com/@eclipseflowernju/luajit-source-code-analysis-part-2-data-type-59b501d59e7f)
- [Tagged vs Untagged Unions in Scala](https://alexn.org/blog/2025/04/02/tagged-vs-untagged-unions-in-scala/)
- [Scala specialization and boxing](https://scalac.io/blog/specialized-generics-avoid-object-instantiation/)
- [Scala @switch annotation](https://www.baeldung.com/scala/switch-annotation)
- [Scala Opaque Types](https://docs.scala-lang.org/scala3/book/types-opaque-types.html)
- [Lua Performance Tips (Roberto Ierusalimschy)](https://www.lua.org/gems/sample.pdf)
