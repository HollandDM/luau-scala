# Luau Official Runtime: Deep Technical Reference

**Source repo**: https://github.com/luau-lang/luau  
**Official site**: https://luau.org  
**Latest release at time of writing**: 0.724 (June 2026)  
**License**: MIT (dual-licensed with original Lua 5.x)

---

## 1. Repository Layout

```
luau-lang/luau/
├── Ast/          – Lexer, parser, AST node hierarchy
├── Compiler/     – AST-to-bytecode compiler
├── Common/       – Shared utilities + Bytecode.h (opcode definitions)
├── Analysis/     – Type checker, linter, constraint solver
├── VM/           – Interpreter, GC, C API (lua.h, lobject.h, lstate.h, lgc.cpp, lmem.cpp, lvmexecute.cpp)
├── CodeGen/      – JIT/native codegen, IR (IrData.h, IrLoweringX64.cpp, IrLoweringA64.cpp)
├── Bytecode/     – Bytecode serialization utilities
├── Config/       – .luaurc config handling
├── Require/      – Module loading system
├── CLI/          – `luau` REPL + `luau-analyze` CLI tools
├── bench/        – Performance benchmarks
├── fuzz/         – Fuzzing harness
├── tests/        – Unit + conformance test suites
└── tools/        – Utility scripts
```

**Language split**: C++ 85%, Lua/Luau 10%, Python 5%.  
**C++ standard**: C++11 for VM/runtime; C++17 for Compiler and Analysis.  
**Supported compilers**: VS 2017+, gcc-7+, clang-7+.

---

## 2. Ast Module: Lexer and Parser

### 2.1 Lexer (`Ast/include/Luau/Lexer.h`, `Ast/src/Lexer.cpp`)

The `Lexer` class does single-pass tokenization with one-token lookahead. It builds tokens into a `Lexeme` struct sized exactly 32 bytes (field ordering is deliberate to hit that target). A `AstNameTable` interns all identifier strings.

**Token categories** (`Lexeme::Type`):

| Category | Examples |
|---|---|
| Operators | `Equal` (==), `LessEqual`, `GreaterEqual`, `NotEqual`, `Dot2` (..), `Dot3` (...), `SkinnyArrow` (->), `DoubleColon` (::), `FloorDiv` (//) |
| Compound assign | `AddAssign` (+=), `SubAssign` (-=), `MulAssign`, `DivAssign`, `ModAssign`, `PowAssign`, `ConcatAssign` (..=), `FloorDivAssign` (//=) |
| String tokens | `RawString`, `QuotedString`, `InterpStringBegin`, `InterpStringMid`, `InterpStringEnd`, `InterpStringSimple`, `BrokenString`, `BrokenUnicode` |
| Other | `Number`, `Name`, `Comment`, `BlockComment`, `Attribute`, `AttributeOpen`, `Error`, `Eof` |
| Reserved keywords | `and`, `break`, `do`, `else`, `elseif`, `end`, `false`, `for`, `function`, `if`, `in`, `local`, `nil`, `not`, `or`, `repeat`, `return`, `then`, `true`, `until`, `while` |

Contextual keywords (`type`, `continue`, `const`) are parsed as `Name` tokens and disambiguated by the parser based on syntactic position — deliberate to preserve Lua 5.1 backward compatibility where those identifiers may be used as variable names.

Interpolated string lexing uses a **brace stack** inside the lexer: when entering `{` inside a backtick string, the lexer pushes state and emits `InterpStringBegin`; nested braces are tracked; closing `}` pops state and emits `InterpStringMid` or `InterpStringEnd`.

### 2.2 Parser (`Ast/src/Parser.cpp`)

Single-pass **recursive descent parser**, no backtracking. Design constraints (from the engineering blog post at https://medium.com/@andy.friesen/how-to-plan-a-luau-augmenting-luas-syntax-with-types-7751a790f0d8):

- Single token lookahead only
- No grammar ambiguity requiring rollback
- `type` keyword is context-sensitive: `type Foo = ...` at statement level → type alias; `type(x)` or `type = ...` → identifier

Produces an AST rooted at `AstStatBlock`.

### 2.3 AST Node Hierarchy (`Ast/include/Luau/Ast.h`)

**Base**: `AstNode` (all nodes).

**Expression nodes** (`AstExpr` subclasses):
- `AstExprGroup` — parenthesized expression
- `AstExprConstantNil`, `AstExprConstantBool`, `AstExprConstantNumber`, `AstExprConstantInteger`, `AstExprConstantString`
- `AstExprLocal`, `AstExprGlobal`, `AstExprVarargs`
- `AstExprCall`, `AstExprIndexName`, `AstExprIndexExpr`
- `AstExprFunction`, `AstExprTable`
- `AstExprUnary`, `AstExprBinary`
- `AstExprTypeAssertion` — `expr :: Type`
- `AstExprIfElse` — `if cond then e1 else e2`
- `AstExprInterpString` — backtick string with embedded expressions
- `AstExprInstantiate` — explicit type instantiation

**Statement nodes** (`AstStat` subclasses):
- `AstStatBlock`, `AstStatIf`, `AstStatWhile`, `AstStatRepeat`
- `AstStatBreak`, `AstStatContinue`, `AstStatReturn`, `AstStatExpr`
- `AstStatLocal`, `AstStatFor`, `AstStatForIn`
- `AstStatAssign`, `AstStatCompoundAssign`
- `AstStatFunction`, `AstStatLocalFunction`
- `AstStatTypeAlias`, `AstStatTypeFunction`

**Other**: `AstAttr` (function attributes like `@native`), `AstGenericType`, `AstGenericTypePack`.

---

## 3. Bytecode Format

**Primary header**: `Common/include/Luau/Bytecode.h`  
**Reference**: https://deepwiki.com/luau-lang/luau/4.2-bytecode-format

### 3.1 Encoding

All instructions are **32-bit words** (word code, not byte code). Three encoding forms:

| Form | Layout | Use |
|---|---|---|
| ABC | `[op:8][A:8][B:8][C:8]` | Three-register ops: arithmetic, table access |
| AD | `[op:8][A:8][D:16s]` | One register + 16-bit signed: jump offsets, constant indices |
| E | `[op:8][E:24s]` | 24-bit signed: `JUMPX`, `COVERAGE` |

Some instructions consume an additional **AUX word** (32-bit auxiliary) immediately following the header word; the opcode determines whether AUX is present.

Extraction macros: `LUAU_INSN_OP(i)`, `LUAU_INSN_A(i)`, `LUAU_INSN_B(i)`, `LUAU_INSN_C(i)`, `LUAU_INSN_D(i)`, `LUAU_INSN_E(i)`.

### 3.2 Index Limits

| Resource | Range |
|---|---|
| Registers | 0–254 (255 total per function) |
| Upvalues | 0–199 |
| Constants | 0–8,388,607 (2²³−1) |
| Closures (child protos) | 0–32,767 |
| Jump offsets | ±8,388,608 words |

### 3.3 Opcode Table (89 opcodes)

**Control flow**: `NOP`, `BREAK`, `JUMP`, `JUMPBACK`, `JUMPX`, `JUMPIFEQ`, `JUMPIFLE`, `JUMPIFLT`, `JUMPIFNOTEQ`, `JUMPIFNOTLE`, `JUMPIFNOTLT`, `JUMPXEQKNIL`, `JUMPXEQKB`, `JUMPXEQKN`, `JUMPXEQKS`

**Load/store**: `LOADNIL`, `LOADB`, `LOADN`, `LOADK`, `LOADKX`, `MOVE`, `GETGLOBAL`, `SETGLOBAL`, `GETUPVAL`, `SETUPVAL`, `CLOSEUPVALS`, `GETIMPORT`

**Table**: `GETTABLE`, `SETTABLE`, `GETTABLEKS`, `SETTABLEKS`, `GETTABLEN`, `SETTABLEN`, `NEWTABLE`, `DUPTABLE`, `SETLIST`, `GETUDATAKS`, `SETUDATAKS`

**Arithmetic**: `ADD`, `SUB`, `MUL`, `DIV`, `MOD`, `POW`, `IDIV` (floor div), `ADDK`, `SUBK`, `MULK`, `DIVK`, `MODK`, `POWK`, `IDIVK`, `SUBRK` (constant − reg), `DIVRK` (constant / reg)

**Logical/unary**: `AND`, `OR`, `ANDK`, `ORK`, `NOT`, `MINUS`, `LENGTH`

**Strings**: `CONCAT`

**Functions**: `CALL`, `RETURN`, `TAILCALL`, `NEWCLOSURE`, `DUPCLOSURE`, `NAMECALL`, `NAMECALLUDATA`, `CAPTURE`, `PREPVARARGS`, `GETVARARGS`

**Fast calls**: `FASTCALL`, `FASTCALL1`, `FASTCALL2`, `FASTCALL2K`, `FASTCALL3`

**Loops**: `FORNPREP`, `FORNLOOP`, `FORGLOOP`, `FORGPREP_INEXT`, `FORGPREP_NEXT`

**Misc**: `COVERAGE`, `NATIVECALL`, `CALLFB`, `CMPPROTO`, `NEWCLASSMEMBER`

### 3.4 Constant Pool Types (`LuauBytecodeTag`)

`LBC_CONSTANT_NIL` (0), `BOOLEAN` (1), `NUMBER` (2), `STRING` (3), `IMPORT` (4), `TABLE` (5), `CLOSURE` (6), `VECTOR` (7), `TABLE_WITH_CONSTANTS` (8), `INTEGER` (9), `CLASS_SHAPE` (10)

Strings are interned. `IMPORT` constants encode multi-part global paths like `math.max` resolved at script load. `TABLE` constants back the `DUPTABLE` fast-path for table literals.

### 3.5 Bytecode Version History

| Version | Added |
|---|---|
| 3 | `FORGPREP`, `JUMPXEQK*` variants |
| 4 | `Proto::flags`, typeinfo encoding, floor division (`IDIV`/`IDIVK`) |
| 5 | `SUBRK`/`DIVRK`, vector constants |
| 6 | `FASTCALL3` |
| 7 | Constant tables with pre-filled values |
| 8 | 64-bit integer constants |
| 9 | Atom-based userdata field acceleration (`GETUDATAKS`/`SETUDATAKS`) |
| 10 | Class instructions (experimental) |
| 11 | Feedback vector description (experimental) |

Compiler targets version 6 by default. Supported reader range: 3–11.

### 3.6 Proto Flags

`LPF_NATIVE_MODULE` (1<<0): module-level `--!native` annotation.  
`LPF_NATIVE_COLD` (1<<1): function excluded from native compilation (no loops, rarely called).  
`LPF_NATIVE_FUNCTION` (1<<2): function-level `@native` attribute.  
`LPF_INLINABLE` (1<<3): compiler determined function is safe to inline.

### 3.7 Type Info Encoding (Proto-level)

Three versions:
- v1: Function signature types only
- v2: Extends to arguments, upvalues, locals, temporaries
- v3: Adds userdata type names for native dispatch

The type info is a separate byte stream in the proto used by the native codegen to skip type guards.

---

## 4. VM Execution Model

**Key file**: `VM/src/lvmexecute.cpp`  
**Reference**: https://deepwiki.com/luau-lang/luau/5-virtual-machine

### 4.1 TValue: Tagged 16-byte Value

```c
typedef union {
    GCObject* gc;   // heap-allocated objects
    void*     p;    // light userdata
    double    n;    // numbers
    int       b;    // booleans
    int64_t   l;    // 64-bit integers
    float     v[2]; // vector components (with extra[] for 3rd/4th)
} Value;

typedef struct lua_TValue {
    Value value;
    int   extra[LUA_EXTRA_SIZE];  // extra space for vector 3rd/4th components
    int   tt;                      // type tag
} TValue;
```

**Luau does NOT use NaN boxing.** Each TValue is 16 bytes: 8-byte value union + 4-byte extra[] + 4-byte type tag. This choice enables the native **vector type** (3 or 4 floats) as a first-class value without heap allocation — vectors live directly in the 16-byte slot.

**Type tags** (`lua_Type` enum):
`LUA_TNIL` (0), `LUA_TBOOLEAN` (1), `LUA_TLIGHTUSERDATA`, `LUA_TNUMBER`, `LUA_TINTEGER`, `LUA_TVECTOR`, `LUA_TSTRING`, `LUA_TTABLE`, `LUA_TFUNCTION`, `LUA_TUSERDATA`, `LUA_TTHREAD`, `LUA_TBUFFER`, `LUA_TCLASS`, `LUA_TOBJECT`, `LUA_TDEADKEY`, `LUA_TPROTO`, `LUA_TUPVAL`

### 4.2 lua_State (Per-Thread)

Fields in `VM/src/lstate.h`:

| Field | Type | Purpose |
|---|---|---|
| `status` | `uint8_t` | Thread execution status |
| `isactive` | `uint8_t` | Currently executing |
| `singlestep` | `uint8_t` | Debugger single-step mode |
| `top` | `StkId` | Stack top pointer |
| `base` | `StkId` | Current frame base |
| `global` | `global_State*` | Shared global state |
| `ci` | `CallInfo*` | Current call frame |
| `stack` / `stack_last` | `TValue*` | Stack array bounds |
| `base_ci` / `end_ci` | `CallInfo*` | CallInfo array bounds |
| `stacksize` / `size_ci` | `int` | Capacity |
| `nCcalls` / `baseCcalls` | `int` | C call depth tracking |
| `cachedslot` | `int` | Inline cache hint |
| `gt` | `Table*` | Global environment table |
| `openupval` | `GCObject*` | List of open upvalues |
| `gclist` | `GCObject*` | GC tracking list |
| `namecall` | `TString*` | Method name for NAMECALL |
| `userdata` | `void*` | Embedder-controlled pointer |

### 4.3 global_State (Shared Across Threads)

Key fields:

| Field | Purpose |
|---|---|
| `strt` | Interned string hash table |
| `frealloc` / `ud` | Allocator function + user data |
| `currentwhite` / `gcstate` | GC color epoch + phase |
| `gray` / `grayagain` / `weak` | GC worklists |
| `GCthreshold` / `totalbytes` | Pacing thresholds |
| `freepages` / `freegcopages` | Page pool free lists |
| `mainthread` | Primary lua_State |
| `mt[NUM_TYPES]` | Default metatables per type |
| `registry` | Lua registry table |
| `rngstate` | `xoshiro256**` PRNG state |
| `udatadirect` | Per-tag userdata direct-access callbacks |
| `gcstats` / `gcmetrics` | Profiling data |

### 4.4 CallInfo (Call Frame)

```c
struct CallInfo {
    StkId     base;      // frame base (register 0)
    StkId     func;      // function value on stack
    StkId     top;       // frame top
    const Instruction* savedpc;  // saved program counter
    int       nresults;  // expected return count (-1 = vararg)
    unsigned  flags;     // RETURN_SKIP | HANDLE_VARARG | NATIVE | OPYIELD
};
```

`LUA_CALLINFO_NATIVE` flag: frame is executing native-compiled code, interpreter dispatch skipped.

### 4.5 Dispatch Loop (`lvmexecute.cpp`)

The interpreter uses **computed gotos** (`VM_USE_CGOTO`) on GCC/Clang, falling back to `switch` on MSVC. This produces a dispatch table of 256 label addresses — one per opcode slot — avoiding branch mispredictions from switch dispatch.

Core interpreter state lives in **local C variables** (not struct fields) for register allocation:
- `cl` — current closure pointer
- `base` — frame base (register 0 pointer)
- `k` — constant table pointer
- `pc` — program counter

This allows the compiler to keep them in CPU registers for the hot loop.

**VM_NEXT()** fetches the next instruction and jumps to its handler label in one step.

**VM_PROTECT()** macro: saves `pc` before calls that may reallocate the stack, restores `base` after.

**VM_REG(i)** and **VM_KV(i)**: access registers and constants with assertion-guarded bounds.

**Inline caching**: `VM_PATCH_C()` self-modifies the currently executing instruction to embed a predicted hash slot for `GETTABLEKS`/`SETTABLEKS`. On the next execution of the same bytecode, the slot is checked first (fast path) before fallback to full hash lookup.

**Import caching**: `GETIMPORT` resolves multi-part globals (e.g., `math.max`) at first execution and replaces itself with a direct reference, bypassing global table lookups on subsequent calls.

**FASTCALL protocol**: `LOP_FASTCALL*` instructions attempt a direct C call to a builtin. If the builtin's preconditions fail (wrong argument type), execution falls through to the subsequent `LOP_CALL` which handles the general case.

### 4.6 Object Layout (GCheader / GCObject)

Every GC-managed object starts with `CommonHeader`:
```c
#define CommonHeader  GCObject* next; uint8_t tt; uint8_t marked; uint8_t memcat; uint8_t flag
```

`marked` byte encodes tri-color state:
- `WHITE0_BIT` / `WHITE1_BIT`: object is candidate for collection (two generations, epoch alternates)
- `BLACK_BIT`: object reachable + children scanned
- Gray = all color bits unset, present in gray worklist

`GCObject` union covers: `TString`, `Udata`, `Closure`, `LuaTable`, `Proto`, `UpVal`, `lua_State`, `LuauBuffer`, `LuauClass`, `LuauObject`.

**TString** (`lstring.h` data): `CommonHeader` + `int16_t atom` (interning index for fast comparison) + `TString* next` (hash chain) + `unsigned hash` + `unsigned len` + `char data[1]` (inline string data).

**Proto** (function prototype): bytecode instruction array, constant table, nested Proto array, upvalue descriptor array, local variable debug info, line info array, feedback vector for native codegen hints, type info byte array (v2/v3).

### 4.7 Vector Type

`LUA_TVECTOR` stores 3 (default) or 4 (when `LUA_VECTOR_SIZE=4`) 32-bit floats directly in the 16-byte TValue slot. No heap allocation. Vector arithmetic operations (`ADD_VEC`, `SUB_VEC`, `MUL_VEC`, `DOT_VEC`, `EXTRACT_VEC`) exist in the IR. This is a major GC-pressure reduction for game code doing lots of spatial math.

Component access via `.x`/`.X`, `.y`/`.Y`, `.z`/`.Z`, `.w`/`.W`. Vectors are immutable — no per-component write.

### 4.8 Coroutines

`lua_newthread()` creates an independent `lua_State` with its own stack but sharing `global_State`. `lua_resume()` / `lua_yield()` implement symmetric coroutine transfer. `lua_xmove()` / `lua_xpush()` move values between stacks. `lua_resetthread()` allows coroutine reuse. `lua_break()` triggers a debugger breakpoint mid-execution.

---

## 5. Compiler (`Compiler/src/Compiler.cpp`)

### 5.1 Pipeline

```
AstStatBlock  →  Compiler struct  →  BytecodeBuilder  →  serialized bytecode blob
```

Three optimization levels:
- **O0**: minimal transforms, fastest compile
- **O1** (default): constant folding, builtin fastcalls, simple dead code elimination
- **O2**: O1 + function inlining + loop unrolling

### 5.2 Register Allocator

Registers 0–254 per function. The compiler tracks:
- `regTop`: highest active register
- `stackSize`: maximum registers used (bounds stack frame)
- `localStack`: maps `AstLocal*` → register slot

Temporaries are allocated and freed around expression evaluation. Locals persist through their declared scope.

### 5.3 Upvalue Capture

`getUpval()` walks the enclosing function chain to find the capturing local, marking it with a "captured" flag. On scope exit, `LOP_CLOSEUPVALS` is emitted to promote live upvalues from stack slots to heap-allocated `UpVal` objects. Limit: 200 upvalues per closure.

### 5.4 FASTCALL Emission

Recognized builtins (from a hardcoded table in `lbuiltins.h`) map to builtin IDs. The compiler emits:
1. `LOP_FASTCALL` / `LOP_FASTCALL1` / `LOP_FASTCALL2` / `LOP_FASTCALL2K` / `LOP_FASTCALL3` (with the builtin ID in the C field)
2. Immediately followed by `LOP_CALL` as fallback

VM executes the fastcall; if type guards pass, execution skips the `LOP_CALL`. Builtins include `math.sqrt`, `math.abs`, `math.floor`, `math.max`, `math.min`, `table.insert`, `table.remove`, `string.len`, `assert`, `type`, `select`, `bit32.*`, etc.

### 5.5 Function Inlining

Criteria: called function marked inlinable (`LPF_INLINABLE`), `regTop ≤ 128`, inlined stack depth `≤ 32`, not self-recursive. Cost model: `LuauCompileLoopUnrollThreshold` default 25, boost cap 300. Inline budget scales with constant argument benefit.

### 5.6 Loop Unrolling

Numeric `for` loops with constant bounds and step: compiler unrolls if body instruction count × iterations ≤ threshold. Unrolled loops receive `LPF_NATIVE_COLD` suppression hints.

### 5.7 DUPCLOSURE / NEWCLOSURE

`DUPCLOSURE` reuses a previously created closure object if no captured upvalues reference stack slots (pure constant closures). `NEWCLOSURE` allocates fresh closures when upvalues must be captured.

---

## 6. Gradual Type System (Analysis Module)

**Papers**: https://arxiv.org/pdf/2109.11397 (goals paper)  
**Reference**: https://deepwiki.com/luau-lang/luau/2.1-type-system-core

### 6.1 Architecture

```
Source → Parser → AST
                   ↓
          ConstraintGenerator  → Constraint list
                   ↓
          ConstraintSolver     → Types resolved
                   ↓
          Normalizer / Subtyping checks
                   ↓
          TypedAst / error diagnostics
```

`Frontend` class orchestrates the pipeline, module loading, and caching.

Two arenas per module: `interfaceTypes` (exported, persisted) and `internalTypes` (inference-only, discarded after checking).

### 6.2 Type Representations (`TypeVar` variants)

| Variant | Meaning |
|---|---|
| `PrimitiveType` | `nil`, `boolean`, `number`, `string`, `thread`, `buffer` |
| `SingletonType` | Literal bool (`true`/`false`) or specific string value |
| `FreeType` | Unbound inference variable, carries `Scope*` + optional bounds |
| `BoundType` | Alias to resolved type; `follow()` traverses chains |
| `FunctionType` | `argTypes: TypePackId`, `retTypes: TypePackId`, generic params |
| `TableType` | `props: Map<string, Property>`, optional indexer, metatable |
| `MetatableType` | Table + metatable pair |
| `UnionType` | `A \| B \| ...` |
| `IntersectionType` | `A & B & ...` (used for overloads) |
| `ClassType` | Userdata/host type with hierarchy |
| `BlockedType` | Inference placeholder until constraint resolves |
| `TypeFunctionInstanceType` | Pending type function application |
| `AnyType`, `UnknownType`, `NeverType` | Top/bottom types |

`TypeId` and `TypePackId` are opaque pointers into `TypeArena`. `follow(id)` dereferences `BoundType` chains.

### 6.3 Type Packs

`TypePack` is a rope structure: `head: vector<TypeId>` + `tail: TypePackId?`. `VariadicTypePack` encodes `...T`. `TypePackIterator` walks the rope, handling cycles. Used for function argument and return types — critical for modeling varargs and multiple return correctly.

### 6.4 Constraint Types (21 variants, `Analysis/include/Luau/Constraint.h`)

| Constraint | Description |
|---|---|
| `SubtypeConstraint` | `A <: B` |
| `PackSubtypeConstraint` | `Pack_A <: Pack_B` |
| `GeneralizationConstraint` | Free type → generic on scope close |
| `IterableConstraint` | `for-in` iterator unwrapping |
| `NameConstraint` | Associate name with type |
| `TypeAliasExpansionConstraint` | Expand pending type alias |
| `FunctionCallConstraint` | Resolve overload at call site |
| `FunctionCheckConstraint` | Bidirectional inference for lambdas |
| `PrimitiveTypeConstraint` | Validate primitive with singleton support |
| `HasPropConstraint` | Property access resolution |
| `HasIndexerConstraint` | Index operation resolution |
| `AssignPropConstraint` | Property assignment |
| `AssignIndexConstraint` | Index assignment |
| `UnpackConstraint` | Unpack type pack → individual types |
| `ReduceConstraint` | Compute type function instance |
| `ReducePackConstraint` | Compute type pack function |
| `EqualityConstraint` | Free type bound equality |
| `SimplifyConstraint` | Normalize type expression |
| `PushFunctionTypeConstraint` | Push expected function type into lambda |
| `TypeInstantiationConstraint` | Bind explicit type arguments |
| `PushTypeConstraint` | Push expected type |

### 6.5 Solver Algorithm

`ConstraintSolver` uses a **worklist algorithm**:
1. Dequeue constraint from worklist
2. If any operand is `BlockedType`, re-queue and skip
3. Otherwise apply constraint, bind free types, emit new constraints
4. `unblock()` re-adds dependent constraints when a blocking type resolves
5. `generalizeOneType()`: free type with zero remaining constraints → converted to generic

`Unifier2` handles subtyping unification, tracking `incompleteSubtypes` for deferred resolution. `occursCheck()` prevents infinite recursion.

### 6.6 Type Modes

- `--!nocheck`: analysis disabled
- `--!nonstrict` (default): permissive inference, inferred types
- `--!strict`: all untyped code rejected, full annotation required

### 6.7 Type Functions (User-Defined)

Since ~2024, Luau supports user-defined type functions via `type function foo(t) ... end` — functions that run at **type-check time** in a `TypeFunctionRuntime` sandbox. They receive and return `type` objects from the `types` library:

```luau
type function unwrap(t)
    if t:is("union") then
        return t:components()[1]  -- simplified
    end
    return t
end
type Unwrapped = unwrap<string | nil>  -- evaluates to string
```

Built-in type functions include `keyof`, `valueof`, `rawget`, `setmetatable`, `typeof`, `union`, `intersection`, arithmetic/comparison type functions.

The `ReduceConstraint` / `ReducePackConstraint` trigger type function evaluation when argument types are known.

### 6.8 What Makes the Type System Hard

- **Metatables**: `setmetatable` changes a table's runtime type; the type system must track `MetatableType` pairs and resolve `__index` chains statically
- **Multiple returns**: type packs as ropes, pack subtyping, variadic pack unification
- **Generics with type packs**: `function f<T...>(...)` — generic packs interact with variadic inference
- **Bidirectional inference**: `FunctionCheckConstraint` for lambdas passed as arguments
- **Free type generalization**: determining when a free type has no more constraints and can be generalized vs. defaulted to `unknown`
- **Cyclical types**: recursive type aliases, tables with self-references, `occursCheck` to prevent infinite loops
- **Class hierarchy**: userdata with `__index` chains, method overloads, inheritance must model runtime Roblox instance types

---

## 7. Native Code Generation (`CodeGen/`)

**References**: https://luau.org/2023/11/01/luau-recap-october-2023.html, https://devforum.roblox.com/t/luau-native-code-generation-preview-studio-beta/2572587

### 7.1 Pipeline

```
Proto bytecode
     ↓
IrTranslation (bytecode → IR)
     ↓
IR optimization passes (constant prop, DCE, type specialization)
     ↓
IrLoweringX64  /  IrLoweringA64  (IR → machine code)
     ↓
Native function (called via LUA_CALLINFO_NATIVE flag)
```

### 7.2 IR (`CodeGen/include/Luau/IrData.h`)

`IrCmd` enum (~200 opcodes):

**Load/store**: `LOAD_TAG`, `LOAD_POINTER`, `LOAD_DOUBLE`, `LOAD_INT`, `LOAD_TVALUE`, `STORE_TAG`, `STORE_POINTER`, `STORE_DOUBLE`, `STORE_INT`, `STORE_TVALUE`

**Integer arithmetic**: `ADD_INT`, `SUB_INT`, `ADD_INT64`, `SUB_INT64`, `MUL_INT64`, `DIV_INT64`, `IDIV_INT64`, `MOD_INT64`

**Float arithmetic**: `ADD_NUM`, `SUB_NUM`, `MUL_NUM`, `DIV_NUM`, `FLOOR_NUM`, `CEIL_NUM`, `SQRT_NUM`

**Vector**: `ADD_VEC`, `SUB_VEC`, `MUL_VEC`, `DOT_VEC`, `EXTRACT_VEC`

**Bitwise (int64)**: `BITAND_INT64`, `BITXOR_INT64`, `BITOR_INT64`, `BITNOT_INT64`, `BITLSHIFT_INT64`, `BITCOUNTLZ_INT64`, `BYTESWAP_INT64`

**Comparisons/jumps**: `CMP_INT`, `CMP_INT64`, `CMP_TAG`, `JUMP_CMP_INT`, `JUMP_CMP_NUM`, `JUMP_EQ_TAG`, `JUMP_SLOT_MATCH`, `JUMP_IF_TRUTHY`, `JUMP_IF_FALSY`

**Guards**: `CHECK_TAG`, `CHECK_TRUTHY`, `CHECK_READONLY`, `CHECK_ARRAY_SIZE`, `CHECK_NODE_VALUE`, `CHECK_CMP_NUM`

**Table**: `GET_ARR_ADDR`, `GET_SLOT_NODE_ADDR`, `TABLE_LEN`, `TABLE_SETNUM`, `NEW_TABLE`

**Control/calls**: `CALL`, `RETURN`, `GET_UPVALUE`, `SET_UPVALUE`, `INVOKE_FASTCALL`, `INVOKE_LIBM`

**GC/runtime**: `INTERRUPT`, `CHECK_GC`, `BARRIER_OBJ`, `COVERAGE`

**Type conversions**: `INT_TO_NUM`, `NUM_TO_INT`, `FLOAT_TO_NUM`, `NUM_TO_FLOAT`, `FLOAT_TO_VEC`

**Buffer ops**: `BUFFER_READI8`, `BUFFER_READI16`, `BUFFER_READI32`, `BUFFER_WRITEI8`, etc.

`IrOp` is a tagged union of: constant (int/double/uintptr), register index, block index, upvalue index, or `vmExit` (bail back to interpreter). `IrBlock` contains a list of `IrInst`. `IrFunction` wraps the block list + entry block.

### 7.3 Activation

Scripts must add `--!native` at module top OR `@native` attribute on individual functions. No automatic JIT profiling or tier-up — compilation decision is static. `LPF_NATIVE_MODULE` flag in Proto marks the entire module; `LPF_NATIVE_COLD` suppresses functions with no loops (cold-path heuristic).

### 7.4 Type Annotations in Native Code

With `-O2` + `--!native`, function argument type annotations (e.g., `function f(x: number)`) allow the codegen to skip `CHECK_TAG` guards in the function body. This is the primary performance lever for user-controlled native optimization. Annotation propagation into function bodies is done via dataflow.

### 7.5 Architecture Targets

- **x64**: requires AVX1 minimum. `IrLoweringX64.cpp`.
- **A64 (AArch64)**: targets Apple M1–M3 and ARM servers. `IrLoweringA64.cpp`.

Fallback: any function that can't be natively compiled (e.g., uses `getfenv`/`setfenv`, deoptimized by wrong type) falls back to bytecode interpreter transparently.

Expected speedup: **1.5×–2.5×** for compute-heavy code.

---

## 8. Garbage Collector (`VM/src/lgc.cpp`, `VM/src/lmem.cpp`)

**Reference**: https://deepwiki.com/luau-lang/luau/5.3-memory-management-and-garbage-collection

### 8.1 Algorithm

Tri-color incremental mark-and-sweep (not generational, not moving/compacting).

Three phases per cycle:
1. **Mark** (incremental): root set scan, gray worklist draining in steps
2. **Atomic** (stop-the-world): flush remaining gray set, barrier-tracked objects (`grayagain`), weak table processing
3. **Sweep** (incremental): page-granularity freeing of white objects

Colors in `GCheader::marked`:
- White (current epoch): `currentwhite` bit set → candidate for collection
- Gray: all color bits clear, object in gray worklist (`global_State::gray`)
- Black: `BLACKBIT` set → reachable, children scanned

Two white epochs (`white0`/`white1`) alternate across cycles to avoid resetting all objects.

### 8.2 Write Barriers

**Forward barrier** (`luaC_barrier`): immediately marks the child non-white. Used for upvalue writes and `setmetatable`. Advances GC progress; costly when object modified repeatedly.

**Backward barrier** (`luaC_barriert`): marks the parent gray, re-queues for rescan. Used for table field writes (primary case). Cheaper for hot write paths. During second-phase mark (after atomic), table writes switch to forward barriers.

`luaC_threadbarrier`: special barrier for coroutines — marks coroutine gray during atomic if a new reference is written into it.

### 8.3 Paced Collection

GC pacing inspired by Go's pacer. Three tunable parameters:
- `gcgoal`: target heap size as % of live objects (default 200% = heap ≤ 2× live)
- `gcstepmul`: GC marking rate relative to allocation rate (must be >1 to make progress)
- `gcstepsize`: kilobytes allocated between GC steps

Controllable via `lua_gc(L, LUA_GCSETGOAL/LUA_GCSETSTEPMUL/LUA_GCSETSTEPSIZE, val)`.

`lua_gc(L, LUA_GCSTEP, ...)` runs one incremental step.

### 8.4 Paged Allocator (`lmem.cpp`)

All GC objects allocated in 16 KB pages (small objects ≤ 512 bytes) or 32 KB pages (large objects > 512 bytes). Page header (`lua_Page`):
- Doubly-linked in `freepages` / `allgcopages` lists
- Block size, page size
- `freeNext` (bump allocator, goes negative when exhausted)
- `busyBlocks` count
- 16-byte aligned `data[]`

When `freeNext < 0`, allocator switches to freelist intrusive linked list within the page data area.

Sweeper processes one full page per incremental step (`luaM_freegco` frees GCO blocks within a page), giving **2–3× faster sweep** vs. linked-list sweeping.

Per-size-class free page lists enable O(1) allocation for common sizes.

### 8.5 String Interning

All strings are interned in `global_State::strt` (hash table of `TString*`). `lua_tostringatom()` returns a 16-bit atom index for fast string identity comparison without hash recomputation. Method name strings (for `NAMECALL`) get atoms assigned at registration time via `lua_Callbacks::useratom`.

### 8.6 GC vs Lua 5.x

Lua 5.1 uses a basic linked-list tri-color incremental GC. Luau's GC differs:
- Paged allocator (page-granularity sweep)
- PID-inspired pacer
- Incremental coroutine marking
- Memory categories (`memcat` field, `lua_setmemcat`) for per-category accounting
- Buffer type (`LUA_TBUFFER`) as first-class GC object

---

## 9. Standard Library

**Reference**: https://luau.org/library/

### Global functions
`assert`, `error`, `print`, `warn`, `tostring`, `tonumber`, `type`, `typeof`, `rawget`, `rawset`, `rawequal`, `rawlen`, `select`, `ipairs`, `pairs`, `next`, `unpack`, `pcall`, `xpcall`, `require`, `load`, `collectgarbage`, `newproxy`, `setmetatable`, `getmetatable`

### `math`
Standard trig, `floor`/`ceil`/`round`, `exp`/`log`, `sqrt`/`pow`, `abs`, `sign`, `min`/`max`, `clamp`, `lerp`, `noise` (Perlin), `random`/`randomseed`, `huge`, `pi`, `maxinteger`/`mininteger`, `tointeger`

### `string`
`find`, `match`, `gmatch`, `gsub`, `sub`, `upper`, `lower`, `reverse`, `rep`, `byte`, `char`, `format`, `split`, `pack`, `unpack`, `packsize`, `len`

### `table`
`insert`, `remove`, `sort`, `concat`, `pack`, `unpack`, `create`, `clone`, `find`, `clear`, `freeze`, `isfrozen`, `move`

### `coroutine`
`create`, `resume`, `yield`, `wrap`, `status`, `running`, `isyieldable`, `close`

### `bit32`
`band`, `bor`, `bxor`, `bnot`, `lshift`, `rshift`, `arshift`, `lrotate`, `rrotate`, `extract`, `replace`, `btest`, `countlz`, `countrz`, `byteswap`

### `utf8`
`codepoint`, `char`, `len`, `offset`, `codes`, `charpattern`

### `buffer`
Fixed-size binary buffers. `create(size)`, `len`, `copy`, `fill`, `readstring`/`writestring`, integer read/write: `readi8`/`writei8`, `readu8`/`writeu8`, `readi16`/`writei16`, `readu16`/`writeu16`, `readi32`/`writei32`, `readu32`/`writeu32`, `readf32`/`writef32`, `readf64`/`writef64`

### `vector`
`vector.create(x, y, z[, w])`, `vector.magnitude(v)`, `vector.normalize(v)`, `vector.dot(a, b)`, `vector.cross(a, b)`, `vector.floor`/`ceil`/`clamp`/`min`/`max`, component-wise arithmetic

### `os`
`os.clock()`, `os.time()`, `os.date()`, `os.difftime()`

### `debug`
`debug.info(level, what)`, `debug.traceback()`, `debug.profilebegin`/`profileend`

### Not in Luau (removed from Lua)
`io`, `package`, `os.execute`, `os.exit`, `os.getenv`, `load` (in restricted form), `dofile`, `loadfile` — excluded for sandbox safety.

---

## 10. Syntax Additions Over Lua 5.1

### Type Annotations
```luau
local x: number = 5
local function add(a: number, b: number): number
    return a + b
end
type Point = { x: number, y: number }
type Callback = (string, number) -> boolean
type StringOrNil = string?           -- shorthand for string | nil
type Map<K, V> = { [K]: V }
export type Alias = ...              -- cross-module export
```

### String Interpolation (backtick strings)
```luau
local name = "world"
print(`Hello, {name}!`)             -- runtime concatenation
print(`{1 + 2} is {math.sqrt(9)}`)  -- arbitrary expressions inside {}
```
Lexed as `InterpStringBegin` / `InterpStringMid` / `InterpStringEnd` tokens.

### Compound Assignment
```luau
x += 1;  x -= 1;  x *= 2;  x /= 2
x //= 3; x %= 4;  x ^= 2;  x ..= "!"
```
Desugars to `x = x op rhs` at compile time. No `++`/`--`.

### `continue`
```luau
for i = 1, 10 do
    if i % 2 == 0 then continue end
    print(i)
end
```
Contextual keyword (parses as `Name` `continue` in statement position).

### If-Then-Else Expressions
```luau
local abs = if x >= 0 then x else -x
```
Must have both `then` and `else` branches. Different from `and`/`or` pattern — no short-circuit issues with falsy values.

### Generalized Iteration
```luau
for k, v in someTable do ... end   -- calls __iter metamethod if present
```
`FORGLOOP` with `__iter` support. `FORGPREP_INEXT` / `FORGPREP_NEXT` are fast paths for standard `ipairs`/`pairs` patterns detected at compile time.

### Floor Division
```luau
local q = 7 // 2   -- 3 (floor division)
```
`LOP_IDIV` / `LOP_IDIVK` opcodes. `__idiv` metamethod.

### Function Attributes
```luau
@native
local function hotPath(x: number): number
    return x * x
end
```
`@deprecated` also recognized. Parsed as `AstAttr` nodes.

### `read-only` and `const`
```luau
local TABLE_SIZE <const> = 100     -- compile-time constant, can be inlined
```
`<const>` prevents reassignment; compiler may fold value into bytecode.

### Type Functions (in Analysis, type-level execution)
```luau
type function optional(t)
    return types.optional(t)
end
type MaybeString = optional<string>
```

### Binary / Underscore Numeric Literals
```luau
local mask = 0b11001010
local million = 1_000_000
local hex = 0xFF_A0_3C
```

### Unicode Escapes
```luau
local s = "\u{1F600}"     -- UTF-8 encoded emoji
local h = "\xAB\xCD"      -- hex escape
```

---

## 11. Differences from Vanilla Lua 5.x

### vs Lua 5.1 (baseline)

| Feature | Lua 5.1 | Luau |
|---|---|---|
| Type annotations | None | Gradual type system |
| String interpolation | None | Backtick `\`...\`` syntax |
| `continue` | None | Yes (contextual keyword) |
| Compound assignment | None | `+=`, `-=`, `*=`, `/=`, `//=`, `%=`, `^=`, `..=` |
| If-then-else expr | None | `if c then a else b` |
| Floor division | None | `//` operator |
| Generalized iteration | None | `__iter` metamethod |
| Vector type | None | First-class `LUA_TVECTOR` |
| Buffer type | None | `LUA_TBUFFER` |
| `bit32` | None (external) | Built-in |
| `utf8` | None | Built-in |
| Integer type | None | `LUA_TINTEGER` (int64) |
| `table.freeze` | None | Yes |
| Read-only tables | None | `lua_setreadonly` |
| Coroutine reset | None | `lua_resetthread` |
| `@native` attr | None | Yes |
| GC tuning | Basic | PID pacer, memcat, page allocator |
| Native codegen | None | x64 + A64 JIT |

### vs Lua 5.2

- No `goto` (removed from Luau for safety / type system simplicity)
- No `_ENV` as an upvalue (Luau uses `LUA_GLOBALSINDEX` pseudo-index style)
- No `table.pack` name conflict (Luau has `table.pack`)
- `setfenv`/`getfenv` present for compatibility but disable import optimization

### vs Lua 5.3+

- No native integer type semantics in arithmetic (Luau numbers are `double`; `LUA_TINTEGER` exists but is distinct)
- No `utf8.offset` with negative positions
- No `table.move` polarity differences
- Luau `bit32` library uses 32-bit unsigned semantics matching Lua 5.2 `bit32`, not Lua 5.3 bitwise operators

### vs LuaJIT

- LuaJIT uses **NaN boxing** (8-byte values); Luau uses **tagged structs** (16-byte values)
- LuaJIT has tracing JIT + interpreter; Luau has method-at-a-time AOT-style native codegen
- LuaJIT targets Lua 5.1 semantics; Luau adds type system on top
- LuaJIT FFI not in Luau (replaced by Buffer + C API userdata mechanisms)

---

## 12. What Makes Luau Hard to Reimplement

1. **Bytecode self-modification**: Inline caching via `VM_PATCH_C()` modifies the running instruction stream. Requires mutable instruction arrays and careful PC management.

2. **Type pack unification**: Function types with multiple returns and varargs require rope-structured type packs with cycle detection and incremental resolution.

3. **Constraint solver blocking**: The 21 constraint variants with `BlockedType` / `unblock()` worklist protocol — correctly implementing deferred resolution without infinite loops or missed constraints is subtle.

4. **Write barrier protocol**: Two barrier directions (forward/backward), mode-dependent switching during atomic phase, thread barrier for coroutines — misimplementing causes silent GC unsoundness.

5. **Paged allocator + incremental sweeper**: Page-granularity sweeping requires page membership tracking for every GCO, non-trivial with `luaM_freegco(page)`.

6. **FASTCALL two-instruction protocol**: `FASTCALL*` + `CALL` pairs with PC skip logic and fallthrough semantics must be preserved precisely.

7. **Import resolution and cache invalidation**: `GETIMPORT` self-modifying optimization disabled by `getfenv`/`setfenv`/`loadstring`. Tracking invalidation conditions correctly.

8. **Coroutine stack independence**: Each coroutine has independent stack but shared `global_State`. `lua_xmove` across threads, `lua_resetthread`, and GC interaction with inactive coroutine stacks.

9. **Type function runtime**: User-defined type functions execute in a sandboxed Luau interpreter during type checking — a meta-interpreter requiring the full VM to be embeddable at type-check time.

10. **Vector as primitive value**: 16-byte TValue with vector stored inline (not heap-allocated) changes memory layout assumptions pervasively throughout the VM — arithmetic, GC tracing, stack frame sizing all affected.

---

## Sources

- [luau-lang/luau GitHub repository](https://github.com/luau-lang/luau)
- [luau.org official site](https://luau.org)
- [Bytecode.h — opcode definitions](https://github.com/luau-lang/luau/blob/master/Common/include/Luau/Bytecode.h)
- [lua.h — C API](https://github.com/luau-lang/luau/blob/master/VM/include/lua.h)
- [Constraint.h — constraint type definitions](https://github.com/luau-lang/luau/blob/master/Analysis/include/Luau/Constraint.h)
- [Bytecode Format — DeepWiki](https://deepwiki.com/luau-lang/luau/4.2-bytecode-format)
- [Virtual Machine — DeepWiki](https://deepwiki.com/luau-lang/luau/5-virtual-machine)
- [Type System Core — DeepWiki](https://deepwiki.com/luau-lang/luau/2.1-type-system-core)
- [Memory Management and GC — DeepWiki](https://deepwiki.com/luau-lang/luau/5.3-memory-management-and-garbage-collection)
- [Compiler — DeepWiki](https://deepwiki.com/luau-lang/luau/4-compiler)
- [How we make Luau fast — luau.org/performance](https://luau.org/performance)
- [Luau Syntax by Example](https://luau.org/syntax)
- [Standard Library](https://luau.org/library)
- [Type Functions Library](https://luau.org/types-library/)
- [Native Codegen October 2023 Recap](https://luau.org/2023/11/01/luau-recap-october-2023.html)
- [Native Codegen Roblox DevForum](https://devforum.roblox.com/t/luau-native-code-generation-preview-studio-beta/2572587)
- [How to Plan a Luau — Andy Friesen (parser design)](https://medium.com/@andy.friesen/how-to-plan-a-luau-augmenting-luas-syntax-with-types-7751a790f0d8)
- [Vector library RFC](https://rfcs.luau.org/vector-library.html)
- [IrLoweringA64.cpp (native codegen source)](https://github.com/luau-lang/luau/blob/6061a14e9f77608ad25ffa8e865471c65bf8404d/CodeGen/src/IrLoweringA64.cpp)
- [Goals of the Luau Type System (arXiv)](https://arxiv.org/pdf/2109.11397)
