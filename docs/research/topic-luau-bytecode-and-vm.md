# Luau Bytecode Format and VM Dispatch: Deep Technical Reference

Sources consulted: [Bytecode.h](https://github.com/luau-lang/luau/blob/master/Common/include/Luau/Bytecode.h), [lvmload.cpp](https://github.com/luau-lang/luau/blob/master/VM/src/lvmload.cpp), [DeepWiki bytecode format](https://deepwiki.com/luau-lang/luau/4.2-bytecode-format), [luau performance docs](https://vegorov-rbx.github.io/luau/performance), [generalized iteration RFC](https://rfcs.luau.org/generalized-iteration.html), [builtin definitions](https://deepwiki.com/luau-lang/luau/4.3-builtin-definitions-and-constant-folding), [compatibility notes](https://luau.org/compatibility/).

---

## 1. Overview and Position Relative to Lua 5.1

Luau is a Roblox-maintained fork of Lua 5.1 that diverges sharply at the bytecode layer. Key structural differences:

| Dimension | Lua 5.1 | Luau |
|---|---|---|
| Instruction width | 32-bit | 32-bit (word-code) |
| Encoding formats | A/B/C, A/Bx, A/sBx | A/B/C, A/D (signed 16-bit), E (signed 24-bit), + AUX word |
| Max registers/function | 255 | 254 (register 255 reserved) |
| Max upvalues/function | 60 | 200 |
| Max constants/function | 2^18 | 2^23 (extended via AUX) |
| Value representation | 12-byte tagged union | 16-byte tagged union (fits float3 vector natively) |
| NaN boxing | No | No (keeps 16-byte tagged for 64-bit double + pointer + vector) |
| Bytecode versioning | None | Explicit version byte, range [3, 11], target 6 |
| Type annotations in bytecode | No | Yes (v4+, `typeinfo` stream on proto) |
| Tail calls | Yes | Removed (simplifies stack traces) |
| `__gc` finalizers | Yes | Removed (sandboxing) |
| `loadstring(bytecode)` exposure | Yes | Removed (sandboxing) |
| Fast-path builtins | No | FASTCALL / FASTCALL1 / FASTCALL2 / FASTCALL2K / FASTCALL3 |
| Import short-circuit | No | GETIMPORT with 1/2/3-level chain |
| Method call short-circuit | No | NAMECALL + `__namecall` hook |
| Numeric for loop | FORPREP + FORLOOP | FORNPREP + FORNLOOP (same concept, renamed) |
| Generic for loop | FORPREP + TFORLOOP | FORGPREP + FORGLOOP + specialized FORGPREP_NEXT/INEXT |
| Integer division | No | IDIV / IDIVK (v4+) |
| Reverse arithmetic | No | SUBRK / DIVRK (v5+, constant on left side) |
| Vector constants | No | LBC_CONSTANT_VECTOR (v5+) |
| 64-bit integer constants | No | LBC_CONSTANT_INTEGER (v8+) |
| Coverage tracking | No | COVERAGE opcode |

Luau is register-based like Lua 5.1, not stack-based. Both use `Proto` (function prototype) as the central compilation unit.

---

## 2. Bytecode Binary Layout

### 2.1 Top-Level Structure

```
[version: u8]                     // must be in [LBC_BYTECODE_MIN, LBC_BYTECODE_MAX]
[typesversion: u8]                // present if version >= 4; must be in [1, LBC_BYTECODE_TYPE_MAX]
[string_count: varint]
for i in 0..string_count:
    [length: varint]
    [bytes: u8 * length]
[userdata_remap_count: u8]        // present if typesversion == 3
for i in 0..userdata_remap_count: // maps bytecode userdata type IDs to runtime atom IDs
    [type_name_index: u8]
[proto_count: varint]
for i in 0..proto_count:
    [proto_body]                  // see section 2.2
[main_proto_index: varint]        // index into proto array, root of execution
```

Constants: `LBC_BYTECODE_MIN = 3`, `LBC_BYTECODE_MAX = 11`, `LBC_BYTECODE_TARGET = 6`. `LBC_TYPE_VERSION_MIN = 1`, `LBC_TYPE_VERSION_MAX = 3`, `LBC_TYPE_VERSION_TARGET = 3`.

### 2.2 Proto Body

```
[maxstacksize: u8]
[numparams: u8]
[nups: u8]           // number of upvalues
[is_vararg: u8]      // 0 or 1

// present if version >= 4
[flags: u8]          // LuauProtoFlag bitmask
[typeinfo_size: varint]
if typeinfo_size > 0:
    [typeinfo: u8 * typeinfo_size]   // LuauBytecodeType stream

[instruction_count: varint]
[instructions: u32 * instruction_count]   // each is a 32-bit word

[constant_count: varint]
for each constant:
    [tag: u8]               // LuauBytecodeTag
    [payload: see below]

[child_proto_count: varint]
for each child:
    [proto_index: varint]   // index into the global proto array

// debug info (may be absent if compiled with debugLevel=0)
[lineinfo_present: u8]
if lineinfo_present:
    [linegaplog2: u8]       // compression interval = 2^linegaplog2 instructions
    for i in 0..instruction_count:
        [delta: u8]         // cumulative delta; actual offset = sum(deltas[0..i])
    intervals = ((instruction_count - 1) >> linegaplog2) + 1
    last_line = 0
    for i in 0..intervals:
        [abs_delta: i32]    // cumulative; abs_line[i] = sum(abs_deltas[0..i])

[local_count: varint]
for each local:
    [name: string_ref varint]   // 1-based index into string table; 0 = null
    [startpc: varint]
    [endpc: varint]
    [reg: u8]

[upvalue_count: varint]
for each upvalue:
    [name: string_ref varint]

[debugname: string_ref varint]
```

### 2.3 Variable-Length Integer Encoding (readVarInt)

Standard 7-bit continuation encoding. Each byte contributes 7 bits of value; high bit signals continuation:

```cpp
unsigned int result = 0, shift = 0;
uint8_t byte;
do {
    byte = *ptr++;
    result |= (byte & 0x7f) << shift;
    shift += 7;
} while (byte & 0x80);
```

### 2.4 Constant Payload by Tag

| Tag | `LuauBytecodeTag` | Payload |
|---|---|---|
| 0 | `LBC_CONSTANT_NIL` | (none) |
| 1 | `LBC_CONSTANT_BOOLEAN` | `u8` (0 or 1) |
| 2 | `LBC_CONSTANT_NUMBER` | `f64` little-endian |
| 3 | `LBC_CONSTANT_STRING` | `varint` (1-based string table index) |
| 4 | `LBC_CONSTANT_IMPORT` | `u32` import ID (see section 5.3) |
| 5 | `LBC_CONSTANT_TABLE` | `varint` key count, then `varint` key indices (string refs) |
| 6 | `LBC_CONSTANT_CLOSURE` | `varint` proto index |
| 7 | `LBC_CONSTANT_VECTOR` | 4× `f32` (x, y, z, w); v5+ |
| 8 | `LBC_CONSTANT_TABLE_WITH_CONSTANTS` | like TABLE but includes constant values; v7+ |
| 9 | `LBC_CONSTANT_INTEGER` | sign `u8` + variable-length magnitude; v8+ |
| 10 | `LBC_CONSTANT_CLASS_SHAPE` | class shape for v9+ userdata acceleration |

---

## 3. Instruction Encoding

### 3.1 Instruction Word Layout

Every instruction is exactly one 32-bit word, optionally followed by an AUX word (also 32-bit). The opcode always occupies the least-significant byte (bits 0–7).

```
Bits 31–24  Bits 23–16  Bits 15–8  Bits 7–0
    C            B           A        OP       ← ABC encoding
         D (signed 16-bit)   A        OP       ← AD encoding  
              E (signed 24-bit)        OP       ← E encoding (JUMPX only)
```

Extraction macros from `Bytecode.h`:

```c
#define LUAU_INSN_OP(insn)   ((insn) & 0xff)
#define LUAU_INSN_A(insn)    (((insn) >> 8) & 0xff)
#define LUAU_INSN_B(insn)    (((insn) >> 16) & 0xff)
#define LUAU_INSN_C(insn)    (((insn) >> 24) & 0xff)
#define LUAU_INSN_D(insn)    (int32_t(insn) >> 16)          // sign-extends from bit 16
#define LUAU_INSN_E(insn)    (int32_t(insn) >> 8)           // sign-extends from bit 8 (24-bit)
```

AUX word sub-field macros:

```c
#define LUAU_INSN_AUX_A(aux)      ((aux) & 0xff)
#define LUAU_INSN_AUX_B(aux)      (((aux) >> 8) & 0xff)
#define LUAU_INSN_AUX_KV(aux)     ((aux) & 0xffffff)        // 24-bit constant index
#define LUAU_INSN_AUX_KB(aux)     ((aux) & 0x1)             // 1-bit boolean value
#define LUAU_INSN_AUX_NOT(aux)    ((aux) >> 31)             // 1-bit NOT flag
#define LUAU_INSN_AUX_KV16(aux)   ((aux) & 0xffff)
#define LUAU_INSN_AUX_SLOT(aux)   ((aux) >> 16)             // inline cache slot
```

### 3.2 Addressing Ranges

| Entity | Field | Range |
|---|---|---|
| Register | A, B, or C (8-bit) | 0–254 |
| Upvalue | B field | 0–199 |
| Constant (small) | D field | 0–32767 |
| Constant (large) | AUX KV (24-bit) | 0–8,388,607 |
| Jump offset (short) | D field | −32768–+32767 |
| Jump offset (long) | E field (JUMPX) | −8,388,608–+8,388,607 |
| Closure index | D field | 0–32767 |

---

## 4. Full Opcode Table

Opcodes 0–88 as of current master (`LBC_BYTECODE_TARGET = 6`). Listed with encoding format and AUX usage.

```
0   LOP_NOP           –        No operation
1   LOP_BREAK         –        Debugger breakpoint (replaces original opcode on the fly)
2   LOP_LOADNIL       A        R[A] = nil
3   LOP_LOADB         A,B,C   R[A] = (bool)B; if C: pc += 1 (skip next)
4   LOP_LOADN         A,D     R[A] = (number)D  (D is signed 16-bit)
5   LOP_LOADK         A,D     R[A] = K[D]  (D: constant index 0..32767)
6   LOP_MOVE          A,B     R[A] = R[B]
7   LOP_GETGLOBAL     A, AUX  R[A] = globals[K[AUX]]  (AUX: constant string index)
8   LOP_SETGLOBAL     A, AUX  globals[K[AUX]] = R[A]
9   LOP_GETUPVAL      A,B     R[A] = upvalue[B]
10  LOP_SETUPVAL      A,B     upvalue[B] = R[A]
11  LOP_CLOSEUPVALS   A       close open upvalues >= R[A]
12  LOP_GETIMPORT     A,D AUX R[A] = import(AUX)  (D: const idx; AUX encodes import path)
13  LOP_GETTABLE      A,B,C   R[A] = R[B][R[C]]
14  LOP_SETTABLE      A,B,C   R[B][R[C]] = R[A]
15  LOP_GETTABLEKS    A,B AUX R[A] = R[B][K[AUX]]  (AUX: constant string; with IC slot)
16  LOP_SETTABLEKS    A,B AUX R[B][K[AUX]] = R[A]
17  LOP_GETTABLEN     A,B,C   R[A] = R[B][C+1]  (C: 0-based u8 → index 1..256)
18  LOP_SETTABLEN     A,B,C   R[B][C+1] = R[A]
19  LOP_NEWCLOSURE    A,D     R[A] = new Closure(proto[D]); followed by CAPTURE* per upvalue
20  LOP_NAMECALL      A,B AUX R[A+1]=R[B]; R[A]=R[B][K[AUX]]; followed by CALL R[A]
21  LOP_CALL          A,B,C   call R[A] with args R[A+1..A+B-1]; results to R[A..A+C-2]
                               B=0: vararg args; C=0: vararg results
22  LOP_RETURN        A,B     return R[A..A+B-2]; B=0: vararg return
23  LOP_JUMP          D       pc += D
24  LOP_JUMPBACK      D       pc += D  (same as JUMP, hints backwards branch for profiling)
25  LOP_JUMPIF        A,D     if R[A]: pc += D
26  LOP_JUMPIFNOT     A,D     if not R[A]: pc += D
27  LOP_JUMPIFEQ      A,D AUX if R[A] == R[AUX_A]: pc += D
28  LOP_JUMPIFLE      A,D AUX if R[A] <= R[AUX_A]: pc += D
29  LOP_JUMPIFLT      A,D AUX if R[A] < R[AUX_A]: pc += D
30  LOP_JUMPIFNOTEQ   A,D AUX if R[A] ~= R[AUX_A]: pc += D
31  LOP_JUMPIFNOTLE   A,D AUX if not (R[A] <= R[AUX_A]): pc += D
32  LOP_JUMPIFNOTLT   A,D AUX if not (R[A] < R[AUX_A]): pc += D
33  LOP_ADD           A,B,C   R[A] = R[B] + R[C]
34  LOP_SUB           A,B,C   R[A] = R[B] - R[C]
35  LOP_MUL           A,B,C   R[A] = R[B] * R[C]
36  LOP_DIV           A,B,C   R[A] = R[B] / R[C]
37  LOP_MOD           A,B,C   R[A] = R[B] % R[C]
38  LOP_POW           A,B,C   R[A] = R[B] ^ R[C]
39  LOP_ADDK          A,B,C   R[A] = R[B] + K[C]  (C: constant index 0..255)
40  LOP_SUBK          A,B,C   R[A] = R[B] - K[C]
41  LOP_MULK          A,B,C   R[A] = R[B] * K[C]
42  LOP_DIVK          A,B,C   R[A] = R[B] / K[C]
43  LOP_MODK          A,B,C   R[A] = R[B] % K[C]
44  LOP_POWK          A,B,C   R[A] = R[B] ^ K[C]
45  LOP_AND           A,B,C   R[A] = R[B] and R[C]  (short-circuit, returns operand)
46  LOP_OR            A,B,C   R[A] = R[B] or R[C]
47  LOP_ANDK          A,B,C   R[A] = R[B] and K[C]
48  LOP_ORK           A,B,C   R[A] = R[B] or K[C]
49  LOP_CONCAT        A,B,C   R[A] = concat(R[B]..R[C])  (range inclusive)
50  LOP_NOT           A,B     R[A] = not R[B]
51  LOP_MINUS         A,B     R[A] = -R[B]
52  LOP_LENGTH        A,B     R[A] = #R[B]
53  LOP_NEWTABLE      A,B AUX R[A] = {}  (B: hash capacity hint; AUX: array capacity)
54  LOP_DUPTABLE      A,D     R[A] = clone(K[D])  (K[D] is a TABLE constant template)
55  LOP_SETLIST       A,B AUX R[A][AUX..AUX+B-2] = R[A+1]..R[A+B-1]  (array init)
56  LOP_FORNPREP      A,D     prep numeric for: verify R[A..A+2] are numbers; jump D if empty
57  LOP_FORNLOOP      A,D     R[A] += R[A+2]; if not done: pc += D; R[A+3] = R[A]
58  LOP_FORGLOOP      A,D AUX call R[A](R[A+1],R[A+2]); store to R[A+3..]; loop if R[A+3]~=nil
                               AUX low 8: variable count; AUX high bit: ipairs-style hint
59  LOP_FORGPREP_INEXT A,D    prepare FORGLOOP for ipairs-style: check R[A] is builtin next
60  LOP_FASTCALL3     A,B,C AUX  fast call with 3 args: R[B], R[B+1], R[AUX_A]; v6+
61  LOP_FORGPREP_NEXT A,D    prepare FORGLOOP for next-style iteration
62  LOP_NATIVECALL    –       hint for native code entry (JIT trampoline)
63  LOP_GETVARARGS    A,B     R[A..A+B-2] = varargs; B=0: all varargs
64  LOP_DUPCLOSURE    A,D     R[A] = shared Closure(K[D])  (K[D] is CLOSURE constant)
65  LOP_PREPVARARGS   A       set up vararg frame for numparams = A
66  LOP_LOADKX        A, AUX  R[A] = K[AUX]  (AUX: large constant index)
67  LOP_JUMPX         E       pc += E  (24-bit jump for long distances)
68  LOP_FASTCALL      A,C     fast call builtin ID=A with jump C; followed by GETIMPORT/MOVE/GETUPVAL + CALL
69  LOP_COVERAGE      E       coverage counter increment; E encodes hit index
70  LOP_CAPTURE       A,B     capture upvalue into last NEWCLOSURE: type=A, source=B
71  LOP_SUBRK         A,B,C   R[A] = K[B] - R[C]  (constant on left; v5+)
72  LOP_DIVRK         A,B,C   R[A] = K[B] / R[C]  (constant on left; v5+)
73  LOP_FASTCALL1     A,B,C   fast call builtin ID=A, arg=R[B], jump=C
74  LOP_FASTCALL2     A,B,C AUX fast call builtin ID=A, args=R[B],R[AUX_A], jump=C
75  LOP_FASTCALL2K    A,B,C AUX fast call builtin ID=A, args=R[B],K[AUX], jump=C
76  LOP_FORGPREP      A,D     general FORGPREP: evaluate __iter if present, setup R[A..A+2]
77  LOP_JUMPXEQKNIL   A,D AUX if (R[A]==nil) xor AUX_NOT: pc += D
78  LOP_JUMPXEQKB     A,D AUX if (R[A]==(bool)AUX_KB) xor AUX_NOT: pc += D
79  LOP_JUMPXEQKN     A,D AUX if (R[A]==K[AUX_KV]) xor AUX_NOT: pc += D  (K is number)
80  LOP_JUMPXEQKS     A,D AUX if (R[A]==K[AUX_KV]) xor AUX_NOT: pc += D  (K is string)
81  LOP_IDIV          A,B,C   R[A] = floor(R[B] / R[C])  v4+
82  LOP_IDIVK         A,B,C   R[A] = floor(R[B] / K[C])  v4+
83  LOP_GETUDATAKS    A,B AUX R[A] = userdata R[B] . K[AUX] via atom acceleration  v9+
84  LOP_SETUDATAKS    A,B AUX userdata R[B] . K[AUX] = R[A]  v9+
85  LOP_NAMECALLUDATA A,B AUX like NAMECALL but on userdata; R[A+1]=R[B]; R[A]=udmethod  v9+
86  LOP_NEWCLASSMEMBER A,B   allocate class member slot (object model support)
87  LOP_CALLFB        A       call fallback (used for CMPPROTO support)
88  LOP_CMPPROTO      A,B,C   compare proto type for class dispatch
```

`LOP__COUNT = 89` (or higher depending on branch). Opcodes are densely packed starting at 0; no gaps permitted (the dispatch array is indexed by opcode byte directly).

---

## 5. Key Fast-Path Opcodes: Deep Semantics

### 5.1 FORNPREP / FORNLOOP

Numeric `for i = start, limit, step do` compiles to four consecutive registers:

```
R[A]   = index variable (internal, modified by FORNLOOP)
R[A+1] = limit
R[A+2] = step
R[A+3] = user-visible loop variable (copy of R[A] before body executes)
```

`FORNPREP A,D`: validate R[A..A+2] are numbers; if step==0: error; if loop won't execute: pc += D (skip body). Also applies initial bound check.

`FORNLOOP A,D`: R[A] += R[A+2]; if (step > 0 && R[A] <= R[A+1]) || (step < 0 && R[A] >= R[A+1]): R[A+3] = R[A]; pc += D. Otherwise falls through (exit loop).

This is semantically identical to Lua 5.1 FORPREP+FORLOOP but renamed and with slightly different pre-check semantics.

### 5.2 FORGPREP / FORGLOOP / Specialized Variants

Generic `for k, v in expr do` compiles to:

```
R[A]   = iterator function (f)
R[A+1] = state (s)
R[A+2] = control variable (var)
R[A+3..A+2+n] = result registers (user visible)
```

`FORGPREP A,D`: evaluates the iterable. If it has `__iter` metamethod, calls it; result replaces R[A..A+2]. If it's a plain table with no `__iter`, sets R[A]=next, R[A+1]=table, R[A+2]=nil. Jumps to FORGLOOP (D offset).

`FORGLOOP A,D AUX`: calls R[A](R[A+1], R[A+2]); stores results to R[A+3..]. AUX low 8 bits = variable count. If R[A+3] != nil: R[A+2] = R[A+3]; pc += D. The high bit of AUX is a hint that the iterator is `ipairs`-style for fast-path dispatch.

`FORGPREP_INEXT A,D`: specialized when compiler proves iterator is built-in `ipairs`. Sets up without going through `__iter` check; FORGLOOP uses ipairs fast path.

`FORGPREP_NEXT A,D`: specialized when compiler proves iterator is built-in `pairs`/`next`. Same optimization path for hash traversal.

### 5.3 GETIMPORT

```
GETIMPORT A,D  [AUX]
```

Used for top-level global chain resolution: `math.floor`, `string.format`, `table.insert`. The D field is a constant table index (into K[]) that holds a placeholder; the AUX word encodes the import path:

```
AUX bits 31–30: path length (1, 2, or 3)
AUX bits 29–20: constant string index for component 0 (10 bits)
AUX bits 19–10: constant string index for component 1 (10 bits, 0 if unused)
AUX bits 9–0:   constant string index for component 2 (10 bits, 0 if unused)
```

`BytecodeBuilder::getImportId(id0)` packs `(1 << 30) | (id0 << 20)`.
`getImportId(id0, id1)` packs `(2 << 30) | (id0 << 20) | (id1 << 10)`.
`getImportId(id0, id1, id2)` packs `(3 << 30) | (id0 << 20) | (id1 << 10) | id2`.

At load time, `resolveImportSafe` walks the global table following the chain; result is cached in K[D] so repeated GETIMPORT hits the constant slot directly without re-walking globals.

### 5.4 NAMECALL

```
NAMECALL A,B  [AUX: constant string index K[AUX]]
CALL     A,nargs+1,nresults
```

Semantics: R[A+1] = R[B] (the object, pushed as `self`); R[A] = R[B][K[AUX]] (method lookup). AUX also carries an inline cache slot index in the high 16 bits (`LUAU_INSN_AUX_SLOT`) for accelerated hash lookup.

The `__namecall` metamethod is an embedding-level hook (not available in Lua source) that lets userdata types bypass the two-step fetch+call pattern; the VM calls `__namecall(self, methodName)` directly, and the C binding resolves the method by name using interned string atoms, avoiding any table walk.

For plain Lua tables, NAMECALL falls back to `__index` chain with the inline cache from the AUX slot.

### 5.5 FASTCALL Family

```
FASTCALL   A,C        // A=builtin ID, C=jump offset past the CALL
<one of: GETIMPORT / MOVE / GETUPVAL>   // backup load
CALL       ...        // fallback if fast path failed
```

When the VM hits FASTCALL, it looks up the fast implementation via `luauF_dispatch[A]`. This is a table of C function pointers (`luauF_*` from `lbuiltins.cpp`). Each function:
- Returns number of results on success
- Returns -1 if argument types aren't right for the fast path

On success: pc skips over the CALL (by C offset). On failure: execution continues into GETIMPORT/MOVE/GETUPVAL + CALL.

`FASTCALL1 A,B,C`: one arg in R[B].
`FASTCALL2 A,B,C AUX`: two args in R[B] and R[AUX_A].
`FASTCALL2K A,B,C AUX`: args R[B] and K[AUX].
`FASTCALL3 A,B,C AUX`: three args in R[B], R[B+1], R[AUX_A]; v6+.

Fast-path builtins (selected from `LuauBuiltinFunction`):

```
LBF_ASSERT, LBF_TYPE, LBF_TYPEOF, LBF_RAWGET, LBF_RAWSET, LBF_RAWEQUAL
LBF_RAWLEN, LBF_UNPACK, LBF_SELECT_LEN, LBF_SELECT_FWD
LBF_TOSTRING, LBF_TONUMBER
LBF_MATH_ABS, LBF_MATH_CEIL, LBF_MATH_FLOOR, LBF_MATH_SQRT
LBF_MATH_SIN, LBF_MATH_COS, LBF_MATH_TAN, ...all math.*...
LBF_BIT32_ARSHIFT ... LBF_BIT32_BYTESWAP
LBF_STRING_BYTE, LBF_STRING_CHAR, LBF_STRING_LEN, LBF_STRING_SUB
LBF_TABLE_INSERT, LBF_TABLE_UNPACK, LBF_TABLE_MOVE
LBF_BUFFER_READU8 ... LBF_BUFFER_WRITEF64
LBF_VECTOR, LBF_VECTOR_MAGNITUDE, LBF_VECTOR_NORMALIZE, ...
```

Excluded: `math.random`, `math.randomseed`, `math.noise`.

### 5.6 JUMPXEQK* Variants

Introduced in bytecode v3, replacing the older `JUMPIFEQK`/`JUMPIFNOTEQK`:

```
JUMPXEQKNIL A,D AUX   // AUX_NOT flips condition
JUMPXEQKB   A,D AUX   // AUX_KB = bool value; AUX_NOT flips
JUMPXEQKN   A,D AUX   // AUX_KV = constant index (24-bit); K[AUX_KV] is number
JUMPXEQKS   A,D AUX   // AUX_KV = constant index (24-bit); K[AUX_KV] is string
```

These fold condition + constant into a single instruction+AUX instead of requiring a separate LOADK before JUMPIFEQ. The `JUMPXEQKS` uses pointer equality on interned strings (fast path) before falling back to string comparison.

### 5.7 CAPTURE Instruction

Follows `NEWCLOSURE` immediately, one per upvalue:

```
CAPTURE  A,B
// A = LuauCaptureType: LCT_VAL=0, LCT_REF=1, LCT_UPVAL=2
// B = register (for LCT_VAL/REF) or upvalue index (for LCT_UPVAL)
```

- `LCT_VAL`: captures register B as a closed-over value (immutable copy if never written)
- `LCT_REF`: captures register B as a mutable reference (open upvalue, closes on CLOSEUPVALS)
- `LCT_UPVAL`: re-captures upvalue B from enclosing function

`DUPCLOSURE` is used instead of `NEWCLOSURE` when the proto has no upvalues or only top-level immutable upvalues — it shares a single Closure object across all call sites, eliminating allocation per call.

### 5.8 COVERAGE

```
COVERAGE  E
```

E encodes the hit counter index. The VM increments a per-proto coverage bitmap and calls a registered callback `(proto_debugname, proto_linedefined, proto_depth, hit_buffer, max_line)`. Used for code coverage instrumentation.

### 5.9 BREAK

Opcode 1. The debugger replaces an instruction's opcode byte with `LOP_BREAK` (0x01) in-place. The VM sees BREAK, suspends, calls `breakHook`. On resume, the original instruction is restored and re-executed. This is the same patch-and-restore model as CPython and Lua 5.1.

---

## 6. Value Representation (TValue)

Luau uses a 16-byte tagged union rather than NaN boxing. The rationale: NaN boxing requires all pointer values to fit in 48 bits; Luau wants to store a native `float3` vector (3 × f32 = 12 bytes) plus a type tag, which doesn't fit NaN-boxed. The 16-byte layout fits all cases:

```c
// Conceptual layout (actual lobject.h internal struct)
struct TValue {
    union {
        double   n;         // LUA_TNUMBER
        int      b;         // LUA_TBOOLEAN
        GCObject* gc;       // any GC type (string, table, function, userdata, thread)
        void*    p;         // light userdata
        struct { float x, y, z; };  // LUA_TVECTOR (3 floats)
    } value;
    int tt;                  // type tag (lua_Type enum)
    // 4 bytes padding on 64-bit systems to align gc pointers
};
// Total: 8 bytes value + 4 bytes tag + 4 bytes padding = 16 bytes
```

Type tags:

```
LUA_TNIL          = 0
LUA_TBOOLEAN      = 1
LUA_TLIGHTUSERDATA = 2
LUA_TNUMBER       = 3
LUA_TVECTOR       = 4   (Luau-specific; not in Lua 5.1)
LUA_TSTRING       = 5
LUA_TTABLE        = 6
LUA_TFUNCTION     = 7
LUA_TUSERDATA     = 8
LUA_TTHREAD       = 9
LUA_TBUFFER       = 10  (Luau-specific buffer type)
// internal GC types
LUA_TPROTO        = 11
LUA_TUPVAL        = 12
LUA_TDEADKEY      = 13
```

All types `>= LUA_TSTRING` are GC-managed. The `tt` field is checked for GC write barriers.

---

## 7. Proto Structure (Runtime)

The runtime `Proto` (in `lobject.h`) mirrors the serialized form but with pointers:

```c
struct Proto {
    CommonHeader;           // GC header
    TValue*   k;            // constant array
    Instruction* code;      // instruction array (uint32_t[])
    Proto**   p;            // child proto array
    uint8_t*  lineinfo;     // delta-encoded line offsets (may be NULL)
    int*      abslineinfo;  // absolute line anchors every 2^linegaplog2 instructions
    LocVar*   locvars;      // local variable debug descriptors
    TString** upvalues;     // upvalue names (debug)
    TString*  source;       // source name
    TString*  debugname;    // function name for stack traces
    int       linedefined;  // first line in source
    int       sizecode;
    int       sizek;
    int       sizep;
    int       sizelineinfo;
    int       sizelocvars;
    int       sizeupvalues;
    uint8_t   maxstacksize;
    uint8_t   numparams;
    uint8_t   nups;
    uint8_t   is_vararg;
    uint8_t   flags;        // LuauProtoFlag bitmask (v4+)
    uint8_t   linegaplog2;
    uint8_t   memcat;
    // optional: typeinfo pointer for type-annotated bytecode
};
```

`LuauProtoFlag` bitmask:

```c
LPF_NATIVE_MODULE   = 1 << 0  // module has --!native comment
LPF_NATIVE_COLD     = 1 << 1  // compiler marked unprofitable for native compilation
LPF_NATIVE_FUNCTION = 1 << 2  // one or more @native-attributed functions
LPF_INLINABLE       = 1 << 3  // eligible for inlining by native compiler
```

---

## 8. VM Execution Model

### 8.1 lua_State and Coroutines

Each coroutine is a `lua_State`. The VM maintains a call stack as `CallInfo` frames on the `lua_State` stack. The `lua_State` has fields:

```c
StkId    top;        // first free slot
StkId    base;       // base of current frame (R[0])
StkId    stack;      // bottom of stack allocation
StkId    stack_last; // end of stack allocation
CallInfo* ci;        // current call frame
CallInfo* base_ci;   // bottom call frame
int       stacksize;
int       status;    // lua_Status enum
```

Each `CallInfo`:

```c
StkId    base;      // R[0] of this frame
StkId    top;       // top of this frame (used for stack overflow detection)
const Instruction* savedpc;  // saved program counter
int      nresults;  // expected result count (-1 = MULTRET)
```

The main execute function is `luaV_execute(lua_State* L)`. It reads `ci->savedpc` on entry and drives the dispatch loop.

### 8.2 C VM Dispatch Mechanism

Luau uses computed-goto dispatch (`__label__` arrays) when compiled with GCC/Clang via `LUAU_VM_USE_COMPUTED_GOTO`. The dispatch table is a `void*[256]` array:

```c
static const void* dispatch_table[256] = {
    &&op_NOP, &&op_BREAK, &&op_LOADNIL, ... // labels per opcode
};
#define DISPATCH() goto *dispatch_table[LUAU_INSN_OP(*pc)]
#define VM_NEXT() { pc++; DISPATCH(); }
```

Each opcode handler ends with `VM_NEXT()` or a direct `goto` to a specific opcode label for fused operations (e.g., FORNLOOP can jump directly to `op_FORNLOOP` to avoid one cycle through the fetch-decode).

Without GCC computed-goto (MSVC, non-GNU C++): falls back to a `switch(op)` inside a `while(1)` loop. The compiler generates a `tableswitch` equivalent on most architectures.

The interpreter loop compiles to approximately 16 KB on x86-64, which fits in the L1 instruction cache.

---

## 9. Scala Data Model for Instructions

### 9.1 Core Instruction ADT

A sealed hierarchy mapping directly to Luau's encoding formats:

```scala
/** Raw 32-bit instruction word as decoded from bytecode */
opaque type RawInsn = Int

object RawInsn:
  def apply(w: Int): RawInsn = w
  extension (r: RawInsn)
    def op: Int     = r & 0xff
    def fieldA: Int = (r >>> 8) & 0xff
    def fieldB: Int = (r >>> 16) & 0xff
    def fieldC: Int = (r >>> 24) & 0xff
    def fieldD: Int = r >> 16                // arithmetic shift, signed
    def fieldE: Int = r >> 8                 // arithmetic shift, 24-bit signed

/** Auxiliary word following certain instructions */
opaque type AuxWord = Int
object AuxWord:
  def apply(w: Int): AuxWord = w
  extension (a: AuxWord)
    def auxA: Int     = a & 0xff
    def auxB: Int     = (a >>> 8) & 0xff
    def auxKV: Int    = a & 0xffffff         // 24-bit unsigned constant index
    def auxKB: Int    = a & 0x1              // boolean bit
    def notFlag: Int  = (a >>> 31) & 0x1    // invert comparison
    def slot: Int     = (a >>> 16) & 0xffff  // IC slot

sealed trait Insn
object Insn:
  // Load/Move
  case class LoadNil(dst: Reg)                               extends Insn
  case class LoadB(dst: Reg, value: Boolean, skip: Boolean)  extends Insn
  case class LoadN(dst: Reg, n: Short)                       extends Insn  // D field = signed 16-bit
  case class LoadK(dst: Reg, k: ConstIdx)                    extends Insn  // D <= 32767
  case class LoadKX(dst: Reg, k: LargeConstIdx)              extends Insn  // AUX = 24-bit idx
  case class Move(dst: Reg, src: Reg)                        extends Insn

  // Global/Upvalue
  case class GetGlobal(dst: Reg, key: ConstIdx)              extends Insn  // key from AUX
  case class SetGlobal(src: Reg, key: ConstIdx)              extends Insn
  case class GetUpval(dst: Reg, upv: UpvalIdx)               extends Insn
  case class SetUpval(src: Reg, upv: UpvalIdx)               extends Insn
  case class CloseUpvals(fromReg: Reg)                       extends Insn

  // Import
  case class GetImport(dst: Reg, kSlot: ConstIdx, importPath: ImportPath) extends Insn
  // importPath decoded from AUX: 1/2/3 component string indices

  // Table
  case class GetTable(dst: Reg, obj: Reg, key: Reg)          extends Insn
  case class SetTable(src: Reg, obj: Reg, key: Reg)          extends Insn
  case class GetTableKS(dst: Reg, obj: Reg, keyK: ConstIdx, icSlot: Int) extends Insn
  case class SetTableKS(src: Reg, obj: Reg, keyK: ConstIdx, icSlot: Int) extends Insn
  case class GetTableN(dst: Reg, obj: Reg, idx: Int)         extends Insn  // idx 1..256
  case class SetTableN(src: Reg, obj: Reg, idx: Int)         extends Insn

  // Arithmetic (register variants)
  case class Add(dst: Reg, a: Reg, b: Reg)    extends Insn
  case class Sub(dst: Reg, a: Reg, b: Reg)    extends Insn
  case class Mul(dst: Reg, a: Reg, b: Reg)    extends Insn
  case class Div(dst: Reg, a: Reg, b: Reg)    extends Insn
  case class Mod(dst: Reg, a: Reg, b: Reg)    extends Insn
  case class Pow(dst: Reg, a: Reg, b: Reg)    extends Insn
  case class IDiv(dst: Reg, a: Reg, b: Reg)   extends Insn
  case class Neg(dst: Reg, src: Reg)          extends Insn

  // Arithmetic (constant variants)
  case class AddK(dst: Reg, a: Reg, k: ConstIdx)   extends Insn
  case class SubK(dst: Reg, a: Reg, k: ConstIdx)   extends Insn
  case class MulK(dst: Reg, a: Reg, k: ConstIdx)   extends Insn
  case class DivK(dst: Reg, a: Reg, k: ConstIdx)   extends Insn
  case class ModK(dst: Reg, a: Reg, k: ConstIdx)   extends Insn
  case class PowK(dst: Reg, a: Reg, k: ConstIdx)   extends Insn
  case class IDivK(dst: Reg, a: Reg, k: ConstIdx)  extends Insn
  case class SubRK(dst: Reg, k: ConstIdx, b: Reg)  extends Insn  // constant on left
  case class DivRK(dst: Reg, k: ConstIdx, b: Reg)  extends Insn

  // Logic
  case class And(dst: Reg, a: Reg, b: Reg)         extends Insn
  case class Or(dst: Reg, a: Reg, b: Reg)          extends Insn
  case class AndK(dst: Reg, a: Reg, k: ConstIdx)   extends Insn
  case class OrK(dst: Reg, a: Reg, k: ConstIdx)    extends Insn
  case class Not(dst: Reg, src: Reg)               extends Insn
  case class Len(dst: Reg, src: Reg)               extends Insn
  case class Concat(dst: Reg, from: Reg, to: Reg)  extends Insn

  // Control flow
  case class Jump(offset: Int)                     extends Insn  // D field
  case class JumpBack(offset: Int)                 extends Insn
  case class JumpX(offset: Int)                    extends Insn  // E field (24-bit)
  case class JumpIf(cond: Reg, offset: Int)        extends Insn
  case class JumpIfNot(cond: Reg, offset: Int)     extends Insn
  case class JumpIfEq(a: Reg, b: Reg, offset: Int)    extends Insn
  case class JumpIfNotEq(a: Reg, b: Reg, offset: Int) extends Insn
  case class JumpIfLe(a: Reg, b: Reg, offset: Int)    extends Insn
  case class JumpIfNotLe(a: Reg, b: Reg, offset: Int) extends Insn
  case class JumpIfLt(a: Reg, b: Reg, offset: Int)    extends Insn
  case class JumpIfNotLt(a: Reg, b: Reg, offset: Int) extends Insn
  // Constant comparison jumps
  case class JumpXEqKNil(reg: Reg, offset: Int, negate: Boolean)                  extends Insn
  case class JumpXEqKB(reg: Reg, offset: Int, kBool: Boolean, negate: Boolean)    extends Insn
  case class JumpXEqKN(reg: Reg, offset: Int, k: ConstIdx, negate: Boolean)       extends Insn
  case class JumpXEqKS(reg: Reg, offset: Int, k: ConstIdx, negate: Boolean)       extends Insn

  // Calls
  case class Call(func: Reg, nArgs: Int, nResults: Int)   extends Insn
  case class Return(base: Reg, nResults: Int)             extends Insn
  case class NameCall(dst: Reg, obj: Reg, method: ConstIdx, icSlot: Int) extends Insn
  case class NewClosure(dst: Reg, protoIdx: Int)          extends Insn
  case class DupClosure(dst: Reg, kIdx: ConstIdx)         extends Insn
  case class Capture(captureType: CaptureType, source: Int) extends Insn
  case class PrepVarargs(numParams: Int)                  extends Insn
  case class GetVarargs(dst: Reg, count: Int)             extends Insn

  // Fast calls
  case class FastCall(builtinId: Int, jumpOffset: Int)                   extends Insn
  case class FastCall1(builtinId: Int, arg: Reg, jumpOffset: Int)        extends Insn
  case class FastCall2(builtinId: Int, arg1: Reg, arg2: Reg, jumpOffset: Int) extends Insn
  case class FastCall2K(builtinId: Int, arg: Reg, k: ConstIdx, jumpOffset: Int) extends Insn
  case class FastCall3(builtinId: Int, arg1: Reg, arg2: Reg, arg3: Reg, jumpOffset: Int) extends Insn

  // Loops
  case class FornPrep(base: Reg, skipOffset: Int)          extends Insn
  case class FornLoop(base: Reg, backOffset: Int)          extends Insn
  case class ForgPrep(base: Reg, jumpOffset: Int)          extends Insn
  case class ForgLoop(base: Reg, jumpOffset: Int, varCount: Int, ipairsHint: Boolean) extends Insn
  case class ForgPrepInext(base: Reg, jumpOffset: Int)     extends Insn
  case class ForgPrepNext(base: Reg, jumpOffset: Int)      extends Insn

  // Table construction
  case class NewTable(dst: Reg, hashSize: Int, arraySize: Int) extends Insn
  case class DupTable(dst: Reg, k: ConstIdx)               extends Insn
  case class SetList(table: Reg, count: Int, startIdx: Int) extends Insn

  // Debug / misc
  case object Nop                                          extends Insn
  case object Break                                        extends Insn
  case object NativeCall                                   extends Insn
  case class Coverage(hitIndex: Int)                       extends Insn

  // Userdata (v9+)
  case class GetUdataKS(dst: Reg, obj: Reg, key: ConstIdx) extends Insn
  case class SetUdataKS(src: Reg, obj: Reg, key: ConstIdx) extends Insn
  case class NameCallUdata(dst: Reg, obj: Reg, method: ConstIdx) extends Insn

// Opaque index types for safety
opaque type Reg       <: Int = Int
opaque type ConstIdx  <: Int = Int
opaque type LargeConstIdx <: Int = Int
opaque type UpvalIdx  <: Int = Int

enum CaptureType:
  case Val, Ref, Upval
```

### 9.2 Constant ADT

```scala
enum Constant:
  case KNil
  case KBoolean(v: Boolean)
  case KNumber(v: Double)
  case KString(s: String)
  case KImport(id: Int)                        // raw 32-bit AUX import ID
  case KTable(keys: Vector[Int])               // string constant indices for shape
  case KTableWithConstants(keys: Vector[(Int, Constant)])  // v7+
  case KClosure(protoIdx: Int)
  case KVector(x: Float, y: Float, z: Float, w: Float)   // v5+
  case KInteger(v: Long)                       // v8+
  case KClassShape(shapeId: Int)               // v9+
```

### 9.3 Proto Data Model

```scala
case class Proto(
  maxStackSize:  Int,
  numParams:     Int,
  numUpvalues:   Int,
  isVararg:      Boolean,
  flags:         Int,                     // LuauProtoFlag bitmask
  typeinfo:      Array[Byte],             // LuauBytecodeType stream
  instructions:  IArray[RawInsn],         // raw 32-bit words
  decoded:       IArray[InsnWithAux],     // decoded (Insn, Option[AuxWord])
  constants:     IArray[Constant],
  children:      IArray[Int],             // proto indices
  lineinfo:      Option[LineInfo],
  locvars:       IArray[LocVar],
  upvalueNames:  IArray[String],
  debugName:     String,
  sourceName:    String,
  lineDefined:   Int,
)

case class LineInfo(
  linegaplog2: Int,
  deltas:      IArray[Byte],   // one per instruction
  abslines:    IArray[Int],    // one per interval
):
  def lineAt(pc: Int): Int =
    val interval = pc >> linegaplog2
    val base     = abslines(interval)
    // sum deltas from interval start to pc
    val intervalStart = interval << linegaplog2
    var offset: Int = 0
    var i = intervalStart
    while i <= pc do { offset = (offset + (deltas(i) & 0xff)) & 0xff; i += 1 }
    base + offset  // NOTE: offset is relative, adding to base gives absolute line

case class LocVar(name: String, startPc: Int, endPc: Int, reg: Int)
case class InsnWithAux(insn: Insn, aux: Option[AuxWord])
```

### 9.4 Bytecode Parser (high-level)

```scala
class LuauBytecodeParser(buf: Array[Byte]):
  private var pos = 0

  def parse(): (IArray[Proto], Int) =      // protos + main proto index
    val version = readU8()
    require(version >= LBC_BYTECODE_MIN && version <= LBC_BYTECODE_MAX)
    if version >= 4 then
      val typesVersion = readU8()
      require(typesVersion >= LBC_TYPE_VERSION_MIN && typesVersion <= LBC_TYPE_VERSION_MAX)
    val strings   = readStringTable()
    val protos    = IArray.fill(readVarInt())(readProto(strings, version))
    val mainIndex = readVarInt()
    (protos, mainIndex)

  private def readVarInt(): Int =
    var result = 0; var shift = 0; var b = 0
    do { b = readU8(); result |= (b & 0x7f) << shift; shift += 7 } while (b & 0x80) != 0
    result
  // ...etc
```

---

## 10. JVM Dispatch Loop Strategy

### 10.1 The Problem: tableswitch vs Virtual Dispatch

The JVM has two bytecode instructions for switch-like dispatch:

- **`tableswitch`**: O(1), requires contiguous integer keys. Generated when case values form a dense range. Jump table indexed directly.
- **`lookupswitch`**: O(log n), binary search over sorted case pairs. Generated when cases are sparse.

A Scala/Java `match` on an `Int` opcode (0–88, contiguous) compiles to `tableswitch`. This is the best possible dispatch for a dense opcode space.

A `match` on sealed trait subtypes compiles to `instanceof` chains — much worse.

### 10.2 Option A: Int Switch (Recommended)

```scala
def execute(state: LuauState): Unit =
  while true do
    val insn = state.fetchInsn()
    val op   = insn & 0xff
    (op: @switch) match   // @switch annotation forces tableswitch, not lookupswitch
      case OP_LOADNIL  => execLoadNil(state, insn)
      case OP_LOADB    => execLoadB(state, insn)
      case OP_MOVE     => execMove(state, insn)
      // ... all 89 cases
      case OP_FORNPREP => execFornPrep(state, insn)
      case OP_FORNLOOP => execFornLoop(state, insn)
      case _ => throw InvalidOpcodeException(op)
```

Scala's `@switch` annotation causes a compile error if the match cannot be compiled to `tableswitch`. With opcodes 0–88 (dense), it will. The JIT sees a single static dispatch, can inline individual case branches after profiling.

**Pro**: single hot `tableswitch` instruction in JVM bytecode, JIT can inline each arm.
**Con**: monolithic method risks hitting JVM 64KB method bytecode size limit. Split into helper methods called from each case.

### 10.3 Option B: Array of Handler Functions (Subroutine Threading)

```scala
type Handler = (LuauState, Int) => Unit

val handlers: Array[Handler] = new Array(256)
handlers(OP_LOADNIL)  = (st, insn) => st.regs(insn.fieldA) = LuauNil
handlers(OP_MOVE)     = (st, insn) => st.regs(insn.fieldA) = st.regs(insn.fieldB)
// fill all 89 slots

def execute(state: LuauState): Unit =
  while true do
    val insn = state.fetchInsn()
    handlers(insn & 0xff)(state, insn)
```

This is subroutine threading. Each dispatch is an array index + virtual call on `Handler`. The virtual call is a `invokedynamic` or `invokevirtual` on a SAM type.

**The megamorphic problem**: if the JVM sees many different concrete `Handler` implementations at a single call site (`handlers(op)(state, insn)`), the call site goes megamorphic (more than ~2 receiver types in HotSpot, or threshold ~8 for polymorphic inline caches). Megamorphic = no inlining, VTable dispatch only. For 89 opcodes all hitting the same call site, this will definitely go megamorphic.

**Measured cost** (from [shipilev.net anatomy-quarks #16](https://shipilev.net/jvm/anatomy-quarks/16-megamorphic-virtual-calls/)): megamorphic overhead is approximately 3x slower than monomorphic — ~1ns vs ~0.3ns per call in optimized JIT code. Not catastrophic but significant in a tight interpreter loop.

**Mitigation**: use `@FunctionalInterface` with a concrete abstract class extended by 89 static inner classes, and profile-guide the JIT by ordering the most common opcodes first in the array. Or restructure as a sealed abstract class with exactly two levels of hierarchy (no deeper polymorphism).

### 10.4 Option C: Trampoline / Continuation Style

Inspired by Deegen and direct-threading semantics. Each opcode handler is a separate JVM method that returns the next opcode handler (or invokes it via tail recursion). Since the JVM does not optimize tail calls, this requires trampolining via a loop:

```scala
abstract class OpcodeHandler:
  def execute(state: LuauState, insn: Int): OpcodeHandler

class DispatchLoop:
  def run(state: LuauState): Unit =
    var handler: OpcodeHandler = handlers(state.peekOp())
    while handler ne null do
      val insn = state.fetchInsn()
      handler = handler.execute(state, insn)
      handler = handlers(state.peekOp())  // next fetch
```

This still has the megamorphic problem on `handler.execute(...)` with 89 receiver types.

### 10.5 Option D: Ahead-of-Time Specialization via Scala Inline/Macros

Use Scala 3 inline + transparent inline to specialize dispatch at compile time for the hot path. Not practical for a dynamic dispatch loop but useful for frequently-fused instruction pairs (FASTCALL followed by CALL).

### 10.6 Recommended JVM Strategy: Int Switch + Method Splitting

```scala
// Break execute loop into groups to stay under 64KB JVM method limit
@inline private def dispatch(state: LuauState, op: Int, insn: Int): Unit =
  (op: @switch) match
    case 0  => execNop(state, insn)
    case 2  => execLoadNil(state, insn)
    case 3  => execLoadB(state, insn)
    case 4  => execLoadN(state, insn)
    case 5  => execLoadK(state, insn)
    case 6  => execMove(state, insn)
    case 12 => execGetImport(state, insn)
    case 20 => execNameCall(state, insn)
    case 21 => execCall(state, insn)
    case 22 => execReturn(state, insn)
    case 56 => execFornPrep(state, insn)
    case 57 => execFornLoop(state, insn)
    case 58 => execForgLoop(state, insn)
    case 68 => execFastCall(state, insn)
    // ... all 89 opcodes
    case _  => throw InvalidOpcodeException(op)
```

The `@switch` annotation on a contiguous-enough range forces `tableswitch`. The JVM `tableswitch` for 89 cases spans indices 0–88 with ~89 entries + bounds check — perfectly fits in L1 cache, O(1) dispatch.

**Each case method stays small** (ideally < 35 bytecodes) to remain inlineable by the JIT. HotSpot's default inline threshold is 35 bytecodes for hot methods. Keep each `execXxx` under that budget or use `@ForceInline` (Graal) / rely on profile-guided inlining.

### 10.7 FASTCALL Implementation on JVM

Map `LuauBuiltinFunction` IDs to a `Array[BuiltinFn]`:

```scala
type BuiltinFn = (LuauState, Reg, Int) => Int  // returns nresults or -1

val builtinDispatch: Array[BuiltinFn] = new Array(256)
builtinDispatch(LBF_MATH_ABS)   = mathAbsFn
builtinDispatch(LBF_MATH_FLOOR) = mathFloorFn
// ...

def execFastCall1(state: LuauState, insn: Int): Unit =
  val builtinId  = insn.fieldA
  val argReg     = insn.fieldB
  val jumpOffset = insn.fieldC
  val fn = builtinDispatch(builtinId)
  if fn != null then
    val nres = fn(state, argReg, 0)
    if nres >= 0 then
      state.pc += jumpOffset + 1  // skip over backup CALL
      return
  // fall through to GETIMPORT/MOVE + CALL
```

Same megamorphic issue: 160+ builtin IDs at one call site. If most code calls the same 2–3 builtins, the call site stays bimorphic and gets inlined. For general-purpose VMs expecting diverse builtins, accept the vtable dispatch cost.

### 10.8 Inline Cache for GETTABLEKS / NAMECALL

The AUX slot field (`LUAU_INSN_AUX_SLOT`) is designed for the C VM's inline cache. On JVM, implement a per-instruction cache in a separate `short[]` array parallel to the instruction stream:

```scala
class LuauFrame(val proto: Proto):
  val icSlots: Array[Int] = new Array(proto.instructions.length)  // inline cache: table shape ID

def execGetTableKS(state: LuauState, pc: Int, insn: Int, aux: Int): Unit =
  val dst    = insn.fieldA
  val obj    = insn.fieldB
  val keyIdx = aux & 0xffffff
  val slot   = aux >>> 16        // IC slot in the instruction AUX
  val table  = state.regs(obj).asTable
  val cached = state.frame.icSlots(pc)
  if table.shapeId == cached then
    // fast path: shape matches, use cached field offset
    state.regs(dst) = table.rawgetSlot(slot)
  else
    // slow path: re-lookup, update cache
    val v = table.rawget(state.proto.constants(keyIdx).asString)
    state.frame.icSlots(pc) = table.shapeId
    state.regs(dst) = v
```

This mirrors Luau's HREF (Hash REFerence) inline caching. The shape ID approach is analogous to V8's hidden classes or Lua's table shape hash.

---

## 11. Luau vs Lua 5.1 Dispatch Differences Summary

| Feature | Lua 5.1 | Luau |
|---|---|---|
| Instruction set size | ~38 opcodes | ~89 opcodes |
| Dispatch mechanism (C VM) | switch + DISPATCH macro | computed-goto with `&&label` dispatch table; fallback to switch |
| Inline caching | No | HREF prediction on GETTABLEKS/NAMECALL via AUX slot |
| Builtin fast path | No | FASTCALL family with LBF IDs |
| Import resolution caching | No | GETIMPORT caches in K[] slot at load time |
| Method call short-circuit | No | NAMECALL + `__namecall` metamethod |
| Generic for optimization | TFORLOOP | FORGPREP_NEXT / FORGPREP_INEXT when iterator statically known |
| Number loop | FORPREP/FORLOOP | FORNPREP/FORNLOOP (renamed + slightly different semantics) |
| Constant folding in bytecode | None | Deep constant folding in compiler; FASTCALL+fold for math.*/bit32.* |
| Long jumps | sBx field (18-bit) | JUMPX with E field (24-bit) for large functions |
| Constant comparisons | Via LOADK + JUMPIFEQ | JUMPXEQK* family (number, string, bool, nil specific) |

---

## 12. Bytecode Versioning Details

```
Version 3:  Added FORGPREP, JUMPXEQKNIL/B/N/S; removed FORGLOOP_NEXT/INEXT, JUMPIFEQK/JUMPIFNOTEQK
            Enhanced FORGLOOP AUX encoding
Version 4:  Added Proto::flags, typeinfo stream, IDIV/IDIVK
Version 5:  Added SUBRK/DIVRK (reversed arithmetic), LBC_CONSTANT_VECTOR
Version 6:  Added FASTCALL3 (3-argument fast call)
Version 7:  Added LBC_CONSTANT_TABLE_WITH_CONSTANTS for DUPTABLE with pre-filled constants
Version 8:  Added LBC_CONSTANT_INTEGER (64-bit integer literals)
Version 9:  Added GETUDATAKS/SETUDATAKS/NAMECALLUDATA + LBC_CONSTANT_CLASS_SHAPE;
            atom-based userdata field acceleration; userdata remapping table in bytecode header
Versions 10–11: (implementation-private; not yet documented publicly as of mid-2026)
```

---

## References

- [Common/include/Luau/Bytecode.h](https://github.com/luau-lang/luau/blob/master/Common/include/Luau/Bytecode.h) — authoritative opcode enum, encoding macros, version constants
- [VM/src/lvmload.cpp](https://github.com/luau-lang/luau/blob/master/VM/src/lvmload.cpp) — bytecode deserialization, readVarInt, line info delta encoding
- [Compiler/src/BytecodeBuilder.cpp](https://github.com/luau-lang/luau/blob/master/Compiler/src/BytecodeBuilder.cpp) — import ID encoding, constant table construction
- [DeepWiki: Luau Bytecode Format](https://deepwiki.com/luau-lang/luau/4.2-bytecode-format)
- [DeepWiki: Builtin Definitions and Constant Folding](https://deepwiki.com/luau-lang/luau/4.3-builtin-definitions-and-constant-folding)
- [Luau Performance Documentation](https://vegorov-rbx.github.io/luau/performance)
- [Luau Compatibility with Lua](https://luau.org/compatibility/)
- [Generalized Iteration RFC](https://rfcs.luau.org/generalized-iteration.html)
- [JVM Anatomy: Megamorphic Virtual Calls](https://shipilev.net/jvm/anatomy-quarks/16-megamorphic-virtual-calls/)
- [Efficient Hosted Interpreters on the JVM (TACO 2014)](https://www.unibw.de/ucsrl/pubs/taco14.pdf)
- [Understanding VM Dispatch through Duality](https://noelwelsh.com/posts/understanding-vm-dispatch/)
- [Building the Fastest Lua Interpreter (Deegen)](https://sillycross.github.io/2022/11/22/2022-11-22/)
- [uniquadev LuauVM bytecode reference](https://github.com/uniquadev/LuauVM/blob/master/VM/luau/bytecode.lua)
