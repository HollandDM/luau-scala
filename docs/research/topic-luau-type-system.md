# Luau Gradual Type System: Deep Technical Reference

Sources:
- [luau-lang/luau GitHub](https://github.com/luau-lang/luau)
- [Luau type introduction](https://luau.org/types/)
- [Luau grammar](https://luau.org/grammar)
- [Luau performance notes](https://luau.org/performance/)
- [Local Type Inference RFC](https://rfcs.luau.org/local-type-inference.html)
- [User-Defined Type Functions RFC](https://rfcs.luau.org/user-defined-type-functions.html)
- [New Type Solver General Release](https://devforum.roblox.com/t/general-release-luau-s-new-type-solver/4084991)
- [New Type Solver Beta](https://devforum.roblox.com/t/new-type-solver-beta/3155804)
- [Semantic Subtyping in Luau](https://luau.org/news/2022-10-31-luau-semantic-subtyping/)
- [Position Paper: Goals of the Luau Type System](https://arxiv.org/pdf/2109.11397)
- [Towards an Unsound But Complete Type System](https://research.luau-lang.org/incorrectness24/incorrectness24.pdf)
- [Goals of the Luau Type System, Two Years On](https://asaj.org/papers/hatra23.pdf)
- [DeepWiki Luau Overview](https://deepwiki.com/luau-lang/luau/1-luau-overview)

---

## 1. Overview: Gradual Typing in Luau

Luau is a dynamically typed scripting language derived from Lua 5.1 that adds a *gradual* type system — meaning types are optional, checked statically at analysis time, and **fully erased** before execution. The runtime sees only tagged dynamic values; it never enforces type annotations.

Luau's type system design is intentionally:

- **Gradual**: code without annotations type-checks under any mode via `any`.
- **Structural**: compatibility checked by shape, not nominal identity. Inherited from Lua's duck-typed semantics.
- **Pragmatically unsound**: nonstrict mode reports only "definite runtime errors" rather than all possible type violations. Papers from the Luau research team explicitly call this *unsound but complete* — accepting all valid programs even if it misses some bugs.
- **Error-suppressing with `any`**: `any` is not the dynamic type of gradual typing literature (with blame tracking); instead it *suppresses* error reporting entirely for that expression.

Key academic papers: the 2021 HATRA position paper, the 2023 two-years-on report (semantic subtyping focus), and the 2024 "Towards an Unsound But Complete Type System" paper (new nonstrict mode design).

---

## 2. Type Annotation Syntax

### 2.1 Mode Directives

Every Luau file begins with one of three directives (default is nonstrict if omitted):

```lua
--!nocheck      -- disables all inference and error reporting
--!nonstrict    -- default; permissive, reports only definite errors
--!strict       -- full inference; reports all detectable type violations
```

### 2.2 Binding Annotations

Grammar rule: `binding ::= NAME [':' Type]`

```lua
local x: number = 5
local name: string
function greet(n: string): string
    return "Hello, " .. n
end
```

### 2.3 Primitive Types

```
nil  boolean  number  string  thread  any  unknown  never  buffer
```

`any` suppresses type checking. `unknown` is the top type (all values assignable to it; cannot use without narrowing). `never` is the bottom type (uninhabited; no value has type `never`).

### 2.4 Function Types

```lua
-- basic
local f: (number, string) -> boolean

-- multiple returns
local g: (number) -> (string, boolean)

-- no returns
local h: (number) -> ()

-- variadic
local v: (...number) -> number

-- generic
local id: <T>(T) -> T
```

### 2.5 Table Types

```lua
-- record
local pt: { x: number, y: number }

-- array shorthand
local arr: { string }  -- equivalent to { [number]: string }

-- explicit indexer
local map: { [string]: number }

-- read-only properties (new solver)
local ro: { read name: string }

-- mixed
local t: { id: number, [string]: boolean }
```

### 2.6 Union and Intersection Types

```lua
type NumOrStr = number | string
type Optional = number?         -- sugar for number | nil
type Overload = ((number) -> string) & ((boolean) -> string)
type Both = { x: number } & { y: number }  -- structural merge
```

### 2.7 Generic Types

```lua
type Pair<T, U> = { first: T, second: U }
type Array<T> = { [number]: T }

-- with defaults (new solver)
type Container<T = string> = { value: T }
```

Generic type packs:

```lua
type Fn<A..., R...> = (A...) -> R...
```

### 2.8 Recursive Types

```lua
type Tree<T> = { value: T, children: { Tree<T> } }
type Json = nil | boolean | number | string | { [string]: Json } | { Json }
```

### 2.9 `typeof` in Type Position

```lua
local config = { host = "localhost", port = 8080 }
type Config = typeof(config)  -- { host: string, port: number }
```

No runtime evaluation; purely static.

### 2.10 Type Aliases and Exports

```lua
type UserId = number
export type Event = { name: string, time: number }  -- visible to requirers
```

### 2.11 Type Cast Operator

```lua
local x = expr :: number          -- assert expr has type number
local t = {} :: { [string]: any } -- declare shape of empty table
```

Cast is statically validated: one side must be subtype of the other, or one must be `any`.

### 2.12 Negation Types (new solver)

Negation types appear in type refinements, not user annotations:

```lua
-- after: if typeof(x) ~= "string" then ...
-- x narrows to ~string (any type except string)
```

The `NegationType` internal variant exists in `Type.h` but is not surface-level syntax.

---

## 3. Type Inference Modes in Depth

### 3.1 `--!nocheck`

Parser still runs; AST is built. Type inference pass is skipped. Zero type errors emitted. Used for performance or to silence legacy code entirely.

### 3.2 `--!nonstrict` (default)

Goal: report only *definite* runtime errors — things that would certainly throw at runtime with any concrete execution.

Behaviors:
- Unannotated local variables infer `any` if assigned before use in ambiguous cases.
- Global variable access is not flagged (globals are assumed to exist).
- Type errors from `any`-typed subexpressions are suppressed.
- The new solver runs one unified inference pass; the old solver inferred `any` for most expressions in nonstrict and provided less useful autocomplete.

```lua
--!nonstrict
local x         -- type: any (old solver), inferred from usage (new solver)
x = 1
x = "hello"    -- OK in nonstrict
```

### 3.3 `--!strict`

Full bidirectional inference. Unannotated parameters remain as free type variables that get constrained by usage. Unresolvable free types become generics.

```lua
--!strict
local x = 1       -- type: number
local y = x + ""  -- ERROR: string cannot be added to number
```

Unknown global access is an error. Unsealed tables cannot have new properties added outside their definition scope.

---

## 4. The Type Representation (Internal)

All types live in a `TypeArena` — a slab allocator. `TypeId` is a pointer into arena-allocated `Type` structs. `TypePackId` similarly for `TypePackVar`.

### 4.1 `TypeVariant` — the discriminated union

From `Analysis/include/Luau/Type.h`:

| Variant | Description |
|---|---|
| `FreeType` | Unconstrained type variable during inference; has lower/upper bounds |
| `GenericType` | Type variable bound by a generic function or alias |
| `BoundType` | Metavariable that has been unified to a concrete type (indirection) |
| `PrimitiveType` | Nil, Boolean, Number, Integer, String, Thread, Function, Table, Buffer |
| `SingletonType` | Literal boolean (`true`/`false`) or string singleton |
| `FunctionType` | Full signature: generics, arg pack, ret pack, magic handler |
| `TableType` | Properties map + optional indexer + table state (Unsealed/Sealed/Free/Generic) |
| `ExternType` | C++ userdata exposed via Luau embedding API |
| `MetatableType` | Table + metatable association |
| `UnionType` | `T \| U` — list of constituent types |
| `IntersectionType` | `T & U` — list of constituent types |
| `NegationType` | `~T` — complement of a type |
| `AnyType` | The `any` escape hatch; error-suppressing |
| `UnknownType` | Top type; no operations permitted without narrowing |
| `NeverType` | Bottom type; empty set |
| `BlockedType` | Sentinel for partial constraint ordering — blocks dependent constraints |
| `PendingExpansionType` | Type alias being instantiated (avoids infinite loops) |
| `TypeFunctionInstanceType` | Unreduced application of a type function |
| `LazyType` | Deferred type resolution wrapper |
| `NoRefineType` | Suppresses further refinement |

### 4.2 `TypePackVar`

Represents sequences of types (function arguments/returns). Variants: `TypePack` (concrete list + optional tail), `TypePackVariadicType` (homogeneous tail), `FreeTypePack`, `GenericTypePack`, `BoundTypePack`, `BlockedTypePack`, `TypeFunctionInstanceTypePack`.

### 4.3 Table State Machine

`TableType::TableState` controls mutability:
- `Unsealed`: freshly created with `{}`, can accumulate new properties until scope exit.
- `Sealed`: has explicit annotation or initial literal — no new properties permitted.
- `Free`: type variable table whose shape is being inferred.
- `Generic`: table parameter in generic context; indexing requires interface match.

---

## 5. The Analysis Pipeline

From `Analysis/include/Luau/Frontend.h`, the full pipeline:

```
Source text
    └─► Parser (Luau.Ast)          → AstStatBlock (AST)
            └─► DataFlowGraph      → def-use chains, DfgScope trees
                └─► ConstraintGenerator  → vector<Constraint>
                        └─► ConstraintSolver  → resolved TypeIds on all AST nodes
                                └─► TypeChecker2  → error/warning diagnostics
```

The `Frontend` class orchestrates this, maintaining:
- `sourceNodes` + `sourceModules` caches with dirty-state tracking per module.
- Two resolver instances: standard and autocomplete (different timeout/strictness).
- `moduleResolver` for `require()` resolution and cycle detection.
- `queueModuleCheck()` + `checkQueuedModules()` for parallel batch analysis.

---

## 6. Old Solver: `TypeChecker` (Luau.Analysis/TypeInfer)

The legacy solver in `Analysis/include/Luau/TypeInfer.h` uses **recursive descent type inference with eager unification**:

Key structures:
- `TypeChecker` — monolithic class; processes AST recursively.
- `check(AstStat*)` family — walks every statement node.
- `checkExpr(AstExpr*)` family — walks every expression, returning `TypeId`.
- `unify(TypeId, TypeId)` — immediate unification; binds free types on the spot.
- `instantiate()` — converts polytypes (foralls) to monotypes by substituting fresh type variables.
- `quantify()` — generalizes free types to generic type parameters at function boundaries.
- `TxnLog` — transaction log enabling rollback when overload resolution fails.
- `RefinementMap` — type guard predicates narrowing types in conditional branches.

**Problems with old solver:**
- Nonstrict mode inferred `any` for most expressions, making autocomplete less useful.
- Overloaded operator inference was inaccurate (no type functions for operators).
- Singleton type handling generated spurious warnings.
- Casting rules too restrictive.
- `unify()` calls mutated type variables eagerly, causing order-dependency bugs — the result could differ based on which branch was processed first.
- No bidirectional inference: lambda argument types could not be inferred from call-site context.

---

## 7. New Solver: Constraint-Based Architecture

Released to general availability in 2024 (exited Studio Beta ~September 2024). Three components:

### 7.1 `ConstraintGenerator`

From `Analysis/include/Luau/ConstraintGenerator.h`.

Walks the AST using a visitor pattern. Does NOT resolve types — only generates constraints.

Key structures:
- `Inference { TypeId, RefinementId }` — result of checking an expression.
- `InferencePack { TypePackId, refinements }` — multi-value result.
- `scopes: vector<ScopePtr>` — scope tree built during traversal.
- `constraints: vector<Constraint>` — accumulated constraint set.
- `DataFlowGraph` — def-use chains from prior pass, used for refinement key construction.

Key methods:
- `visitModuleRoot()` — entry point.
- `check(AstExpr*, expectedType?)` — bidirectional: propagates expected type downward.
- `checkPack()` — multi-value expressions.
- `addConstraint()` — registers a constraint with optional dependency set.
- `freshType(scope)` — creates `FreeType` with lower bound `never`, upper bound `unknown`.
- `resolveType()` — converts AST type annotation nodes to internal `TypeId`.
- `computeRefinement()` — processes `typeof`/equality guards into `RefinementKey` chains.
- `unionRefinements()` — merges refinements from if/else branches.

### 7.2 Constraint Variants

From `Analysis/include/Luau/Constraint.h`. Each constraint has a `Location` and a `scope`.

| Constraint | Semantics |
|---|---|
| `SubtypeConstraint { subType, superType }` | Assert `subType <: superType` |
| `PackSubtypeConstraint { subPack, superPack }` | Assert subtype on type packs |
| `EqualityConstraint { resultType, assignmentType }` | `assignmentType <: freeType <: resultType` |
| `GeneralizationConstraint { generalizedType, sourceType }` | Generalize free types to generics at scope exit |
| `IterableConstraint { iterator, variables }` | Unpack iterator protocol bindings |
| `FunctionCallConstraint { fn, argsPack, result }` | Resolve call + overload selection |
| `FunctionCheckConstraint { fn, argsPack, callSite }` | Push expected types into lambda args (bidirectional) |
| `HasPropConstraint { resultType, subjectType, prop }` | Property access type |
| `HasIndexerConstraint { resultType, subjectType, indexType }` | Index access type |
| `AssignPropConstraint { lhsType, propName, rhsType }` | Property write |
| `TypeAliasExpansionConstraint { target }` | Expand pending alias |
| `ReduceConstraint { ty }` | Reduce type function instance |
| `ReducePackConstraint { tp }` | Reduce type pack function instance |
| `SimplifyConstraint { ty }` | Simplify union/intersection |
| `PrimitiveTypeConstraint { freeType, expectedType, primitiveType }` | Resolve primitive literal |
| `UnpackConstraint { resultPack, sourcePack }` | Unpack type packs |
| `TypeInstantiationConstraint { functionType, typeArguments }` | Explicit generic instantiation |
| `PushTypeConstraint / PushFunctionTypeConstraint` | Propagate expected types (bidirectional) |

### 7.3 `ConstraintSolver`

From `Analysis/include/Luau/ConstraintSolver.h`.

Implements a **worklist algorithm** (constraint propagation loop):

```cpp
bool progress = false;
do {
    progress = runSolverPass(/*force=*/false);
    if (!progress)
        progress |= runSolverPass(/*force=*/true);
} while (progress);
finalizeTypeFunctions();
```

Each pass iterates `unsolvedConstraints`. For each constraint:
1. Check if blocked on any unresolved `TypeId`/`TypePackId`.
2. If unblocked: call `tryDispatch(constraint, force)`.
3. `tryDispatch` returns `true` → remove from worklist.
4. `tryDispatch` returns `false` → leave in worklist (blocked).
5. When a type variable is bound via `bind()`, `unblock()` wakes all constraints blocked on it.

When no progress after a full pass, the solver forces dispatch — this handles cycles or defaults unresolved free types.

Key methods:
- `bind(TypeId, TypeId)` — associate free type with resolved type; triggers `unblock`.
- `block(TypeId, Constraint*)` — register constraint as waiting on type resolution.
- `unify(TypeId, TypeId, scope)` — non-committing unification test; may push new constraints.
- `resolveModule(require)` — resolves `require()` calls for inter-module type flow.
- `lookupTableProp(subjectType, prop, context)` — resolves property access recursively through unions.

After solving, `TypeChecker2` does a final AST walk, looks up resolved types via `lookupType(AstExpr*)`, and reports subtyping violations as diagnostics.

---

## 8. Local Type Inference Algorithm

Based on Benjamin Pierce's Local Type Inference. Documented in the [Local Type Inference RFC](https://rfcs.luau.org/local-type-inference.html).

### 8.1 Free Type Bounds

Each `FreeType` carries two bounds:
- **Lower bound**: union of all types that have been assigned to this variable (`never` initially).
- **Upper bound**: intersection of all constraints imposed on this variable (`unknown` initially).

Invariant: `lower <: freeType <: upper`.

### 8.2 Constraint Dispatch Rules

| Constraint form | Effect |
|---|---|
| `T <: 't` (T flows into free type) | Union lower bound with T |
| `'t <: T` (free type must satisfy T) | Intersect upper bound with T |

The solver **never directly binds a free type** when dispatching a subtype constraint. Binding only happens during **generalization**.

### 8.3 Generalization

At function boundary exit, `GeneralizationConstraint` fires. For each unsolved free type, variance determines replacement:

| Position | Replacement |
|---|---|
| Covariant | Replace with lower bound |
| Contravariant | Replace with upper bound |
| No constraints | Becomes generic type parameter |
| Invariant | Requires bounded generic (partially implemented) |

Example — the `index_of` function with two return paths:

```lua
local function index_of(t, v)
    for i, x in ipairs(t) do
        if x == v then return i end
    end
    return nil
end
```

Return type free variable accumulates:
- Lower bound after `return i`: `number`
- Lower bound after `return nil`: `number | nil`
- Generalization → return type = `number | nil`

### 8.4 Key Difference from Hindley-Milner

HM global inference: unifies type variables across entire program, can "jump to conclusions" and produce surprising results. Local type inference: constraints are local to function scope, union lower bounds instead of unifying, preserves multi-path union types. No let-polymorphism in the HM sense — Luau does not infer polymorphic `let` bindings automatically.

---

## 9. Type Refinements and Narrowing

The `DataFlowGraph` provides def-use chains. `RefinementKey` chains encode property paths.

Control flow narrowing happens through:

```lua
-- typeof narrowing
if typeof(x) == "string" then
    -- x: string in this branch
    -- x: ~string in else branch (new solver negation type)
end

-- truthiness narrowing
local s: string? = maybeString()
if s then
    -- s: string (nil excluded)
end

-- assert narrowing
assert(typeof(x) == "number")
-- x: number after assert

-- tagged union discrimination
type Shape = { kind: "circle", r: number } | { kind: "rect", w: number, h: number }
if s.kind == "circle" then
    -- s: { kind: "circle", r: number }
end
```

`RefinementContext` maps definitions to discriminant type partitions. `unionRefinements()` merges from divergent branches.

---

## 10. Type Functions

Type functions are type-level computations, introduced with the new solver.

### 10.1 Built-in Type Functions

Built-in type functions correspond to operators and operations:

| Type Function | Purpose |
|---|---|
| `add<A, B>` | Result type of `A + B` |
| `sub<A, B>` | `A - B` |
| `mul<A, B>`, `div<A, B>`, `mod<A, B>`, `pow<A, B>`, `idiv<A, B>` | Arithmetic |
| `unm<T>` | Unary minus |
| `concat<A, B>` | String concatenation result |
| `len<T>` | Length operator |
| `eq<A, B>` | Equality comparison |
| `lt<A, B>`, `le<A, B>` | Comparison |
| `and<A, B>`, `or<A, B>`, `not<T>` | Logical operators |
| `keyof<T>`, `rawkeyof<T>` | Union of keys |
| `valueof<T>` | Union of value types |
| `rawget<T, K>` | Table index without metatable |
| `index<T, K>` | Table index with metatable |
| `setmetatable<T, M>` | Table with metatable type |
| `typeof<T>` | Runtime type string |
| `union<A, B, ...>`, `intersect<A, B, ...>` | Structural operations |

Enable generic operator inference: `function add<A, B>(a: A, b: B): add<A, B>`.

### 10.2 User-Defined Type Functions

Syntax (new solver):

```lua
type function myTransform(t)
    -- t is a `type` userdata
    if t:is("table") then
        return types.string
    end
    return t
end

type Result = myTransform<number>
```

Execution model:
1. Type solver encounters `TypeFunctionInstanceType` in constraint.
2. Serializes argument `TypeId`s into `type` userdata values.
3. Executes function body in sandboxed Luau VM (`TypeFunctionRuntime`).
4. Reifies result back to `TypeId`.

The `ReduceConstraint` blocks until all argument types are concrete (not `BlockedType`). Halting not guaranteed — delegated to analysis timeout.

Sandbox includes: `types` library, `assert`, `error`, `print`, `math`, `table`, `string`, `bit32`, `utf8`, `buffer`. No `require`, no I/O.

---

## 11. Normalization and Semantic Subtyping

`Normalizer` reduces types to canonical **P | T | F | G** form:
- **P**: union of primitives + singletons + extern types + error type.
- **T**: union of table types.
- **F**: union of function type intersections (overloads).
- **G**: union of generic/free/blocked types, each intersected with a normalized type.

Special cases supersede: `any`, `unknown`, `never`.

`NormalizedType` struct fields: `tops`, `booleans`, `externTypes`, `errors`, `nils`, `numbers`, `integers`, `strings`, `threads`, `buffers`, `tables`, `functions`, `tyvars`.

### 11.1 Semantic Subtyping

Luau interprets types as **sets of values**. Subtyping = set inclusion. This enables correct handling of cases syntactic subtyping gets wrong:

```
(number?) & (string?) = { nil }
```

Syntactically: two distinct optional types — hard to tell what intersection means.  
Semantically: `number | nil` ∩ `string | nil` = `nil` (only shared element).

Luau's pragmatic deviation from full set-theoretic types:
- Function types normalize to intersection of functions (overloads), not disjunctive normal form.
- Negation restricted to "test types" (string, number, boolean, nil, thread, table, function) — no `~((A) -> B)`.
- Function application semantics simplified (no special case for uninhabited arguments).

`Subtyping` class implements:
- `isSubtype(TypeId sub, TypeId super, scope)` — public entry point.
- `isCovariantWith()`, `isContravariantWith()`, `isInvariantWith()` — variance-specific dispatch.
- `SubtypingResult` carries `isSubtype: bool`, failure `reasoning` path, `assumedConstraints` for free types.
- `seenSetCache` prevents infinite recursion on recursive types.

---

## 12. Runtime: Type Erasure and Bytecode

### 12.1 Analysis vs. Compilation Are Independent

The Luau codebase has six libraries:

| Library | Role |
|---|---|
| `Luau.Ast` | Lexer + parser → AST |
| `Luau.Compiler` | AST → bytecode (does NOT depend on Analysis) |
| `Luau.Analysis` | Type checking + linting (does NOT affect bytecode correctness) |
| `Luau.VM` | Bytecode interpreter |
| `Luau.CodeGen` | Optional JIT → native code |
| `Luau.Common` | Shared headers, bytecode definitions |

`Luau.Compiler` does **not** link against `Luau.Analysis`. The compiler accepts:
1. Source string (or pre-parsed `ParseResult`)
2. `CompileOptions` (optimization level, debug level, vector config)
3. Optional `BytecodeEncoder`

Type annotations are **not** a compiler input for correctness. The compiler can optionally emit type information into bytecode for optimization hints — this is separate from the Analysis module's type inference output.

### 12.2 Bytecode Type Tags (`LuauBytecodeType`)

From `Common/include/Luau/Bytecode.h`. Starting from bytecode version 4 (typeinfo version 1), the bytecode `Proto` (function prototype) can carry type hints for:

- Arguments
- Upvalues
- Local variables
- Some temporaries

`LuauBytecodeType` enum values:

```
LBC_TYPE_NIL, LBC_TYPE_BOOLEAN, LBC_TYPE_NUMBER, LBC_TYPE_STRING,
LBC_TYPE_TABLE, LBC_TYPE_FUNCTION, LBC_TYPE_THREAD, LBC_TYPE_USERDATA,
LBC_TYPE_VECTOR, LBC_TYPE_BUFFER, LBC_TYPE_INTEGER
LBC_TYPE_ANY = 15         -- untyped
LBC_TYPE_OPTIONAL_BIT = bit 7  -- set for nullable types
LBC_TYPE_TAGGED_USERDATA_BASE..END = 64..95  -- named userdata types
LBC_TYPE_INVALID = 256    -- sentinel
```

Three typeinfo versions:
- v1 (bytecode v4): function signature types.
- v2 (bytecode v4): arg + upvalue + local + temp types.
- v3 (bytecode v5): userdata type names + index mapping.

### 12.3 How Type Tags Are Used

These bytecode-embedded tags are **optimization hints only**, not runtime enforcement. The VM and JIT use them to:

- Select fastcall paths for built-in operations (known argument types → skip dispatch overhead).
- Enable native code specialization (type-specific register allocation, shorter instruction sequences).
- Perform constant folding when argument types guarantee no side effects.
- Hoisting and CSE are planned but not yet implemented.

The VM **never** checks annotations for type safety at runtime. An `any`-typed variable at analysis time produces `LBC_TYPE_ANY` tag — the bytecode is identical to untyped Lua. A value's *actual* runtime type is always its dynamic tag (Luau uses a 16-byte tagged-value representation: 8-byte type tag + 8-byte payload, not NaN-boxed).

**Key conclusion**: Type annotations do not alter observable runtime behavior. A program that passes type checking and one that does not execute identically given the same inputs.

### 12.4 Minimal Embedding — No Analysis Needed

Embedding Luau for execution requires only:

```
Luau.Common + Luau.Ast + Luau.Compiler + Luau.VM
```

`Luau.Analysis` is optional and only needed if the host wants type diagnostics (IDE tooling, CI linting). A game engine embedding Luau for scripting can compile and run scripts without ever instantiating a `Frontend`, `ConstraintGenerator`, or `ConstraintSolver`.

The `luau-analyze` CLI tool wraps Analysis independently of the VM.

---

## 13. The `any` Type — Semantics

`any` in Luau is **not** the dynamic type of gradual typing theory (which would require runtime blame tracking). It is an *error-suppressing* type:

- Any subtype check `T <: any` succeeds silently.
- Any subtype check `any <: T` succeeds silently.
- Errors originating from `any`-typed subexpressions are suppressed.

This means `any` propagates through expressions like an infection — one `any` argument can silence an entire expression's type checking. This is the intentional "unsound" design: the system trades soundness for ergonomics and adoption.

`unknown` is the "safe" top type — you can assign anything to `unknown`, but you cannot use it without a narrowing check first. `unknown` does not suppress errors.

---

## 14. Scope and Module System

`Scope` struct (`Analysis/include/Luau/Scope.h`):
- `parent: Scope*` — lexical parent.
- `bindings: map<Symbol, Binding>` — variable → `TypeId` + location + deprecation info.
- `exportedTypeBindings`, `privateTypeBindings` — `TypeFun` definitions (generic aliases).
- `importedModules`, `importedTypeBindings` — from `require()` calls.
- `lvalueTypes` — unrefined type per definition.
- `rvalueRefinements` — control-flow-narrowed types per expression.
- `refinements: RefinementMap` — type guard predicates.

Module boundaries: `export type` makes a `TypeFun` visible to requirers. The `Frontend` tracks `require()` dependencies, detects cycles, and provides `importedTypeBindings` for cross-module type access.

---

## 15. Design Tensions: Soundness vs. Completeness vs. Ergonomics

The Luau team's published papers frame three competing goals:

1. **Soundness**: no false negatives — if the checker says safe, it is safe.  
   Luau *explicitly gives this up* in nonstrict mode. The paper "Towards an Unsound But Complete Type System" (2024) makes this a design choice, not a bug.

2. **Completeness**: no false positives — checker accepts all valid programs.  
   Nonstrict mode optimizes for this. Only "definite runtime errors" are reported.

3. **Ergonomics**: minimal annotation burden, good autocomplete, fast feedback.  
   The new solver's unified inference pass serves both modes with better autocomplete.

Gradual typing theory uses *consistent subtyping* (`any ≲ T ≲ any` everywhere). Luau instead uses *error suppression*: `any` does not participate in blame, it simply turns off checking. This is a deliberate departure from gradual typing orthodoxy.

---

## 16. Should a Scala Implementation Include a Typechecking Phase?

### 16.1 The Case for Deferring (Phase 1: Parse + Compile Only)

**Arguments:**

- **Runtime independence**: the Luau VM does not need type information to execute code correctly. A Scala implementation of the runtime (VM, bytecode compiler) needs zero type checker code. The entire `Analysis` module is optional.

- **Complexity**: the new constraint solver is ~5,000+ lines of C++ across `ConstraintGenerator.cpp`, `ConstraintSolver.cpp`, `TypeChecker2.cpp`, `Normalizer.cpp`, `Subtyping.cpp`. Correct implementation requires understanding bidirectional inference, worklist algorithms, negation types, type functions with their own sandboxed VM, and semantic subtyping normalization.

- **Moving target**: the new solver exited beta in 2024 and is still actively evolving. RFCs for type functions and local type inference are recent. Implementing a stable moving target is risky.

- **Scope**: if the goal is Luau *execution* (game scripting, embedding), type checking is out of scope. If the goal is IDE tooling, defer to Luau's own `luau-analyze` binary or LSP via `luau-lsp`.

- **Interop path**: Scala impl can shell out to `luau-analyze` for diagnostics, or bind via JNI/JNA to the official C++ analysis library. No need to reimplement.

- **Unsound guarantees**: even if you implemented type checking, Luau's intentional unsoundness means you cannot use type info for JVM bytecode specialization without additional runtime guards — so the optimization payoff from typing is limited.

**Recommended defer strategy**: implement `--!nocheck` semantics (parse, compile, execute). Add a `--typecheck` flag that shells out to `luau-analyze` or invokes analysis via native binding.

### 16.2 The Case for Implementing Type Checking (Phase 1 Inclusion)

**Arguments:**

- **IDE integration**: if the Scala impl targets developer tooling (REPL, editor plugin, build tool), having the type checker in-process enables fast incremental checking without spawning processes.

- **Roblox parity**: Roblox's Studio type checks all scripts. Code targeting Roblox expects `--!strict` semantics. A Scala impl without type checking would be surprising to Roblox developers.

- **Correctness of nonstrict**: even for execution, some Luau semantics depend on the type-checking pass setting module-level attributes (e.g., whether a script runs as `--!strict`). The directive affects error *reporting* only, not execution — but knowing the mode matters for tooling.

- **`typeof` at analysis time**: `typeof(expr)` in type annotations is resolved statically by the type checker. If you emit code that relies on the analyzer having run (e.g., to resolve exported types for autocomplete), you need it.

- **Learning value**: implementing the constraint solver is a rich exercise in type theory applicable to Scala's own type system work.

**Recommended include strategy**: implement a minimal read-only type checker covering: mode directive parsing, primitive type annotation syntax, basic subtype checking, `any` propagation, union/optional types. Defer: generics, type functions, user-defined type functions, semantic normalization, full refinement. This gives 80% of practical value at 20% of complexity.

### 16.3 Recommendation

**Defer type checking for a runtime-focused Scala implementation.**

The runtime (bytecode compiler + VM) is the critical path. Type checking is orthogonal and can be layered in later or delegated to the official C++ implementation. The constraint solver is architecturally complex, actively evolving, and not required for correct Luau execution. Build the Analysis layer as a separate optional module with a clear interface boundary — mirroring how the official Luau codebase separates `Luau.Analysis` from `Luau.VM`.

If IDE tooling is the primary goal, consider binding to `luau-lsp` (JohnnyMorganz/luau-lsp) via process or LSP protocol instead of reimplementing Analysis.

---

## 17. Summary Table

| Aspect | Detail |
|---|---|
| Type system flavor | Gradual, structural, intentionally unsound (nonstrict) |
| Modes | `--!nocheck`, `--!nonstrict` (default), `--!strict` |
| Type erasure | Complete — annotations absent from VM execution semantics |
| Bytecode type hints | Optional optimization metadata only; not enforcement |
| Inference algorithm | Local type inference (Pierce); constraint-based (new solver) |
| Old solver | Recursive descent + eager unification (`TypeChecker`) |
| New solver | Constraint generation + worklist solving (`ConstraintGenerator` + `ConstraintSolver`) |
| Subtyping | Semantic (set-theoretic interpretation), with pragmatic normalization |
| `any` semantics | Error-suppressing, not blame-tracking gradual type |
| Type functions | Type-level computations; reduce in constraint solver; user-definable |
| Negation types | Internal to narrowing; not surface annotation syntax |
| Runtime need for typechecking | None — `Luau.Analysis` not linked by `Luau.VM` or `Luau.Compiler` |
| Scala impl recommendation | Defer type checking; focus runtime on parse + compile + execute |
