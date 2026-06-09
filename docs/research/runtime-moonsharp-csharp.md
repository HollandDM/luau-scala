# MoonSharp: Lua 5.2 on the CLR — Technical Reference

**Repository:** https://github.com/moonsharp-devs/moonsharp  
**Docs:** https://www.moonsharp.org  
**License:** 3-clause BSD  
**Last release:** v2.0.0.0 (Oct 2016); Unity UPM branch `upm/v3.0` continues  
**Targets:** .NET 3.5 / 4.x, Mono, Xamarin, Unity3D, IL2CPP (AOT)  
**Compatibility:** 99% Lua 5.2 (only missing: weak tables)

Clean-room design. No original Lua C source reused (minor stdlib exception). Written entirely in C# to run inside a managed, GC'd VM. Every design decision described below is directly shaped by that constraint.

---

## 1. Repository Layout

```
src/MoonSharp.Interpreter/
  DataTypes/          -- DynValue, Table, Closure, Coroutine, UserData …
  Execution/
    VM/
      Processor/      -- Processor.cs, Processor_InstructionLoop.cs,
                      -- Processor_Coroutines.cs, Processor_Scope.cs …
      ByteCode.cs     -- flat instruction list
      Instruction.cs  -- single instruction struct
      OpCode.cs       -- opcode enum
      CallStackItem.cs
    Scopes/           -- ClosureContext.cs, RuntimeScopeFrame.cs …
  Interop/            -- descriptor system, IL codegen, converters
  CodeAnalysis/       -- lexer, parser
  Tree/               -- AST node types
  CoreLib/            -- stdlib modules
  Script.cs           -- public entry point
```

Key source files referenced throughout: all paths relative to `src/MoonSharp.Interpreter/`.

---

## 2. Compilation Pipeline

Three-phase: **Lex → Parse → Codegen**. No separate IR or optimisation pass.

### 2.1 Lexer

`CodeAnalysis/Lexer.cs`. Breaks source into tokens: numeric literals (int, float, hex), long strings, comments, identifiers, keywords, operators. Standard single-pass design.

### 2.2 Parser → AST

Recursive-descent parser in `CodeAnalysis/`. Entry points:

| Method | AST root |
|---|---|
| `LoadChunk()` | `ChunkStatement` |
| `LoadFunction()` | `FunctionDefinitionExpression` |
| `LoadDynamicExpr()` | `DynamicExprExpression` |

AST node types live in `Tree/`. Each node implements `Compile(ByteCode bc)`.

### 2.3 Code Generation

Each AST node emits directly into `ByteCode`. No intermediate representation. Characteristic patterns:

- Variable access → `StoreLcl` / `StoreUpv` / `IndexSet`  
- Function call → `Call` + `Ret`  
- Arithmetic → `Add`, `Sub`, `Mul`, `Div`, `Mod`, `Power`  
- Control flow → `Jump`, `Jf`, `JNil`, `JFor`, `JtOrPop`, `JfOrPop`  
- Table → `NewTable`, `TblInitN`, `TblInitI`

`ScriptLoadingContext` carries scope state (variable resolution, closure building) through codegen.

### 2.4 ByteCode storage

`Execution/VM/ByteCode.cs`:
```csharp
public List<Instruction> Code = new List<Instruction>();
```
Flat list — no function-prototype nesting as in `luac`. Function entries are just offsets into this single list. Constants embed directly in `Instruction.Value` as `DynValue` objects. Source positions tracked per-instruction via `SourceRef` for debugger use.

Bytecode can be serialized and deserialized (dump/load), handled by `Processor_BinaryDump.cs`.

---

## 3. Instruction Set

`Execution/VM/OpCode.cs` — ~40 opcodes.

### 3.1 Instruction format

`Execution/VM/Instruction.cs`:

```csharp
internal class Instruction {
    public OpCode    OpCode;
    public SymbolRef Symbol;        // single symbol reference
    public SymbolRef[] SymbolList;  // upvalue list for Closure op
    public string    Name;
    public DynValue  Value;         // literal constant embedded inline
    public int       NumVal;        // integer operand (jump target, arg count …)
    public int       NumVal2;       // secondary integer operand
    public SourceRef SourceCodeRef; // debug info
}
```

Variable-length encoding: `InstructionFieldUsage` flags per opcode determine which fields serialize. `NumValAsCodeAddress` fields get base-address–adjusted during dump/load. No fixed-width encoding — this is CLR object references, not a register-encoded 32-bit word as in PUC Lua.

### 3.2 Selected opcodes

| Category | Opcodes |
|---|---|
| Stack | `Pop`, `Copy`, `Swap`, `Literal`, `Closure`, `NewTable` |
| Variables | `StoreLcl`, `StoreUpv`, `IndexSet` (write) + corresponding loads |
| Calls | `Call`, `ThisCall` (colon syntax), `Ret`, `BeginFn`, `Args`, `Meta` |
| Jumps | `Jump`, `Jf`, `JNil`, `JFor`, `JtOrPop`, `JfOrPop` |
| Arithmetic | `Add`, `Sub`, `Mul`, `Div`, `Mod`, `Power`, `Neg` |
| Logical | `Not`, `CNot`, `Less`, `LessEq`, `Eq` |
| String | `Concat`, `Len` |
| Type | `MkTuple`, `Scalar`, `ToNum`, `ToBool`, `ExpTuple`, `Incr` |
| Iteration | `IterPrep`, `IterUpd` |

No registers. Pure operand stack. All intermediate results pass through `m_ValueStack`.

---

## 4. DynValue: the Universal Value Type

`DataTypes/DynValue.cs` — `public sealed class DynValue`.

### 4.1 Fields

```csharp
private static int s_RefIDCounter;   // global counter
private int    m_RefID;              // unique identity per instance
private int    m_HashCode;           // cached, -1 = not computed
private bool   m_ReadOnly;           // immutability lock
private double m_Number;             // stores: numbers AND booleans (0/1)
private object m_Object;             // stores: string, Table, Closure,
                                     //         Coroutine, UserData, DynValue[],
                                     //         TailCallData, YieldRequest
private DataType m_Type;             // discriminator
```

Hybrid: primitive numeric/bool lives in `m_Number` (no heap allocation); all reference types go into `m_Object` (single object field avoids storing a pointer per type).

### 4.2 DataType enum

```
Nil, Void
Boolean, Number, String
Table, Function, ClrFunction
Thread          (coroutines)
UserData
Tuple           (multi-return, DynValue[])
TailCallRequest (trampoline signal)
YieldRequest    (coroutine yield signal)
```

`Void` is distinct from `Nil` — used internally for empty returns.

### 4.3 Static singletons

```csharp
public static readonly DynValue Nil   = new DynValue(…);
public static readonly DynValue Void  = new DynValue(…);
public static readonly DynValue True  = new DynValue(…);
public static readonly DynValue False = new DynValue(…);
```

Factory methods: `NewNumber()`, `NewString()`, `NewTable()`, `NewClosure()`, `NewCallback()`, `NewCoroutine()`, `NewUserData()`, `NewTuple()`, `NewTailCallReq()`, `NewYieldReq()`.

Bidirectional CLR ↔ DynValue: `DynValue.FromObject(script, obj)` and `DynValue.ToObject()` / `ToObject<T>()`.

### 4.4 Reference semantics — design choice driven by CLR

DynValue is a **class**, not a struct. This is intentional. The official justification: value types do not support the full range of CLR optimisations. Practically, it means:

- Every DynValue sits on the managed heap.
- The two stacks (`FastStack<DynValue>`) hold references.
- Passing DynValue across calls does not copy the payload.
- GC sees live values via normal reference tracking.

**Implication for managed-runtime authors:** the "obvious" alternative — a struct with a union via `[FieldOffset]` — was apparently considered and rejected. Struct copying would be cheap for the number payload but expensive for tuples and strings (which would still box). Using a class gives a single indirection cost but enables the `m_ReadOnly` protection pattern where `AsReadOnly()` shares the same object.

**GC pressure consequence:** The community (Unity users in particular) found this costly. The `moonsharp-unityenhanced` fork measured DynValue at 112 bytes on heap and reduced it to 40 bytes by stripping the `m_RefID` debug counter. They also reduced `FastStack` default capacity from 131,072 to 4,096, cutting per-script memory by >99%. A pooling pattern was proposed in [discussion #335](https://github.com/moonsharp-devs/moonsharp/discussions/335): replace `new DynValue()` with a free-list pool. The fork noted they "might make DynValue a value type in a future version."

### 4.5 Mutability and identity

`Assign(DynValue other)` mutates in-place — used by the upvalue write path. `AsReadOnly()` sets `m_ReadOnly = true` and returns `this` (no copy). The `m_RefID` counter gives every value a unique lifetime identity for debugging.

---

## 5. Table Implementation

`DataTypes/Table.cs`.

### 5.1 Storage

```csharp
private LinkedList<TablePair>              m_Values;     // all pairs, insertion order
private LinkedListIndex<string, TablePair> m_StringMap;  // string key O(1)
private LinkedListIndex<int, TablePair>    m_ArrayMap;   // integer key O(1)
private LinkedListIndex<DynValue,TablePair>m_ValueMap;   // arbitrary key O(1)
private Table   m_MetaTable;
private int     m_CachedLength;           // -1 = invalidated
private bool    m_ContainsNilEntries;
```

`LinkedListIndex<K,V>` is a custom wrapper around `Dictionary<K,LinkedListNode<V>>`. The three indices all point into nodes of the same `m_Values` `LinkedList`. This gives O(1) lookup by any key type while preserving insertion-order iteration (Lua's `pairs()` semantic).

### 5.2 Array vs hash split

Mirrors Lua's reference implementation conceptually. Integer keys 1..n land in `m_ArrayMap`; string keys in `m_StringMap`; everything else in `m_ValueMap`. No physical memory split (no raw C array) — all three are dictionary-backed. The "array part" optimisation from PUC Lua (a raw C `TValue` array for dense integer indices) is absent; MoonSharp pays dictionary overhead even for `t[1]`, `t[2]`, … This is the managed-GC trade-off: no manual memory layout.

### 5.3 Length operator (`#`)

```csharp
if (m_CachedLength < 0) {
    m_CachedLength = 0;
    for (int i = 1;
         m_ArrayMap.ContainsKey(i) && !m_ArrayMap.Find(i).Value.Value.IsNil();
         i++)
        m_CachedLength = i;
}
```

Sequential scan from 1. Cached until an integer-keyed write invalidates it. Behaviour matches the "border" definition in Lua 5.2 for tables with no holes.

### 5.4 Metatable

Simple `Table MetaTable` property. No `__index` chain traversal inside `Table` — that lives in the Processor's `ExecIndex` / `ExecGetMetamethod` helpers.

### 5.5 Iteration

`NextKey(DynValue key)` walks the linked list for Lua-style `next()`. `Pairs`, `Keys`, `Values` enumerate `m_Values` directly.

---

## 6. Bytecode VM: the Processor

`Execution/VM/Processor/Processor.cs` and siblings.

### 6.1 Core fields

```csharp
ByteCode              m_RootChunk;           // the program
FastStack<DynValue>   m_ValueStack;          // operand stack, cap 131072
FastStack<CallStackItem> m_ExecutionStack;   // call frame stack, cap 131072
List<Processor>       m_CoroutinesStack;     // active coroutine chain
Table                 m_GlobalTable;
Script                m_Script;
Processor             m_Parent;             // null for main thread
CoroutineState        m_State;
bool                  m_CanYield;
int                   m_SavedInstructionPtr;
int                   m_OwningThreadID;      // CLR thread safety guard
int                   m_ExecutionNesting;    // re-entrancy depth
DebugContext          m_Debug;
```

`FastStack<T>` is a preallocated fixed-capacity array (`T[] m_Storage`) with a head index. No `List<T>` growth. Pop/push is `m_Storage[m_HeadIdx++]` and `m_Storage[--m_HeadIdx]`. After pop, the slot is cleared to `default(T)` so GC can collect the old `DynValue`. Minimal allocation, no boxing.

### 6.2 CallStackItem

`Execution/VM/CallStackItem.cs`:

```
int              ReturnAddress        // instruction ptr to return to
int              BasePointer          // m_ValueStack base for this frame
DynValue[]       LocalScope           // local variable array (heap-allocated per call)
ClosureContext   ClosureScope         // upvalue array for this function
CallbackFunction ClrFunction          // non-null for CLR frames
CallbackFunction Continuation         // trampoline continuation
CallbackFunction ErrorHandler
DynValue         ErrorHandlerBeforeUnwind
int              Debug_EntryPoint
SymbolRef[]      Debug_Symbols
CallStackItemFlags Flags
```

`LocalScope` is a `DynValue[]` allocated at call entry. Locals are by index into this array (no named lookup at runtime). `ClosureScope` is the `ClosureContext` captured at closure creation — a `List<DynValue>` (see §7).

### 6.3 Main dispatch loop

`Processor_InstructionLoop.cs` — `Processing_Loop(int instructionPtr)`:

```
while (true) {
    Instruction i = m_RootChunk.Code[instructionPtr];
    // optional debugger hook
    // auto-yield counter decrement
    instructionPtr++;
    switch (i.OpCode) {
        case OpCode.Add:
            instructionPtr = ExecAdd(i, instructionPtr);
            if (instructionPtr == YIELD_SPECIAL_TRAP)
                goto yield_to_calling_coroutine;
            break;
        // … ~40 cases
    }
}
yield_to_calling_coroutine:
    // pop yielded value, save instruction ptr, return to caller
```

`YIELD_SPECIAL_TRAP = -99`. Every opcode handler returns the next instruction pointer. A return of `-99` signals "a yield happened" and breaks out of the loop via `goto`. Standard `goto` within a single method — idiomatic in CLR interpreters for performance (avoids virtual dispatch and exception-based control flow).

The class is `sealed` and uses procedural helpers (`ExecAdd`, `ExecCall`, etc.) to avoid virtual dispatch overhead. Comments in source: *"This part is practically written procedural style — it looks more like C than C#. This is intentional so to avoid this-calls and virtual-calls as much as possible."*

### 6.4 Call / return

`Internal_ExecCall(int argsCount, int instructionPtr, …)` dispatches on whether the callee is a `ClrFunction` (immediate reflection/delegate call, return to current frame) or a Lua `Closure` (push `CallStackItem`, jump to `EntryPointByteCodeLocation`). `Ret` instruction: `ExecRet` pops the frame via `PopToBasePointer()`, restores `instructionPtr = frame.ReturnAddress`.

### 6.5 Tail call optimisation

When execution stack depth exceeds a threshold AND the call site is immediately followed by `Ret 1` with no error handlers pending, `PerformTCO()` reuses the current frame instead of allocating a new one. This prevents stack overflow on tail-recursive Lua. The check is `m_ExecutionStack.Count > threshold` for both stacks.

### 6.6 TailCallRequest trampoline

`DynValue.NewTailCallReq(function, args, continuation)` packages a deferred call. When any opcode handler returns this sentinel type, `Internal_CheckForTailRequests()` catches it and re-enters the call loop — effectively a trampoline. CLR callbacks that cannot safely be on the call stack at yield time return `TailCallReq` to ensure they are off the stack before execution resumes. `TailCallData` fields: `Function` (DynValue), `Args` (DynValue[]), `Continuation` (CallbackFunction), `ErrorHandler`, `ErrorHandlerBeforeUnwind`.

---

## 7. Closures and Upvalues

`DataTypes/Closure.cs`, `Execution/Scopes/ClosureContext.cs`.

### 7.1 Closure structure

```csharp
public class Closure : RefIdObject, IScriptPrivateResource {
    public int EntryPointByteCodeLocation;
    public Script OwnerScript;
    public ClosureContext ClosureContext;
}
```

### 7.2 ClosureContext

`ClosureContext : List<DynValue>` with a parallel `string[] Symbols` for debug names.

Constructor takes `SymbolRef[]` and `IEnumerable<DynValue>` — the upvalue values captured at closure creation time. Upvalues are plain `DynValue` references stored in the list. Index into `ClosureContext` is the upvalue index from bytecode.

**No open/closed upvalue distinction in the C++ Lua sense.** PUC Lua keeps upvalues "open" (pointing into the stack) until the enclosing scope exits, then "closes" them (copies value out). MoonSharp avoids this entirely: `LocalScope` is a heap-allocated `DynValue[]` per call frame. Inner functions capture the `DynValue` reference itself. Because `DynValue` is a reference type (class), both the outer local slot and the inner closure slot point to the same `DynValue` object. Mutation uses `DynValue.Assign(value)` — in-place update of the shared object. The GC keeps the object alive as long as any closure holds a reference.

This is a direct consequence of running on a managed GC: instead of explicit open/close machinery, MoonSharp leverages the CLR heap for upvalue lifetime. Simpler implementation, GC-managed lifetime, slight extra allocation per call frame.

### 7.3 UpvaluesType classification

```csharp
public enum UpvaluesType { None, Environment, Closure }
```

`None` — no captured variables. `Environment` — only `_ENV` captured (most top-level functions). `Closure` — real closures with ≥1 non-env upvalues. Checked at bytecode generation; `Environment` closures get a lighter descriptor path.

### 7.4 Variable read/write in Processor

`Processor_Scope.cs`:

```csharp
// read local
frame.LocalScope[symref.i_Index]
// read upvalue
frame.ClosureScope[symref.i_Index]
// write local
frame.LocalScope[symref.i_Index].Assign(value)
// write upvalue
frame.ClosureScope[symref.i_Index].Assign(value)
```

`SymbolRef.i_Index` is the compile-time slot index. No name lookup at runtime.

---

## 8. Coroutine Model

`DataTypes/Coroutine.cs`, `Execution/VM/Processor/Processor_Coroutines.cs`.

### 8.1 The fundamental CLR constraint

CLR cannot switch native call stacks the way POSIX `ucontext` or Win32 fibers can. Stack-switching coroutines require OS-level primitive support or an explicit VM that owns its own stack representation. MoonSharp already owns the VM stack (the `FastStack` arrays), so it implements **stackless coroutines** — suspension by saving instruction pointer and returning from the dispatch loop.

No `System.Threading.Thread`, no `ManualResetEvent`, no fiber. All coroutine state is in the `Processor` object.

### 8.2 Each coroutine = one Processor

`Coroutine_Create` in `Processor_Coroutines.cs`:

```csharp
var p = new Processor(this);          // child processor, inherits script/chunk
p.m_ValueStack.Push(closureDynValue); // seed value stack with the function
return new Coroutine(p);
```

Every coroutine gets an independent `Processor` with its own `m_ValueStack` and `m_ExecutionStack`. Main thread is also a `Processor`. `m_Parent` links child to parent.

### 8.3 Resume

`Processor_Coroutines.cs` — `Coroutine_Resume(DynValue[] args)`:

```
switch (m_State) {
case NotStarted:
    PushClrToScriptStackFrame(ReturnType.TailCall, args, …);
    // sets up initial call frame with args
    m_State = Running;
    return Processing_Loop(entryPoint);
case Suspended:
    // push args onto value stack
    m_State = Running;
    return Processing_Loop(m_SavedInstructionPtr);
case ForceSuspended:
    m_State = Running;
    return Processing_Loop(m_SavedInstructionPtr);
}
```

`Processing_Loop` runs until it returns normally (coroutine `return`) or hits `YIELD_SPECIAL_TRAP` (coroutine `yield`).

### 8.4 Yield

Inside the dispatch loop, any opcode that processes a `YieldRequest` value sets `m_SavedInstructionPtr = instructionPtr` and returns `YIELD_SPECIAL_TRAP`. The `goto yield_to_calling_coroutine` label pops the `YieldRequest.ReturnValues` and exits `Processing_Loop` with them. State transitions to `Suspended`. Control returns to the caller of `Coroutine.Resume()`.

```
// Caller calls Resume(args)
//   → Processing_Loop starts
//   → Lua runs … coroutine.yield(x, y) encountered
//   → YieldRequest created, YIELD_SPECIAL_TRAP returned
//   → Processing_Loop returns {x, y} to Resume caller
//   → m_State = Suspended, m_SavedInstructionPtr saved
//
// Later: caller calls Resume(a, b)
//   → Processing_Loop(m_SavedInstructionPtr) restarts
//   → {a, b} injected into Lua as yield() return value
```

### 8.5 Preemptive coroutines (AutoYieldCounter)

The instruction loop decrements a counter each cycle:
```csharp
if (--AutoYieldCounter == 0)
    return YIELD_SPECIAL_TRAP; // with YieldRequest.Forced = true
```

Caller checks `result.Type == DataType.YieldRequest && result.YieldRequest.Forced`. On `ForceSuspended` resume, args are not injected — execution resumes as if nothing happened. This lets a host limit CPU time without adding `coroutine.yield` calls to Lua scripts. Caveat: `ForceSuspended` cannot interrupt CLR calls (re-entrancy sections set `m_CanYield = false`).

### 8.6 Cannot yield across CLR boundaries

MoonSharp enforces: you cannot `coroutine.yield` inside a C# callback invoked from Lua. The `m_CanYield` flag is `false` during CLR-frame execution. Workaround: CLR callback returns `DynValue.NewTailCallReq(fn, args)` — trampoline ensures the CLR frame is gone before Lua resumes and yield becomes possible.

### 8.7 Coroutine as C# IEnumerable

`Coroutine.AsTypedEnumerable<T>()` wraps resume in a C# iterator: `foreach` loop calls `Resume()` each iteration, consuming yielded values. `AsUnityCoroutine()` yields Unity `WaitForEndOfFrame`-compatible objects.

### 8.8 Processor recycling

`Coroutine_Recycle` clears both stacks and resets state for a dead coroutine. Avoids allocating new `Processor` per coroutine reuse.

### 8.9 Coroutine state machine

```
NotStarted → Running → Suspended (explicit yield)
                     → ForceSuspended (auto-yield)
                     → Dead (return or exception)
```

### 8.10 Comparison with how JVM languages face the same problem

Scala on JVM faces the identical impossibility: JVM has no fiber/continuation support in the base spec (pre-Project Loom). Solutions observed in practice:

| Approach | Example | Analogy to MoonSharp |
|---|---|---|
| Stackless VM coroutines | MoonSharp (this doc) | Exact match: own VM owns stacks |
| CPS transform at compile time | Scala async/await (`scala-async` macro) | Transform source so yield = return |
| Thread-per-coroutine + blocking | Early Kotlin coroutines prototype | Real OS threads, expensive |
| Project Loom virtual threads | Java 21+ | OS-independent stack switching |
| State-machine codegen | Kotlin coroutines (current) | `suspend` compiles to state machine class |

MoonSharp's approach (stackless VM, no OS threads) is directly analogous to what Kotlin coroutines do: transform coroutine body into a state machine where each suspension point becomes an explicit state enum value + saved-locals struct. The difference is MoonSharp does this at the VM level (instruction pointer + saved stacks) rather than transforming user source. A Scala/JVM Lua interpreter would need either the same VM-owns-stack approach or CPS transform, since JVM bytecode has no yield primitive.

---

## 9. CLR Interop System

`Interop/` — descriptor-based, reflection + optional IL codegen.

### 9.1 UserData wrapper

`DataTypes/UserData.cs`:

```csharp
public class UserData {
    public object             Object;     // null for static access
    public IUserDataDescriptor Descriptor;
}
```

Stored as `DynValue` with `m_Type = DataType.UserData`, `m_Object = userDataInstance`.

All Lua `__index`, `__newindex`, `__call`, metamethod operations on userdata route through `Descriptor.Index()`, `Descriptor.SetIndex()`, `Descriptor.MetaIndex()`.

### 9.2 Type registration

`Interop/UserDataRegistries/TypeDescriptorRegistry.cs` maintains:

```csharp
Dictionary<Type, IUserDataDescriptor> s_TypeRegistry;
Dictionary<Type, IUserDataDescriptor> s_TypeRegistryHistory;
```

`RegisterType_Impl` selection hierarchy:
1. `[MoonSharpUserData]` attribute → use its specified `AccessMode`
2. Implements `IUserDataType` → `AutoDescribingUserDataDescriptor`
3. Generic type → `StandardGenericsUserDataDescriptor`
4. Enum → `StandardEnumUserDataDescriptor`
5. Default → `StandardUserDataDescriptor`

`BackgroundOptimized` mode queues descriptor compilation on the thread pool.

Default policy: **explicit registration only** (`InteropRegistrationPolicy.Default`). `InteropRegistrationPolicy.Automatic` exists but discouraged in sandboxed contexts.

### 9.3 Descriptor types

`StandardUserDataDescriptor` — general. Internally creates sub-descriptors per member:

- `MethodMemberDescriptor` — single method
- `OverloadedMethodMemberDescriptor` — overload set
- `PropertyMemberDescriptor` — property get/set
- `FieldMemberDescriptor` — field
- `EventMemberDescriptor` — event

### 9.4 Method dispatch and IL compilation

`Interop/StandardDescriptors/ReflectionMemberDescriptors/MethodMemberDescriptor.cs` — two dispatch paths:

**Reflection mode** (`InteropAccessMode.Reflection`):
```csharp
MethodInfo.Invoke(obj, pars)
```
Per-call reflection overhead. Forced on AOT platforms (Unity IL2CPP).

**Optimised mode** (`LazyOptimized` or `Preoptimized`):
Builds `System.Linq.Expressions` tree at first call:
```csharp
var ep    = Expression.Parameter(typeof(object[]));
var inst  = Expression.Parameter(typeof(object));
var call  = Expression.Call(
    Expression.Convert(inst, methodInfo.DeclaringType),
    methodInfo,
    parameters.Select((p, i) =>
        Expression.Convert(
            Expression.ArrayIndex(ep, Expression.Constant(i)),
            p.OriginalType))
);
var lambda = Expression.Lambda<Func<object,object[],object>>(call, inst, ep);
m_OptimizedFunc = lambda.Compile();
```

`Interlocked.Exchange` for thread-safe lazy store. Post-JIT, dispatch is a single delegate call — no reflection overhead. AOT platforms cannot use `.Compile()`, forced back to reflection.

### 9.5 Argument conversion

`FunctionMemberDescriptorBase.BuildArgumentList` converts `CallbackArguments` (a `DynValue[]` wrapper) to `object[]` for CLR invocation. Handles:

- `ref`/`out` parameters — output collected post-invoke, returned as Lua multi-return tuple
- Overload scoring — numeric type matching with heuristic score (Lua has only `double`; C# distinguishes `int`/`float`/`double`)
- `params` arrays — variadic CLR methods

`BuildReturnValue` wraps CLR return value back to `DynValue` via `DynValue.FromObject`.

### 9.6 Name resolution

`Script.GlobalOptions.FuzzySymbolMatching` enables:
- `CalcHypotenuse` accessible as `calcHypotenuse` (camelCase)
- `CalcHypotenuse` accessible as `calc_hypotenuse` (snake_case)

### 9.7 Visibility control

- `[MoonSharpVisible(false)]` — hide any member
- `[MoonSharpHidden]` — alias for above
- `[MoonSharpHide("MemberName")]` on type — remove inherited member
- `[MoonSharpUserDataMetamethod("__pow")]` — custom metamethod

### 9.8 Static type access

`UserData.CreateStatic<T>()` — `Object = null`, `Descriptor` reflects static members only. Exposed as a table-like global.

### 9.9 Proxy / facade pattern

Recommended security practice: register a dedicated adapter class, not raw application types. This isolates the API surface and prevents scripts from reaching internal state.

---

## 10. GC and Managed-Runtime Design Consequences

Running on a GC'd managed VM shapes the design throughout:

| Decision | Reason | Trade-off |
|---|---|---|
| `DynValue` is a `class` | CLR optimisations on reference types; shared mutation via `Assign()` | Every value on heap; GC pressure |
| `FastStack` pre-allocated fixed array | Avoid incremental allocation during execution | 131,072 × sizeof(ref) per stack, even for tiny scripts |
| No open/closed upvalue split | CLR heap keeps `DynValue` alive automatically | Extra `DynValue[]` alloc per call frame |
| Table uses `LinkedList` + `Dictionary` | CLR has no `realloc`; layout of arrays not manual | No compact C array for integer indices — all int lookups go through hash |
| Coroutines = one Processor per coroutine | CLR cannot switch stacks; must own all stack state | Each coroutine allocates a full `Processor` with two 131K stacks |
| IL codegen for fast interop | Reflection too slow for hot paths on CLR | Cannot use `.Compile()` on AOT (IL2CPP); falls back to reflection |
| `AsyncExtensions` wraps sync ops in `Task.Factory.StartNew` | No native async VM; reuse CLR thread pool | No true cooperative async — OS thread blocked per async Lua call |
| No weak tables | `WeakReference<T>` / `ConditionalWeakTable<K,V>` exists but interaction with GC generation model and Lua semantics is complex | Only missing Lua 5.2 feature |
| Bytecode dump/load | Binary serialization of `Instruction` objects; avoids re-parse | Custom format, not luac-compatible |
| `m_OwningThreadID` guard | Processor is not thread-safe; CLR apps may call from any thread | Hard error if re-entered from wrong thread; async wrappers offload to thread pool |

### GC pressure in practice

Community findings (Unity users, `AvionicsSystems` issue tracker, discussion #335):

- DynValue at 112 bytes original; 40 bytes after stripping debug fields
- `FastStack` 131K default wastes >1 MB per coroutine even for small scripts
- `CallStackItem`, `SourceRef` also frequent allocators
- Object pooling for DynValue showed dramatic improvement; not yet upstream
- The `moonsharp-unityenhanced` fork applied: reduced stack cap, removed `m_RefID`, added `[MethodImpl(AggressiveInlining)]` to passthrough methods, rewrote `require` in C#

---

## 11. Sandboxing

`CoreModules` enum passed to `new Script(CoreModules.X | CoreModules.Y)`. Modules:

```
Basic, TableIterators, Metatables, String, Math, Load,
Coroutine, REPL, ErrorHandling, OS_System, OS_Time,
IO, File, Debug, Dynamic
```

Presets: `Preset_HardSandbox`, `Preset_SoftSandbox`, `Preset_Default`.

Sandbox principle: disable `io`, `os`, `file` for untrusted. Never use `InteropRegistrationPolicy.Automatic` in sandboxed contexts — any CLR type reachable from script becomes an attack surface.

---

## 12. Notable Lua 5.2 Deviations

| Behaviour | Note |
|---|---|
| Strings are Unicode (`System.String`) | Binary data in strings may be corrupted |
| Weak tables absent | Only missing feature |
| GC is .NET GC | `collectgarbage()` is a no-op stub |
| `yield` allowed in more places | e.g. `__tostring` metamethod can yield; reference Lua cannot |
| `\u{xxx}` string escapes | Extension |
| Multi-index `t[1,2,i]` | Extension |
| Lambda `|x| x+1` | Metalua-style extension |
| `loadsafe`, `loadfilesafe` | Sandbox-friendly load variants |
| No binary compatibility with `luac` | Source-level only |

---

## Primary Sources

- [GitHub: moonsharp-devs/moonsharp](https://github.com/moonsharp-devs/moonsharp)
- [DynValue.cs source](https://github.com/moonsharp-devs/moonsharp/blob/master/src/MoonSharp.Interpreter/DataTypes/DynValue.cs)
- [Coroutine.cs source](https://github.com/moonsharp-devs/moonsharp/blob/master/src/MoonSharp.Interpreter/DataTypes/Coroutine.cs)
- [Table.cs source](https://github.com/moonsharp-devs/moonsharp/blob/master/src/MoonSharp.Interpreter/DataTypes/Table.cs)
- [Closure.cs source](https://github.com/moonsharp-devs/moonsharp/blob/master/src/MoonSharp.Interpreter/DataTypes/Closure.cs)
- [OpCode.cs source](https://github.com/moonsharp-devs/moonsharp/blob/master/src/MoonSharp.Interpreter/Execution/VM/OpCode.cs)
- [Instruction.cs source](https://github.com/moonsharp-devs/moonsharp/blob/master/src/MoonSharp.Interpreter/Execution/VM/Instruction.cs)
- [CallStackItem.cs source](https://github.com/moonsharp-devs/moonsharp/blob/master/src/MoonSharp.Interpreter/Execution/VM/CallStackItem.cs)
- [Coroutines documentation](https://www.moonsharp.org/coroutines.html)
- [Objects/UserData documentation](https://www.moonsharp.org/objects.html)
- [Lua/MoonSharp differences](https://www.moonsharp.org/moonluadifferences.html)
- [moonsharp-unityenhanced fork](https://github.com/SmilingCatEntertainment/moonsharp-unityenhanced)
- [DynValue pooling discussion #335](https://github.com/moonsharp-devs/moonsharp/discussions/335)
- [DeepWiki MoonSharp architecture](https://deepwiki.com/moonsharp-devs/moonsharp/4-architecture)
- [DeepWiki VM design](https://deepwiki.com/moonsharp-devs/moonsharp/4.1-virtual-machine)
- [DeepWiki coroutines](https://deepwiki.com/moonsharp-devs/moonsharp/4.4-coroutines-and-concurrency)
- [DeepWiki CLR interop](https://deepwiki.com/moonsharp-devs/moonsharp/5-clr-interoperability)
