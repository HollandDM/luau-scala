# gopher-lua: Lua 5.1 VM in Go — Technical Deep Dive

> Source: [github.com/yuin/gopher-lua](https://github.com/yuin/gopher-lua)
> Reference Lua spec: [Lua 5.1.5 lopcodes.h](https://www.lua.org/source/5.1/lopcodes.h.html)

---

## 1. Project Scope and Design Philosophy

gopher-lua implements Lua 5.1 (plus the `goto` statement from Lua 5.2) entirely in Go — no cgo, no C bridge. The stated goal is "extensible semantics" as a Go-embeddable scripting language. Two explicit trade-offs anchor every architectural decision:

- **User-friendliness over raw speed.** The Go API is object-oriented method calls on `*LState`, not raw stack indices like the C Lua API. The stack is exposed for argument passing and return values only; the host program does not manually manage frame pointers.
- **Performance target is Python 3, not C Lua.** On the canonical `fib(35)` benchmark: gopher-lua 5.40s vs Python 3.4 5.84s vs reference Lua 5.1.4 (C) 1.71s. The 3x gap versus C is the expected cost of a GC'd managed runtime without JIT.

---

## 2. Instruction Encoding — A Divergence from Lua 5.1 C

### 2.1 Reference Layout (Lua 5.1 C VM)

The C VM places the opcode in the **low 6 bits** (`inst & 0x3F`), then A at bits 6–13, C at 14–22, B at 23–31 (all 32-bit word). Fields:

```
SIZE_OP=6  SIZE_A=8  SIZE_C=9  SIZE_B=9  SIZE_Bx=18
POS_OP=0   POS_A=6   POS_C=14  POS_B=23  POS_Bx=14
```

### 2.2 gopher-lua Layout

gopher-lua reverses the opcode to the **top 6 bits** (bits 26–31). Extraction via right-shift is slightly cheaper than masking in Go:

```go
// opcode.go
func opGetOpCode(inst uint32) int { return int(inst >> 26) }
func opGetArgA(inst uint32) int   { return int(inst>>18) & 0xff }
func opGetArgC(inst uint32) int   { return int(inst>>9) & 0x1ff }
func opGetArgB(inst uint32) int   { return int(inst & 0x1ff) }
func opGetArgBx(inst uint32) int  { return int(inst & 0x3ffff) }
func opGetArgSbx(inst uint32) int { return opGetArgBx(inst) - opMaxArgSbx }
```

Layout (msb → lsb):

```
[31..26] opcode (6 bits)
[25..18] A      (8 bits)
[17..9]  C      (9 bits)
[8..0]   B      (9 bits)
[17..0]  Bx     (18 bits, overlaps C+B)
```

The signed `sBx` field uses bias encoding: value = unsigned_Bx − `opMaxArgSbx` (= 2^17 − 1 = 131071). Jump offsets stored as sBx, so a jump of −3 is stored as 131068.

No explanation for the bit reversal exists in the repository (see [issue #126](https://github.com/yuin/gopher-lua/issues/126)). It's a compatibility break with C-compiled Lua bytecode — gopher-lua cannot execute `.luac` files from the C toolchain.

### 2.3 RK Encoding

B and C operands use the high bit as a "is-constant" flag (bit 8 of the 9-bit field, value 0x100). When set, the remaining 8 bits index into `FunctionProto.Constants`. When clear, they index a register. This is the same `BITRK`/`ISK`/`INDEXK` scheme as the C VM.

---

## 3. Opcode Set

gopher-lua extends Lua 5.1's 38 opcodes with two Go-specific additions:

| Code | Name | Type | Description |
|------|------|------|-------------|
| 0 | MOVE | iABC | `R(A) = R(B)` |
| 1 | MOVEN | iABC | **gopher-lua extension**: batch move — `R(A)=R(B)`, then execute C more consecutive MOVEs from instruction stream |
| 2 | LOADK | iABx | `R(A) = K(Bx)` |
| 3 | LOADBOOL | iABC | `R(A) = (Bool)B; if C then pc++` |
| 4 | LOADNIL | iABC | `R(A..B) = nil` |
| 5 | GETUPVAL | iABC | `R(A) = UpValue[B]` |
| 6 | GETGLOBAL | iABx | `R(A) = Gbl[K(Bx)]` |
| 7 | GETTABLE | iABC | `R(A) = R(B)[RK(C)]` (arbitrary key) |
| 8 | GETTABLEKS | iABC | **gopher-lua extension**: `R(A) = R(B)[K(C)]` string key fast path |
| 9 | SETGLOBAL | iABx | `Gbl[K(Bx)] = R(A)` |
| 10 | SETUPVAL | iABC | `UpValue[B] = R(A)` |
| 11 | SETTABLE | iABC | `R(A)[RK(B)] = RK(C)` |
| 12 | SETTABLEKS | iABC | **gopher-lua extension**: string key fast path for SETTABLE |
| 13 | NEWTABLE | iABC | `R(A) = {}` (B=array hint, C=hash hint) |
| 14 | SELF | iABC | `R(A+1) = R(B); R(A) = R(B)[RK(C)]` |
| 15–20 | ADD/SUB/MUL/DIV/MOD/POW | iABC | `R(A) = RK(B) op RK(C)` |
| 21–23 | UNM/NOT/LEN | iABC | unary ops |
| 24 | CONCAT | iABC | `R(A) = R(B) .. ... .. R(C)` |
| 25 | JMP | iAsBx | `pc += sBx` |
| 26–28 | EQ/LT/LE | iABC | comparison; skips next instruction if result matches A |
| 29–30 | TEST/TESTSET | iABC | conditional assignment |
| 31 | CALL | iABC | `R(A..A+C-2) = R(A)(R(A+1..A+B-1))` |
| 32 | TAILCALL | iABC | tail call (reuses frame) |
| 33 | RETURN | iABC | return `R(A..A+B-2)` to caller |
| 34–35 | FORLOOP/FORPREP | iAsBx | numeric for loop |
| 36 | TFORLOOP | iABC | generic for loop (`pairs`, `ipairs`) |
| 37 | SETLIST | iABC | bulk table initialization `R(A)[Bx*FPF+i] = R(A+i)` |
| 38 | CLOSE | iABC | close upvalues ≥ R(A) |
| 39 | CLOSURE | iABx | `R(A) = closure(Proto[Bx])`, followed by upvalue pseudo-instructions |
| 40 | VARARG | iABC | `R(A..A+B-2) = vararg` |
| 41 | NOP | iAsBx | no operation |

**MOVEN** is a compile-time fusion: the code generator detects consecutive MOVE instructions and merges them into one MOVEN where C holds the count of additional moves to execute without returning to the dispatch loop. Reduces dispatch overhead on assignment sequences.

**GETTABLEKS / SETTABLEKS** are emitted when the key is a string constant. They call `L.getFieldString` / `L.setFieldString` instead of the generic `L.getField`, avoiding an interface type switch on the key.

---

## 4. VM Dispatch — Jump Table

The dispatch mechanism is a **function pointer array** (`jumpTable`), not a switch statement:

```go
// _vm.go
type instFunc func(*LState, uint32, *callFrame) int
var jumpTable [opCodeMax + 1]instFunc

func init() {
    jumpTable[OP_MOVE]      = func(L *LState, inst uint32, baseframe *callFrame) int { ... }
    jumpTable[OP_MOVEN]     = func(L *LState, inst uint32, baseframe *callFrame) int { ... }
    // ... all 42 entries
}
```

Main loop:

```go
func mainLoop(L *LState, baseframe *callFrame) {
    var cf *callFrame
    var inst uint32
    for {
        cf = L.currentFrame
        inst = cf.Fn.Proto.Code[cf.Pc]
        cf.Pc++
        if jumpTable[int(inst>>26)](L, inst, baseframe) == 1 {
            return
        }
    }
}
```

Return value `1` from an opcode handler signals frame exit (RETURN, TAILCALL completing, coroutine yield). Return `0` continues the loop.

An open [issue #195](https://github.com/yuin/gopher-lua/issues/195) proposes switching to an inlined `switch` statement, which benchmarked ~15% faster on `fib(35)` by eliminating indirect function call overhead. The change was not merged because it requires duplicating both `mainLoop` and `mainLoopWithContext`.

### Context-Aware Loop

`mainLoopWithContext` wraps each iteration with a Go `select` on `L.ctx.Done()`:

```go
func mainLoopWithContext(L *LState, baseframe *callFrame) {
    var cf *callFrame
    var inst uint32
    for {
        cf = L.currentFrame
        inst = cf.Fn.Proto.Code[cf.Pc]
        cf.Pc++
        select {
        case <-L.ctx.Done():
            L.RaiseError(L.ctx.Err().Error())
            return
        default:
            if jumpTable[int(inst>>26)](L, inst, baseframe) == 1 {
                return
            }
        }
    }
}
```

The per-instruction `select` is responsible for ~30% performance overhead when a context is attached. `L.SetContext(ctx)` swaps `L.mainLoop` to point to `mainLoopWithContext`.

---

## 5. LValue Interface and Type System

All Lua values implement:

```go
type LValue interface {
    String() string
    Type() LValueType
}

type LValueType int
const (
    LTNil       LValueType = iota
    LTBool
    LTNumber
    LTString
    LTFunction
    LTUserData
    LTThread
    LTTable
    LTChannel
)
```

Concrete types:

| Go Type | Lua Type | Notes |
|---------|----------|-------|
| `LNilType` (singleton `LNil`) | nil | pointer receiver, one global instance |
| `LBool` (`bool` typedef) | boolean | `LTrue`, `LFalse` constants |
| `LNumber` (`float64` typedef) | number | all numbers are float64; `LNumberBit=64` |
| `LString` (`string` typedef) | string | immutable, interning via Go string intern |
| `*LTable` | table | dual array+hash storage |
| `*LFunction` | function | wraps either `*FunctionProto` or `LGFunction` |
| `*LUserData` | userdata | wraps `interface{}` Value field |
| `*LState` | thread | the coroutine/VM instance itself is the value |
| `chan LValue` aliased as `LChannel` | channel | gopher-lua extension, not in Lua 5.1 |

### LNumber Boxing — The Allocator

A critical performance concern: converting `LNumber` (float64) to `LValue` (interface) causes a heap allocation in Go because interface boxes non-pointer scalar values. gopher-lua attacks this with a custom allocator in `alloc.go`:

```go
type allocator struct {
    size         int
    fptrs        []float64           // pre-allocated backing slice
    fheader      *reflect.SliceHeader
    scratchValue LValue              // reusable boxing scratch
    scratchValueP *iface             // unsafe pointer to scratch
}
```

Strategy: allocate large `[]float64` blocks. When boxing an `LNumber`, append to current block and take a pointer to the appended element. The interface header stores this pointer. This avoids per-number `malloc`. The trade-off: an entire block cannot be GC'd until all floats in it are unreferenced.

### LVIsFalse

Truthiness follows Lua semantics (only `nil` and `false` are falsy):

```go
func LVIsFalse(v LValue) bool {
    return v == LNil || v == LFalse
}
```

---

## 6. LTable — Hybrid Array/Hash Storage

```go
type LTable struct {
    array    []LValue
    strdict  map[string]LValue   // string keys, O(1)
    dict     map[LValue]LValue   // all other keys
    keys     []LValue            // insertion order for Next()
    k2i      map[LValue]int      // key -> index in keys[]
    metatable LValue
}
```

Three storage regions:

1. **`array`** — integer keys 1..N stored at `array[key-1]`. `RawSetInt` converts `index := key - 1`. Gaps filled with `LNil`. `MaxArrayIndex = 2^26` = 67,108,864.
2. **`strdict`** — string keys separated out for O(1) access without boxing to `LValue` interface for map lookup.
3. **`dict`** — all remaining keys (number keys outside array range, boolean, table, function keys).

The `keys` slice + `k2i` map maintain insertion order for `table.next()` / `pairs()` iteration. Array part iterated first (indices 1..MaxN), then hash keys in insertion order.

No custom rehashing — Go map growth handles `dict` and `strdict`. Array part grows via `append`.

---

## 7. FunctionProto and Closures

### FunctionProto (compile-time, immutable, shareable)

```go
type FunctionProto struct {
    SourceName          string
    LineDefined         int
    LastLineDefined     int
    NumParameters       uint8
    IsVarArg            uint8
    NumUsedRegisters    uint8
    Code                []uint32          // bytecode
    Constants           []LValue          // K(i) table
    FunctionPrototypes  []*FunctionProto  // nested functions
    DbgSourcePositions  []int
    DbgLocals           []*DbgLocalInfo
    DbgUpvalues         []string
    DbgCalls            []DbgCall
}
```

`FunctionProto` is read-only after compilation. Multiple `LFunction` instances across multiple `LState`s can share the same `Proto`. This is the mechanism for bytecode sharing:

```go
chunk, _ := parse.Parse(reader, "chunk")
proto, _ := lua.Compile(chunk, "chunk")   // *FunctionProto
// share proto across goroutines:
L1.Push(L1.NewFunctionFromProto(proto))
L2.Push(L2.NewFunctionFromProto(proto))
```

### LFunction (runtime, per-closure)

```go
type LFunction struct {
    IsG       bool
    Env       *LTable
    Proto     *FunctionProto   // nil if IsG
    GFunction LGFunction       // nil if !IsG
    Upvalues  []*Upvalue       // captured variables
}
type LGFunction func(*LState) int
```

### Upvalue (runtime)

```go
type Upvalue struct {
    reg    *registry   // pointer to owning thread's register array
    index  int         // position in reg.array (open) or ignored (closed)
    value  LValue      // stored value after Close()
    closed bool
    next   *Upvalue    // linked list node
}
```

**Open upvalue**: `reg` points to a live `registry`, `index` is the stack slot. Reading/writing goes through `reg.array[index]`.

**Closed upvalue**: after `OP_CLOSE` or frame exit, `closed=true` and `value` holds a snapshot. The stack slot is freed; the upvalue lives on the heap.

`LState.uvcache` holds the linked list of all open upvalues for the thread, sorted by ascending `index`. `closeUpvalues(reg, index)` walks the list and calls `Close()` on all upvalues with `index >= param`.

### CLOSURE Execution

```go
// OP_CLOSURE handler (_vm.go)
proto := cf.Fn.Proto.FunctionPrototypes[Bx]
closure := newLFunctionL(proto, cf.Fn.Env, int(proto.NumUpvalues))
reg.array[RA] = closure
for i := 0; i < int(proto.NumUpvalues); i++ {
    inst = cf.Fn.Proto.Code[cf.Pc]
    cf.Pc++
    B := opGetArgB(inst)
    switch opGetOpCode(inst) {
    case OP_MOVE:
        closure.Upvalues[i] = L.findUpvalue(lbase + B)  // capture local
    case OP_GETUPVAL:
        closure.Upvalues[i] = cf.Fn.Upvalues[B]          // inherit upvalue
    }
}
```

The N pseudo-instructions following `OP_CLOSURE` in the bytecode stream are consumed by the handler, not dispatched. This matches the Lua 5.1 C VM pattern.

---

## 8. LState — The VM Instance

```go
type LState struct {
    G            *Global          // shared global state
    Parent       *LState          // parent coroutine (nil for main thread)
    Panic        func(*LState)    // unhandled error hook
    Dead         bool             // thread terminated
    Options      Options
    stop         int32            // atomic; set by Close()
    alloc        *allocator
    currentFrame *callFrame       // hot pointer to active frame
    wrapped      bool             // coroutine.wrap vs coroutine.create
    uvcache      *Upvalue         // open upvalue linked list head
    hasErrorFunc bool
    mainLoop     func(*LState, *callFrame)  // points to mainLoop or mainLoopWithContext
    ctx          context.Context
    ctxCancelFn  context.CancelFunc
    stack        callFrameStack   // call frame stack
    reg          *registry        // value register array
    Env          *LTable          // _G environment
}
```

### Global State

```go
type Global struct {
    MainThread    *LState
    CurrentThread *LState
    Registry      *LTable   // internal registry table
    Global        *LTable   // _G
    builtinMts    map[int]LValue  // metatables for primitive types (string, etc.)
    tempFiles     []*os.File
}
```

`G` is shared across all coroutines (threads) forked from the same root `LState`. `G.CurrentThread` always points to the executing coroutine.

---

## 9. Call Frame Stack

### callFrame

```go
type callFrame struct {
    Idx        int        // frame index (position in stack)
    Fn         *LFunction // executing function
    Parent     *callFrame // previous frame (linked list pointer)
    Pc         int        // program counter into Fn.Proto.Code
    Base       int        // absolute index of function in reg.array
    LocalBase  int        // Base+1 (first local variable)
    ReturnBase int        // where to write return values in caller's frame
    NArgs      int        // argument count
    NRet       int        // expected return value count (MultRet = -1)
    TailCall   int        // tail call nesting depth
}
```

### Two Stack Implementations

`callFrameStack` is an interface:

```go
type callFrameStack interface {
    Push(v callFrame)
    Pop() *callFrame
    Last() *callFrame
    SetSp(sp int)
    Sp() int
    At(sp int) *callFrame
    IsFull() bool
    IsEmpty() bool
    FreeAll()
}
```

**fixedCallFrameStack**: pre-allocated `[]callFrame` of `Options.CallStackSize` (default 256). Zero-allocation push/pop. Faster.

**autoGrowingCallFrameStack**: segment-based — `[]*callFrameStackSegment`. Each segment holds `FramesPerSegment` (power-of-2, enables bitshift index arithmetic) frames, allocated from a `sync.Pool`. Growth is O(1) amortized; unused segments are pooled. Enabled by `Options.MinimizeStackMemory = true`.

### registry (Value Stack)

```go
type registry struct {
    array   []LValue
    top     int
    growBy  int   // Options.RegistryGrowStep (default 32)
    maxSize int   // Options.RegistryMaxSize
    alloc   *allocator
    handler registryHandler
}
```

Default initial size: `RegistrySize = 256*20 = 5120`. The registry is a flat `[]LValue` — the register file for all active call frames. Each `callFrame.LocalBase` indexes into this array. Frames do not allocate separate stacks; all frames share one `registry`.

---

## 10. Coroutines — Cooperative, Not Goroutine-Based

**Key insight**: gopher-lua coroutines are NOT Go goroutines. They are cooperative, stack-switching coroutines implemented via parent/child `*LState` linkage and explicit control transfer.

### Creation

`L.NewThread()` allocates a new `*LState` with:
- Its own `stack` (call frame stack)
- Its own `reg` (registry / value stack)
- `Parent = nil` initially
- Shared `G` with creator

### Resume

```go
// coroutinelib.go (simplified)
func coResume(L *LState) int {
    co := L.CheckThread(1)
    // move args from L to co
    L.XMoveTo(co, nargs)
    // set parent linkage
    co.Parent = L
    L.G.CurrentThread = co
    // run co's main loop
    threadRun(co)
    // after co yields/returns, control is back here
    // move results from co to L
    co.XMoveTo(L, nresults)
    return nresults + 1  // +1 for status bool
}
```

### Yield

`coroutine.yield` returns `-1` from the Go function. `callGFunction` detects negative return:

```go
gfnret := frame.Fn.GFunction(L)
if gfnret < 0 {
    switchToParentThread(L, L.GetTop(), false, false)
    return true
}
```

### switchToParentThread

```go
func switchToParentThread(L *LState, nargs int, haserror bool, kill bool) {
    parent := L.Parent
    // error if no parent (yield outside coroutine)
    L.G.CurrentThread = parent
    L.Parent = nil
    if !L.wrapped {
        if haserror { parent.Push(LFalse) } else { parent.Push(LTrue) }
    }
    L.XMoveTo(parent, nargs)      // transfer yield values to parent stack
    L.stack.Pop()
    // adjust parent's register top
    offset := L.currentFrame.LocalBase - L.currentFrame.ReturnBase
    L.currentFrame = L.stack.Last()
    L.reg.SetTop(L.reg.Top() - offset)
    if kill { L.kill() }
}
```

After `switchToParentThread`, the parent `LState`'s `mainLoop` continues executing — the parent was blocked inside `threadRun(co)` which directly called `co`'s `mainLoop`. No goroutine blocking, no channel synchronization.

**Thread state machine**:
- `th.Dead == false && !th.isStarted()` → suspended, never resumed
- `th.Dead == false && th.isStarted()` → suspended mid-execution (yielded)
- `th.Dead == true` → terminated

---

## 11. Go Goroutine Concurrency Model

`LState` is **not goroutine-safe**. The recommended pattern: one `LState` per goroutine, communicate via `LChannel`.

### LChannel

`LChannel` is `chan LValue`. The `channellib.go` implements channel operations using Go's `reflect.SelectCase` for multi-channel select:

```go
func channelSelect(L *LState) int {
    // Build []reflect.SelectCase from Lua table of {direction, channel, value}
    // directions: "<-|" send, "|<-" receive, "default"
    chosen, recv, recvOK := reflect.Select(cases)
    // push results
}
```

Type safety constraint: only `LTNil`, `LTBool`, `LTNumber`, `LTString`, and plain `LTTable` (no metatable) can traverse channels. Functions, threads, userdata, and metatabled tables are blocked — they hold references to `LState`-specific internals.

### Bytecode Sharing Pattern

The recommended pattern for concurrent workers sharing Lua code:

```go
chunk, _ := parse.Parse(strings.NewReader(src), "worker")
proto, _ := lua.Compile(chunk, "worker")   // compiled once

for i := 0; i < N; i++ {
    go func() {
        L := lua.NewState()
        defer L.Close()
        fn := L.NewFunctionFromProto(proto)
        L.Push(fn)
        L.Call(0, 0)
    }()
}
```

`FunctionProto` is read-only; concurrent reads from multiple goroutines are safe.

---

## 12. Go GC Interaction

### LValue as Go Values

All `LValue` types are ordinary Go heap objects. Go's GC traces them automatically. No reference counting, no separate Lua GC. `collectgarbage()` in Lua code calls `runtime.GC()` — it GC's the entire Go process, not just Lua objects.

### Interface Boxing Cost

`LNumber` (float64) must be boxed into an `LValue` interface on every register write. The `allocator` mitigates this by bulk-allocating float64 arrays, but GC pressure remains: a block of floats is retained until all contained values become unreachable — worst case O(block_size) space overhead.

### LTable and Circular References

Lua tables can be self-referential (metatables pointing to themselves). Go's GC handles cycles via tricolor mark-and-sweep — no action needed from the VM. The C Lua VM's incremental GC is replaced entirely.

### Upvalue Lifetime

Open upvalues hold a `*registry` pointer — keeping the owning `LState`'s value stack alive. Closed upvalues hold `LValue` directly. `OP_CLOSE` and frame exit convert open→closed, releasing the registry reference. This mirrors the C VM's `luaF_close` behavior but relies on Go GC rather than manual `luaM_free`.

### LUserData and Finalizers

`LUserData.Value` is `interface{}`. Go does not provide a general destructor hook from Lua's `__gc` metamethod — `__gc` on userdata is not implemented in gopher-lua. Resources requiring cleanup must be handled explicitly by the embedding application.

---

## 13. Compiler — AST to FunctionProto

Parser lives in `parse/` (hand-written recursive descent). AST types in `ast/`. Compiler in `compile.go`.

### Core Structures

```go
type funcContext struct {
    Code     *codeStore
    Proto    *FunctionProto
    Block    *block
    Upvalues []*varInfo    // upvalue capture list
    regTop   int           // next free register
    parent   *funcContext
    // ...
}

type codeStore struct {
    codes []uint32
    lines []int
    pc    int
}
```

### Compilation Flow

1. `compileFunctionExpr` creates a child `funcContext`
2. Parameters registered as locals (assigned registers)
3. `compileChunk` walks statement list, dispatches to specialized compilers
4. `compileExpr` returns register delta; context types `ecLocal/ecGlobal/ecUpvalue/ecTable` control code emission
5. Constants deduplicated via `ConstIndex` (returns existing index or appends)
6. Post-pass: `constFold` folds compile-time arithmetic; bulk-move optimizer detects consecutive MOVEs and emits MOVEN; jump targets patched

### Upvalue Resolution

`getIdentRefType` walks scope chain:
- Found in current function's locals → `ecLocal`
- Found in parent function's locals → create upvalue entry, emit `OP_MOVE` pseudo-instruction after CLOSURE
- Found in parent's upvalues → create upvalue entry, emit `OP_GETUPVAL` pseudo-instruction

Parent block marked `RefUpvalue = true` → compiler emits `OP_CLOSE` when the scope exits.

### Register Allocation

Register allocation is purely linear — `regTop` increments on allocation, decrements on scope exit. No register graph coloring. Temporaries are stack-allocated top-down. `NumUsedRegisters` in `FunctionProto` records peak register usage for frame sizing at call time.

---

## 14. Standard Library Port

All standard libraries reimplemented in Go as `LGFunction` maps. Key notes:

| Library | File | Notes |
|---------|------|-------|
| base | `baselib.go` | `pcall`/`xpcall` via `L.PCall`; `pairs`/`ipairs` via `tbl.Next()` |
| string | `stringlib.go` | Lua pattern matching delegated to `github.com/yuin/gopher-lua/pm` package — a pure Go reimplementation of Lua's pattern engine (not Go's `regexp`) |
| table | `tablelib.go` | sort, concat, insert, remove |
| math | `mathlib.go` | wraps `math` package |
| io | `iolib.go` | wraps `os` and `bufio` |
| os | `oslib.go` | adds `os.setenv`; missing `os.setlocale` |
| coroutine | `coroutinelib.go` | full coroutine.create/resume/yield/wrap/status/running |
| channel | `channellib.go` | gopher-lua extension; not Lua 5.1 standard |
| debug | `debuglib.go` | partial; no `sethook`/`getinfo` full debug hooks |
| package | `loadlib.go` | `require`, `package.path`, `package.preload`; no `loadlib` (C dylib loading blocked) |

String metatable attachment:

```go
// stringlib.go
mod = L.RegisterModule(StringLibName, strFuncs).(*LTable)
mod.RawSetString("__index", mod)
L.G.builtinMts[int(LTString)] = mod
```

String method dispatch on `("hello"):upper()` hits `__index` on the string metatable stored in `builtinMts[LTString]`, without any per-string metatable allocation.

Library initialization order is deterministic despite Go's randomized map iteration: `OpenLibs` explicitly opens LoadLib and BaseLib first, then iterates `luaLibs` slice (ordered, not map):

```go
// linit.go
var luaLibs = []luaLib{
    {LoadLibName, OpenPackage},
    {BaseLibName, OpenBase},
    {TabLibName, OpenTable},
    // ...
}
```

---

## 15. Go-Embedding API Idioms

### LGFunction Convention

```go
type LGFunction func(*LState) int  // return = number of values pushed to stack
```

Return 0 for no results. Return negative to trigger yield (used internally by coroutine.yield).

### Stack vs Object API

Unlike C Lua API, no raw stack index manipulation for arguments — use typed checkers:

```go
func myFunc(L *lua.LState) int {
    s := L.CheckString(1)    // arg 1, raises error if wrong type
    n := L.OptInt(2, 0)      // arg 2, default 0 if nil
    L.Push(lua.LString(s + strconv.Itoa(n)))
    return 1
}
```

### UserData and Metatables

```go
mt := L.NewTypeMetatable("mytype")   // stored in L.G.Registry
L.SetField(mt, "__index", L.SetFuncs(L.NewTable(), methods))
ud := L.NewUserData()
ud.Value = &MyGoStruct{}
L.SetMetatable(ud, mt)
L.Push(ud)
```

### Protected Calls

```go
err := L.CallByParam(lua.P{
    Fn:      L.GetGlobal("fn"),
    NRet:    1,
    Protect: true,   // wrap in pcall equivalent
}, arg1)
```

### Performance Configuration

```go
L := lua.NewState(lua.Options{
    CallStackSize:       512,         // max Lua call depth (Go calls don't count)
    RegistrySize:        1024 * 20,   // initial register array size
    RegistryMaxSize:     1024 * 80,   // cap; 0 = unlimited
    RegistryGrowStep:    32,
    MinimizeStackMemory: false,       // true = autoGrowingCallFrameStack
    SkipOpenLibs:        false,
    IncludeGoStackTrace: false,       // true = Go stack in Lua error messages
})
```

---

## 16. Porting C VM to a GC'd Language — Key Idioms

gopher-lua's implementation demonstrates several transferable patterns:

### Replace malloc with interface allocation pooling
C VM allocates tagged unions; Go interface boxing requires heap allocation for scalars. Bulk-allocate backing arrays and take interior pointers to amortize allocator pressure. Accept GC block retention as trade-off.

### Jump table over switch for dispatch
`var table [N]func(...)` with `table[opcode](...)` gives O(1) dispatch without branch prediction burden of a large switch. In Go the indirect call cost is measurable — the proposed switch inlining (issue #195) shows 15% gain — but jump tables are simpler to maintain alongside context-aware variants.

### Two mainLoop variants, selected at runtime
Instead of per-instruction `if ctx != nil` checks, store a function pointer in the `LState` and swap it on `SetContext`. Zero overhead on hot path when no context is set.

### Coroutines without goroutines
Stack-switching coroutines via parent/child pointer linkage and direct function calls. No channel synchronization. `switchToParentThread` is a plain function call — parent resumes from inside `threadRun(co)` because `co`'s `mainLoop` returns. This achieves full Lua coroutine semantics with minimal overhead.

### Immutable compiled FunctionProto for thread safety
Make the compiled bytecode + constants an immutable read-only struct. All mutable runtime state lives in `LFunction.Upvalues` and `LState.reg`. Allows sharing compiled code across goroutines without locks.

### RK encoding preserves C VM constant-folding idioms
The high-bit flag on B/C operands (distinguishing register vs constant) maps directly to Go code: `if opIsK(x) { K[opIndexK(x)] } else { reg[x] }`. No separate instruction variants needed for constant operands.

### Extend the opcode set rather than interpret
MOVEN (bulk move), GETTABLEKS/SETTABLEKS (string key fast path) are new opcodes emitted by the Go compiler. Adding VM-level specializations for hot patterns is cheaper than adding runtime type checks inside existing handlers.

---

## 17. Known Limitations vs Lua 5.1 C

- No `string.dump` / binary bytecode load (no `.luac` compatibility — bit layout differs)
- No `loadlib` (C shared library loading)
- No `os.setlocale`
- No debug hooks (`sethook`/`gethook`)
- No `__gc` metamethod on userdata
- No daylight saving time in `os.time`/`os.date`
- `collectgarbage` triggers Go's full GC, not a Lua-scoped collection
- `LState` not goroutine-safe; no fine-grained locking
- Context-aware execution costs ~30% throughput

---

## Sources

- [github.com/yuin/gopher-lua — repository root](https://github.com/yuin/gopher-lua)
- [gopher-lua README.md](https://raw.githubusercontent.com/yuin/gopher-lua/master/README.md)
- [gopher-lua opcode.go](https://raw.githubusercontent.com/yuin/gopher-lua/master/opcode.go)
- [gopher-lua vm.go](https://raw.githubusercontent.com/yuin/gopher-lua/master/vm.go)
- [gopher-lua _vm.go](https://raw.githubusercontent.com/yuin/gopher-lua/master/_vm.go)
- [gopher-lua _state.go](https://raw.githubusercontent.com/yuin/gopher-lua/master/_state.go)
- [gopher-lua value.go](https://raw.githubusercontent.com/yuin/gopher-lua/master/value.go)
- [gopher-lua alloc.go](https://raw.githubusercontent.com/yuin/gopher-lua/master/alloc.go)
- [gopher-lua table.go](https://raw.githubusercontent.com/yuin/gopher-lua/master/table.go)
- [gopher-lua function.go](https://raw.githubusercontent.com/yuin/gopher-lua/master/function.go)
- [gopher-lua compile.go](https://raw.githubusercontent.com/yuin/gopher-lua/master/compile.go)
- [gopher-lua coroutinelib.go](https://raw.githubusercontent.com/yuin/gopher-lua/master/coroutinelib.go)
- [gopher-lua channellib.go](https://raw.githubusercontent.com/yuin/gopher-lua/master/channellib.go)
- [gopher-lua auxlib.go](https://raw.githubusercontent.com/yuin/gopher-lua/master/auxlib.go)
- [pkg.go.dev — gopher-lua API docs](https://pkg.go.dev/github.com/yuin/gopher-lua)
- [gopher-lua Benchmarks wiki](https://github.com/yuin/gopher-lua/wiki/Benchmarks)
- [Issue #126 — opcode bit position](https://github.com/yuin/gopher-lua/issues/126)
- [Issue #195 — switch vs jump table dispatch](https://github.com/yuin/gopher-lua/issues/195)
- [Lua 5.1.5 lopcodes.h](https://www.lua.org/source/5.1/lopcodes.h.html)
