# LuaJ: Lua 5.2 on the JVM — Deep Technical Reference

> Primary source: [github.com/luaj/luaj](https://github.com/luaj/luaj) (MIT, last release v3.0.2, March 2019)
> All class references are in package `org.luaj.vm2` unless otherwise noted.

---

## 1. Project Architecture

LuaJ implements a complete Lua 5.2 runtime in pure Java targeting both JME and JSE. The source tree separates platform-agnostic core from platform-specific extensions:

```
src/core/org/luaj/vm2/          -- portable runtime (JME + JSE)
src/jse/org/luaj/vm2/           -- JSE-only: parser, LuaJC compiler, luajava
src/jme/org/luaj/vm2/           -- JME-only platform shims
```

Core source files in `src/core/org/luaj/vm2/`:

| File | Role |
|------|------|
| `LuaValue.java` | Abstract base for all Lua values |
| `Varargs.java` | Abstract multiple-return / vararg representation |
| `LuaNil.java` | Singleton nil |
| `LuaBoolean.java` | Singleton true/false |
| `LuaNumber.java` | Abstract numeric base |
| `LuaInteger.java` | Boxed `int` Lua number |
| `LuaDouble.java` | Boxed `double` Lua number |
| `LuaString.java` | Byte-array backed Lua string |
| `LuaTable.java` | Hash+array hybrid Lua table |
| `LuaFunction.java` | Abstract function base |
| `LuaClosure.java` | Interpreted bytecode function |
| `LuaThread.java` | Coroutine (one Java thread each) |
| `LuaUserdata.java` | Opaque Java object wrapper |
| `Prototype.java` | Compiled Lua function description |
| `UpValue.java` | Mutable upvalue cell (open → closed lifecycle) |
| `TailcallVarargs.java` | Trampoline node for tail calls |
| `WeakTable.java` | Weak-reference table implementation |
| `Globals.java` | Per-environment root: libraries, loader chain, running thread |

JSE compiler source in `src/jse/org/luaj/vm2/luajc/`:

| File | Role |
|------|------|
| `LuaJC.java` | Entry point, installs into Globals |
| `ProtoInfo.java` | SSA/liveness analysis on Prototype |
| `BasicBlock.java` | Control-flow graph nodes |
| `VarInfo.java` | Per-variable dataflow info |
| `UpvalInfo.java` | Upvalue read/write classification |
| `JavaGen.java` | Opcode-to-bytecode dispatch layer |
| `JavaBuilder.java` | BCEL-based bytecode emitter |
| `JavaLoader.java` | Loads compiled `.class` bytes into JVM |

---

## 2. Value Representation: The LuaValue Hierarchy

### 2.1 Class Tree

```
Varargs  (abstract)
└── LuaValue  (abstract, extends Varargs)
    ├── LuaNil          type=TNIL  (0)   singleton _NIL
    ├── LuaBoolean      type=TBOOLEAN (1) singletons _TRUE, _FALSE
    ├── LuaNumber       type=TNUMBER (3)
    │   ├── LuaInteger  -- fits java int;  TINT = -2 internally
    │   └── LuaDouble   -- all other numbers
    ├── LuaString       type=TSTRING  (4)
    ├── LuaTable        type=TTABLE   (5)
    ├── LuaFunction     type=TFUNCTION (6)
    │   ├── LuaClosure        -- interpreted Lua bytecode
    │   ├── LibFunction       -- Java-implemented library fn
    │   │   ├── ZeroArgFunction
    │   │   ├── OneArgFunction
    │   │   ├── TwoArgFunction
    │   │   ├── ThreeArgFunction
    │   │   └── VarArgFunction
    │   └── (generated classes from LuaJC compiler)
    ├── LuaUserdata     type=TUSERDATA (7)
    └── LuaThread       type=TTHREAD  (8)
```

`LuaValue` extends `Varargs` so every single value acts as a one-element varargs list — `arg(1)` returns `this`, `narg()` returns 1. This collapses the call/return protocol: a function that returns one value returns a `LuaValue`; a function that returns many returns a richer `Varargs` subclass.

### 2.2 Type Constants

```java
public static final int TNIL      = 0;
public static final int TBOOLEAN  = 1;
public static final int TNUMBER   = 3;
public static final int TSTRING   = 4;
public static final int TTABLE    = 5;
public static final int TFUNCTION = 6;
public static final int TUSERDATA = 7;
public static final int TTHREAD   = 8;
// internal implementation tags:
public static final int TINT  = -2;   // LuaInteger
public static final int TNONE = -1;   // empty Varargs (NONE constant)
public static final int TVALUE = 9;   // generic LuaValue slot
```

### 2.3 Singletons and Pooling

Every allocation-reducing trick is exposed through factory methods, never public constructors:

- `LuaValue.NIL` — `LuaNil._NIL` singleton
- `LuaValue.TRUE` / `FALSE` — `LuaBoolean._TRUE` / `_FALSE` singletons
- `LuaValue.NONE` — `None._NONE`, the empty `Varargs` (zero-element return)
- `LuaValue.ZERO` / `ONE` — `LuaInteger.valueOf(0/1)`
- `LuaInteger.valueOf(int i)` — returns from a pre-allocated pool of 512 instances for i ∈ [-256, 255]; allocates above that
- `LuaDouble.valueOf(double d)` — if `(int)d == d`, returns `LuaInteger.valueOf((int)d)` instead (no redundant double wrapping for whole numbers like 1.0)
- `LuaString.valueOf(String)` — checks `RecentShortStrings` cache (128 slots, threshold 32 bytes); bypasses for longer strings

Every Lua value is a heap-allocated Java object. There is no unboxed numeric representation. This means tight numeric loops pay allocation + GC pressure for every intermediate result that escapes a register.

### 2.4 LuaString Internals

```java
final byte[] m_bytes;
final int    m_offset;
final int    m_length;
```

Strings are byte sequences, not Java `char[]`. Lua 5.x strings are byte strings; this matches exactly. Multiple `LuaString` instances may share the same `byte[]` backing (substring slicing). Modification is forbidden.

`decodeAsUtf8()` / `encodeToUtf8()` handle Java interop. The string metatable `s_metatable` is a static field shared across all `Globals` instances in the same classloader — a thread-safety hazard documented as: "server environments should replace with a read-only table."

### 2.5 LuaNumber: Integer / Double Split

`LuaNumber` is abstract. Subtype is chosen at construction:
- `LuaInteger` — for values representable as Java `int`
- `LuaDouble` — for all others

Arithmetic promotes to `LuaDouble` when needed. `LuaInteger.sub(int rhs)` returns `LuaDouble.valueOf(v - rhs)` — subtraction always promotes, avoiding overflow surprises. Division (`LuaDouble.valueOf(a / b)`) always returns double. The `valueOf(long l)` factory returns `LuaInteger` if `l` fits in `int`, otherwise `LuaDouble`.

### 2.6 LuaValue Method Protocol

All operations are virtual dispatch on `LuaValue`. Key method families:

```java
// Type inspection (no throw)
boolean isnil()       boolean isnumber()    boolean isstring()
boolean istable()     boolean isfunction()  boolean isint()

// Safe conversion (returns 0/null on mismatch)
double  todouble()    int toint()           String tojstring()

// Checked conversion (throws LuaError on mismatch)
double  checkdouble() int checkint()        LuaString checkstring()
LuaTable checktable() LuaFunction checkfunction()

// Optional (return defval if nil/none, throw on wrong type)
int     optint(int defval)
double  optdouble(double defval)

// Arithmetic (metamethod-aware)
LuaValue add(LuaValue rhs)   // → arithmt(ADD, rhs)
LuaValue sub(LuaValue rhs)   // → arithmt(SUB, rhs)
LuaValue mul(LuaValue rhs)
LuaValue div(LuaValue rhs)
LuaValue pow(LuaValue rhs)

// Overloads with primitive rhs for avoiding re-boxing
LuaValue add(double rhs)
LuaValue add(int rhs)

// Table access (metamethod-aware)
LuaValue get(LuaValue key)
void     set(LuaValue key, LuaValue value)
LuaValue rawget(LuaValue key)
void     rawset(LuaValue key, LuaValue value)

// Call protocol
LuaValue call()
LuaValue call(LuaValue arg)
LuaValue call(LuaValue a1, LuaValue a2)
LuaValue call(LuaValue a1, LuaValue a2, LuaValue a3)
Varargs  invoke(Varargs args)      // multi-return entry point
Varargs  invokemethod(String name, Varargs args)
```

`call()` variants return a single `LuaValue` (truncate multi-return). `invoke()` returns `Varargs` (preserve all returns). `callmt()` is the internal helper that looks up `__call` when dispatch falls through to a non-function.

---

## 3. Varargs: Multiple Return and Variable Arguments

`Varargs` is an abstract class (not an interface — LuaValue extends it). Core contract:

```java
abstract public LuaValue arg(int i);      // 1-based
abstract public int narg();
abstract public LuaValue arg1();
abstract public Varargs subargs(int start);
```

Concrete implementations inside `LuaValue.java` and `Varargs.java`:

| Class | Description |
|-------|-------------|
| `LuaValue` | Itself — single value as 1-element Varargs |
| `None` | Zero-element (the `NONE` constant) |
| `PairVarargs` | Two elements: `LuaValue v1` + `Varargs rest` |
| `ArrayVarargs` | Fixed `LuaValue[]` array + optional `Varargs` tail |
| `ArrayPartVarargs` | Slice of array with offset/length |

Factory methods on `LuaValue`:

```java
static Varargs varargsOf(LuaValue[] v)
static Varargs varargsOf(LuaValue[] v, Varargs tail)
static Varargs varargsOf(LuaValue[] v, int offset, int length)
static Varargs varargsOf(LuaValue[] v, int offset, int length, Varargs tail)
static Varargs varargsOf(LuaValue v1, Varargs rest)
```

The interpreter builds return values via these factories without extra copies. The `OP_RETURN b=0` case (return "top of stack to end") does `varargsOf(stack, a, top-v.narg()-a, v)`, threading in the last multi-return result `v` as the tail.

`eval()` on a `Varargs` resolves `TailcallVarargs` chains (see §7). All `check*`, `opt*`, `to*` methods are available on `Varargs` as indexed variants: `checkint(int i)`, `optstring(int i, String def)`, etc.

---

## 4. LuaTable

### 4.1 Storage Layout

```java
LuaValue[] array;   // 1-based array part (key k → array[k-1])
Slot[]     hash;    // open-addressed hash part
int        hashEntries;
```

Integer keys ≥ 1 and ≤ `array.length` go directly into `array`. All others go into `hash`. This mirrors PUC-Lua's split-storage design.

`Slot` is an interface with several implementations:
- `NormalEntry` — generic `(LuaValue key, LuaValue value)` pair
- `IntKeyEntry` — integer key stored in hash (out-of-bounds for array)
- `NumberValueEntry` — non-integer key with numeric value (micro-optimization)

Hash uses power-of-2 sizing with `hashpow2()` for most keys or `hashmod()` for strings. Collision chains stored inline using linked-list next pointers within the `Slot` interface.

Resize: when load factor exceeded, `rehash()` runs. It counts integer keys, decides new array size (to fit as many integers as possible with ≥50% occupancy), allocates new arrays, and re-inserts all entries.

### 4.2 Iteration

`next(LuaValue key)` walks array first, then hash slots. Returns `(nextKey, value)` pair as `Varargs`. Stateless from caller perspective — position tracked by key identity.

### 4.3 Metatables

`m_metatable` field (type `Metatable` interface, not `LuaTable`). `NonTableMetatable` wraps non-table metatables. `__index` / `__newindex` dispatch done in `LuaTable.rawget()` / `rawset()` callers — the table itself calls `get()` / `set()` on `LuaValue` which handles the metamethod lookup chain.

### 4.4 Weak Tables

`__mode = "k"/"v"/"kv"` triggers wrapping keys/values in `WeakReference`. `WeakTable` class overrides hash operations to handle reference clearing. Iteration via `next()` skips cleared (dead) entries.

---

## 5. Execution Strategy 1 — Interpreted Prototype Execution

### 5.1 Prototype Structure

`Prototype` is the compiled representation of a Lua function, loaded from binary bytecode (`.luac`) or compiled from source by LuaC:

```java
int[]        code;           // packed 32-bit instructions (opcode | A | B | C)
LuaValue[]   k;              // constant pool
Prototype[]  p;              // nested function prototypes
int[]        lineinfo;       // PC → source line mapping
LocVars[]    locvars;        // local variable debug info
Upvaldesc[]  upvalues;       // upvalue descriptors
LuaString    source;         // source filename
int          linedefined;
int          lastlinedefined;
int          numparams;
int          is_vararg;      // 1 = vararg function
int          maxstacksize;   // max registers used
```

### 5.2 LuaClosure: The Interpreter

```java
public final Prototype p;
public final UpValue[] upValues;
final Globals globals;
```

`LuaClosure` extends `LuaFunction`. Call variants (0–3 fixed args) and `invoke(Varargs)` all delegate to:

```java
protected Varargs execute(LuaValue[] stack, Varargs varargs)
```

Caller allocates `stack = new LuaValue[p.maxstacksize]`, fills parameters, then calls `execute`. The interpreter loop:

```java
while (true) {
    int i = code[pc++];
    int a = (i >> 6) & 0xff;
    switch (i & 0x3f) {
        case Lua.OP_MOVE:   stack[a] = stack[i>>>23]; continue;
        case Lua.OP_LOADK:  stack[a] = k[i>>>14]; continue;
        case Lua.OP_LOADNIL:
            for (int b = i>>>23; a <= b; a++) stack[a] = NIL; continue;
        case Lua.OP_GETUPVAL: stack[a] = upValues[i>>>23].getValue(); continue;
        case Lua.OP_SETUPVAL: upValues[a].setValue(stack[i>>>23]); continue;
        case Lua.OP_GETTABUP:
            stack[a] = upValues[i>>>23].getValue()
                .get((b=(i>>14)&0x1ff)>0xff ? k[b&0xff] : stack[b]);
            continue;
        case Lua.OP_SETTABUP:
            upValues[a].getValue().set(
                (b=i>>>23)>0xff ? k[b&0xff] : stack[b],
                (c=(i>>14)&0x1ff)>0xff ? k[c&0xff] : stack[c]);
            continue;
        case Lua.OP_ADD:
            stack[a] = ((b=i>>>23)>0xff?k[b&0xff]:stack[b])
                       .add((c=(i>>14)&0x1ff)>0xff?k[c&0xff]:stack[c]);
            continue;
        case Lua.OP_CALL: {
            // B = arg count+1, C = ret count+1; 0 = vararg
            switch (i & (Lua.MASK_B | Lua.MASK_C)) {
                case (1<<POS_B)|(0<<POS_C):
                    v = stack[a].invoke(NONE); top = a+v.narg(); continue;
                case (2<<POS_B)|(0<<POS_C):
                    v = stack[a].invoke(stack[a+1]); top = a+v.narg(); continue;
                // ... more cases
                default:
                    b = i>>>23; c = (i>>14)&0x1ff;
                    v = stack[a].invoke(b>0 ?
                        varargsOf(stack,a+1,b-1) :
                        varargsOf(stack,a+1,top-v.narg()-(a+1),v));
                    // distribute returns into stack
            }
        }
        case Lua.OP_TAILCALL: {
            switch (i & Lua.MASK_B) {
                case (1<<POS_B): return new TailcallVarargs(stack[a], NONE);
                case (2<<POS_B): return new TailcallVarargs(stack[a], stack[a+1]);
                // ...
            }
        }
        case Lua.OP_RETURN: {
            b = i>>>23;
            switch (b) {
                case 0: return varargsOf(stack, a, top-v.narg()-a, v);
                case 1: return NONE;
                case 2: return stack[a];
                default: return varargsOf(stack, a, b-1);
            }
        }
        case Lua.OP_CLOSURE: {
            Prototype newp = p.p[i>>>14];
            LuaClosure ncl = new LuaClosure(newp, globals);
            for (int j = 0; j < newp.upvalues.length; j++) {
                if (newp.upvalues[j].instack)
                    ncl.upValues[j] = findupval(stack, newp.upvalues[j].idx, openups);
                else
                    ncl.upValues[j] = upValues[newp.upvalues[j].idx];
            }
            stack[a] = ncl; continue;
        }
        // OP_FORLOOP, OP_FORPREP, OP_TFORLOOP, OP_JMP, etc.
    }
}
```

Instructions are 32-bit packed words. Opcode occupies bits 0–5, A bits 6–13, B bits 23–31, C bits 14–22. Constants are distinguished from registers by `> 0xff` tests on B/C fields (the K-flag bit is bit 8 of the 9-bit field).

### 5.3 Call/Return Protocol

LuaJ distinguishes "single-value return" (`call()` → `LuaValue`) from "multi-value return" (`invoke()` → `Varargs`). This avoids allocating `Varargs` objects when caller doesn't need multiple returns. The interpreter tracks `top` (live stack size) for multi-return handling.

The `v` local inside `execute` holds the last multi-return result from a call; `top` is updated to `a + v.narg()` after a call whose C operand is 0 (all returns wanted). When next call packages arguments and B is also 0 (all args from top), the prior multi-return result `v` is appended as the tail via `varargsOf(stack, a+1, top-v.narg()-(a+1), v)`.

### 5.4 Stack Depth Constraint

Java's call stack bounds LuaJ's call depth. Each `LuaClosure.execute()` is a Java stack frame. With default JVM stack sizes (~512KB, ~256 frames), deeply recursive Lua code hits `StackOverflowError` and dies as `LuaError`. Cobalt (the CC:Tweaked fork) works around this by making the interpreter re-entrant — when calling another interpreted function, the outer frame hands off to a new `execute()` call that replaces the current one rather than nesting.

---

## 6. Execution Strategy 2 — LuaJC: Lua-to-Java Bytecode Compilation

### 6.1 Overview

`org.luaj.vm2.luajc.LuaJC` installs itself as the `Globals.loader`:

```java
public static void install(Globals G) {
    G.loader = instance;
}
```

When any Lua source or binary is loaded after installation, instead of producing `LuaClosure`, LuaJC:
1. Compiles source → `Prototype` via LuaC
2. Runs `ProtoInfo` SSA analysis
3. Calls `JavaGen` to emit Java bytecode via BCEL
4. Loads the resulting `.class` bytes into the JVM via `JavaLoader`
5. Returns an instance of the generated class (which extends `VarArgFunction` or fixed-arity base)

BCEL is required at compile time; no runtime dependency if pre-compiled classes are on the classpath.

### 6.2 ProtoInfo: SSA Analysis

`ProtoInfo` builds a control-flow graph from the `Prototype`:
- Identifies basic blocks
- Performs forward dataflow to compute variable liveness at each program point
- Creates `VarInfo` records tracking: which stack slot, whether assigned, whether referenced, whether invalidated at block joins (phi nodes)
- Runs `UpvalInfo` analysis: classifies each upvalue as read-only (`TYPE_LUAVALUE` field) vs read-write (`TYPE_LOCALUPVALUE = LuaValue[]` field, mutable through array indirection)

This analysis is essential for determining which JVM local slots can be `LuaValue` directly vs which need the `LuaValue[]` indirection for shared-mutable upvalue semantics.

### 6.3 JavaBuilder: Bytecode Emission

`JavaBuilder` wraps BCEL's `ClassGen` / `InstructionList`. Generated class structure:

```
class <chunk_name>  extends VarArgFunction {
    // one field per upvalue
    LuaValue   upval_readonly_N;       // read-only upvalue
    LuaValue[] upval_readwrite_N;      // read-write upvalue (mutable cell)

    static LuaValue[] CONSTANTS;       // prototype constant pool
    static { /* load constants */ }

    public Varargs onInvoke(Varargs args) {
        // JVM locals 0..maxstacksize for Lua stack slots
        // locals that become upvalues → LuaValue[] wrapper locals
        LuaValue s0, s1, ..., sN;
        LuaValue[] up_s3;              // if slot 3 captured as upvalue
        // translated instructions...
    }
}
```

Lua stack slot `i` → JVM local variable `i` of type `LuaValue`. When slot becomes an upvalue, a `LuaValue[]` local `up_sN = new LuaValue[]{sN}` is created and subsequent reads/writes go through `up_sN[0]`.

Arithmetic opcodes map to virtual method calls:
```java
// OP_ADD r3 = r1 + r2
s3 = s1.add(s2);   // emitted as INVOKEVIRTUAL LuaValue.add(LuaValue)
```

Table access:
```java
s3 = s1.get(s2);       // OP_GETTABLE
s1.set(s2, s3);        // OP_SETTABLE
```

Calls:
```java
// fixed arity
s0 = s0.call(s1, s2);
// vararg
Varargs r = s0.invoke(LuaValue.varargsOf(s1, s2, vararg_tail));
```

Control flow: BCEL branch instructions targeting `InstructionHandle` objects cached per Lua PC.

### 6.4 Upvalue Representation in Generated Code

Read-only upvalue `u`:
```java
LuaValue upval_u = this.upval_u_field;   // field access, no indirection
```

Read-write upvalue `u`:
```java
LuaValue[] upval_u = this.upval_u_field; // field is LuaValue[]
upval_u[0] = newValue;                    // write
LuaValue x = upval_u[0];                  // read
```

This array indirection is exactly how LuaClosure's `UpValue` class works — one-element arrays shared between closures. LuaJC replicates the semantics directly in generated bytecode.

### 6.5 Known LuaJC Limitations

- `string.dump()` does not work — generated classes have no Lua bytecode to dump
- `xpcall()` does not work with LuaJC-compiled functions (stack unwinding assumptions differ)
- Debug information (variable names, line numbers) is not preserved in the hot path
- Tail calls in JVM-compiled code are not tracked in debug info
- BCEL is an optional dependency; if absent, silently falls back to interpreter

---

## 7. Tail Call Handling

LuaJ uses a **trampoline** pattern since JVM lacks TCO. `OP_TAILCALL` in the interpreter returns a `TailcallVarargs` object instead of making a recursive call:

```java
class TailcallVarargs extends Varargs {
    LuaFunction func;
    Varargs args;
    Varargs result;   // null until eval()

    public Varargs eval() {
        while (result == null) {
            Varargs r = func.onInvoke(args);
            if (r instanceof TailcallVarargs) {
                TailcallVarargs t = (TailcallVarargs) r;
                func = t.func;
                args = t.args;
            } else {
                result = r;
                func = null;
                args = null;
            }
        }
        return result;
    }
}
```

The trampoline loop runs in `eval()`, called lazily when a caller actually accesses `narg()` or `arg(i)`. For pure tail-call chains (no intermediate consumers), this achieves constant stack depth. However, the trampoline does not help with non-tail recursion, and `debug` library cannot track tail call frames.

---

## 8. Upvalues: Open/Closed Lifecycle

`UpValue` is a mutable cell shared across closures:

```java
class UpValue {
    LuaValue[] array;  // points at stack during open phase
    int        index;  // position in array

    LuaValue getValue() { return array[index]; }
    void setValue(LuaValue v) { array[index] = v; }

    void close() {
        LuaValue[] old = array;
        array = new LuaValue[]{ old[index] };
        old[index] = null;  // release stack reference
        index = 0;
    }
}
```

While a function is executing, an upvalue in the `openups` list points directly into the executing function's `stack[]` array. When the function returns or a `OP_JMP` with upvalue-closing operand fires, `close()` is called: the current value is captured into a private one-element array, and the stack slot is nulled.

Multiple closures created in the same scope share the same `UpValue` object (found via `findupval()` by stack index). Mutations through any closure are immediately visible to all others.

---

## 9. Coroutines: One Java Thread Per Coroutine

### 9.1 State Machine

`LuaThread` wraps a `State` inner class with five states:

```java
static final int STATUS_INITIAL   = 0;
static final int STATUS_SUSPENDED = 1;
static final int STATUS_RUNNING   = 2;
static final int STATUS_NORMAL    = 3;
static final int STATUS_DEAD      = 4;
```

### 9.2 Resume/Yield Synchronization

Both sides use `synchronized(state)` + `wait()` / `notify()`:

**Resume side** (`lua_resume()`):
```java
synchronized(state) {
    if (status == STATUS_INITIAL) {
        // spawn new Java thread, inject args
        new Thread(state).start();
    } else {
        // wake suspended coroutine
        state.args = args;
        state.notify();
    }
    // block until coroutine yields or dies
    while (state.status == STATUS_RUNNING)
        state.wait();
    return state.yieldedValues;
}
```

**Yield side** (`lua_yield()`):
```java
synchronized(state) {
    state.yieldedValues = args;
    state.status = STATUS_SUSPENDED;
    state.notify();               // wake the resumer
    while (state.status == STATUS_SUSPENDED)
        state.wait(thread_orphan_check_interval);  // 5000ms default
    // check weak ref — if orphaned, throw OrphanedThread
}
```

### 9.3 Thread Safety Model

Each `Globals` instance tracks `globals.running` (the active `LuaThread`). On resume, `globals.running` is updated to the resuming coroutine; on yield, it reverts. This design mandates **one `Globals` instance per OS thread** — sharing globals across threads is unsupported.

Shared-across-all-globals static metatables (for `Number`, `String`, `Thread`, `Function`, `Boolean`, `Nil`) must not be mutated after Lua code starts running.

### 9.4 Orphan Detection

During yield, `state.wait(5000)` wakes every 5 seconds. The coroutine checks a `WeakReference` to itself; if the reference was cleared (nothing holds the `LuaThread`), it throws `OrphanedThread` (an `Error`) to terminate the thread. This prevents ghost threads accumulating forever, at the cost of ~5s latency for collection.

### 9.5 Performance Cost: The Thread-Per-Coroutine Problem

This design is the most-criticized aspect of LuaJ. Real-world consequences:

- **Memory**: Each Java thread consumes ~256KB–1MB stack by default
- **Scheduling**: OS scheduler must manage all coroutine threads; context switches are expensive
- **Scale**: The SwitchCraft server (Minecraft, ComputerCraft) accumulated 250,000 live threads at 50 creations/sec before collapsing
- **Synchronization overhead**: Every `resume`/`yield` round-trip does two `wait()`/`notify()` calls — measurable overhead even at modest coroutine counts

**Cobalt's fix** (CC: Tweaked fork of LuaJ 2): exception-based yielding. When all frames on the Lua call stack are interpreted `LuaClosure` or designated-resumable Java functions, `yield()` throws a `LuaYield` exception that unwinds back to the top-level interpreter. The interpreter saves the unwound state and switches coroutines. No OS thread involved. Reduced SwitchCraft from 2,000 coroutine threads to 50 for 250 computers. Later Cobalt versions eliminated threads entirely for coroutines via bytecode rewriting that transforms interpreted functions into state machines.

---

## 10. Metatables

### 10.1 Metatag Constants

Defined as `LuaString` statics on `LuaValue`:

```java
INDEX NEWINDEX CALL
ADD SUB MUL DIV MOD POW UNM
LEN EQ LT LE
TOSTRING CONCAT
```

### 10.2 Dispatch Mechanism

Arithmetic: `LuaValue.arithmt(LuaString tag, LuaValue rhs)` — looks up tag in `this.getmetatable()`, falls back to `rhs.getmetatable()`. If neither has it, throws `LuaError`.

Comparison: `__lt` fallback for `__le` is implemented — if no `__le` metamethod, LuaJ evaluates `not (b < a)` using `__lt`.

`__index`: `gettable(LuaValue table, LuaValue key)` on `LuaValue`. Default implementation: if `rawget()` returns non-nil, done. Else look up `__index` in metatable; if it's a function, call it; if it's a table, recurse. Cycle limit via depth counter prevents infinite chains.

`__call`: `callmt()` in `LuaValue` retrieves `__call` from the metatable and calls it with `this` prepended as first argument.

### 10.3 Per-Type Shared Metatables

`LuaNumber.s_metatable`, `LuaString.s_metatable`, `LuaBoolean.s_metatable` — static fields, single instance across entire JVM process. All `LuaInteger` / `LuaDouble` / `LuaString` / `LuaBoolean` instances share the same metatable. Safe only because they're immutable post-startup.

---

## 11. Loading Pipeline

### 11.1 Globals Loader Chain

`Globals` defines three interface slots:

```java
public Compiler  compiler;    // source → Prototype
public Undumper  undumper;    // binary chunk → Prototype
public Loader    loader;      // Prototype → LuaFunction
```

`loadPrototype(InputStream, chunkname)`: tries binary signature `\033Lua` first (undumper), falls back to compiler. The default `loader` wraps the prototype in a `LuaClosure`. LuaJC replaces `loader` with bytecode-compiling logic.

### 11.2 Binary Chunk Format

`LoadState` reads Lua 5.2 binary format. Header (12 bytes):
- Signature: `{ 0x1B, 'L', 'u', 'a' }`
- Version: `0x52` (Lua 5.2)
- Format indicator
- Endianness byte
- Sizes: `sizeof(int)`, `sizeof(size_t)`, `sizeof(Instruction)`, `sizeof(lua_Number)`
- Number format type (float/integer/32-bit patch)

After header, recursive prototype tree is deserialized: `code[]`, `k[]`, `p[]`, `lineinfo[]`, `locvars[]`, `upvalues[]`.

---

## 12. Java Interoperability (luajava)

The JSE-only `luajava` library (`org.luaj.vm2.lib.jse.LuajavaLib`) exposes Java reflection to Lua:

```lua
jframe = luajava.bindClass("javax.swing.JFrame")
frame  = luajava.newInstance("javax.swing.JFrame", "Hello")
```

`CoerceJavaToLua` converts Java objects to `LuaValue`:
- Primitives → `LuaInteger` / `LuaDouble`
- `String` → `LuaString`
- Arrays → `JavaArray` (a `LuaUserdata` subclass)
- `Class` → `JavaClass`
- Everything else → `JavaInstance`

`CoerceLuaToJava` does the reverse for method arguments, using cached `Coercion` strategies per target type. Method overload resolution uses type coercion scoring.

`JavaInstance` wraps an arbitrary `Object` in `LuaUserdata`. Method calls go through `__index` metatable dispatch: `JavaInstance.get(key)` looks up Java methods by name via reflection.

---

## 13. Error Handling

`LuaError extends RuntimeException` (unchecked). All type errors, arithmetic on non-numbers, missing metatag lookups, etc. throw `LuaError`.

`pcall` / `xpcall` in `BaseLib` use `try { ... } catch (LuaError e) { ... } catch (Exception e) { ... }`. Java exceptions from luajava or C extensions are caught and converted to Lua error objects.

`xpcall` stores the error handler in `globals.running.errorfunc` (per-thread), runs the protected call, then restores the handler in `finally`.

Stack trace: `LuaError` carries a `level` field used to trim the traceback to the appropriate Lua call depth. The debug library hooks (`onCall`, `onReturn`, `onLine`, `onInstruction`) attach to `Globals.debuglib` when loaded.

---

## 14. Performance Characteristics

### 14.1 Interpreted Mode

From LuaJ README benchmarks:
- `binarytrees-15`: LuaJ 12.8s, C Lua 17.6s (LuaJ faster here due to JIT warming up `execute()`)
- General: competitive with C Lua after JVM JIT warms up the interpreter loop

The single `execute()` method with a giant `switch` statement is a good target for JVM JIT (C2) because the JIT can inline the method, specialize the switch, and hoist type checks.

### 14.2 LuaJC (JIT Compiled) Mode

LuaJC-compiled code outperforms interpreted mode because:
- No bytecode dispatch overhead — Lua instructions compile to direct JVM bytecode
- JVM JIT can inline `LuaValue.add()` / `get()` / `set()` call sites
- Local variables are real JVM locals (not `LuaValue[]` slot array), enabling JIT register allocation

Per README, LuaJC "executes faster than C-based Lua in some cases" on compute-heavy benchmarks.

### 14.3 Allocation Pressure

Every Lua value is a heap-allocated Java object. Hot paths create constant GC pressure:
- Integer arithmetic: `LuaInteger.valueOf()` returns pooled for [-256, 255], allocates outside
- `varargsOf()` for multi-return: `PairVarargs` or `ArrayVarargs` allocation per return site
- String concatenation: new `byte[]` + new `LuaString` per concat
- Closures: `new LuaClosure(...)` per `OP_CLOSURE`

The integer cache (512 entries, range [-256, 255]) and recent-string cache (128 entries, ≤32 bytes) are the main GC reduction mechanisms. No general escape-analysis-based optimization is done at the LuaJ layer; that's left to the JVM JIT.

### 14.4 Coroutine Overhead

Thread-per-coroutine cost:
- Thread creation: ~1ms and ~256KB–1MB stack per coroutine
- Each `resume`/`yield`: two OS-level synchronization operations
- Impractical above ~1000 concurrent live coroutines on typical JVM settings

---

## 15. Lessons for a Scala/JVM Lua Implementation

### 15.1 Value Representation

LuaJ's full-object-per-value design works but pays GC cost. Scala alternatives:

1. **Sealed trait hierarchy** — equivalent to LuaJ's class hierarchy; exhaustive pattern matching gives type-safety and good JIT devirtualization
2. **Tagged union via Long** — encode nil/bool/int as bit patterns in a `Long`, use `AnyRef` slot for strings/tables/functions; avoids allocation for nil/bool/integer. Requires careful null safety.
3. **Scala `@specialized`** or **inline classes** (`opaque type` / `AnyVal`) — limited; Lua values are not single-primitive
4. **Integer pool via companion object** — replicate LuaJ's 512-entry cache; trivial to implement

Recommendation: sealed trait hierarchy for correctness, then profile. The JVM JIT deoptimizes virtual dispatch well when the hot type is monomorphic at a call site.

### 15.2 Varargs Protocol

LuaJ's `Varargs` design is sound. Key insights for Scala:
- Extend your value type so single values are their own varargs (eliminates wrapping allocations for most returns)
- Use case class `PairVarargs(head: LuaValue, tail: Varargs)` for two-element returns
- Use `ArrayVarargs(arr: Array[LuaValue], tail: Varargs)` for N-element returns
- Keep `NONE` as a singleton for zero-return

### 15.3 Table Implementation

LuaJ's split array+hash design is correct and performant. Port directly:
- `Array[LuaValue]` for integer keys [1..N]
- `Array[Slot]` for hash; Slot is sealed trait with NormalEntry / IntKeyEntry variants
- Resize: rehash on load > 0.75

### 15.4 Interpreter Loop

Scala cannot write the same C-style while loop with `continue`. Options:
- `while(true)` with `if/else` chains or `@scala.annotation.switch` on opcode
- Put interpreter in a `@tailrec`-free loop (recursive Scala won't work)
- Extract method per opcode family for JIT inlining

The `switch` on the 6-bit opcode is the hot path; keep it in one method so JVM JIT can see and optimize the entire dispatch.

### 15.5 UpValues

`UpValue` as a mutable wrapper is the correct design. In Scala:
```scala
final class UpValue(private var array: Array[LuaValue], private var index: Int) {
  def get: LuaValue = array(index)
  def set(v: LuaValue): Unit = array(index) = v
  def close(): Unit = {
    val v = array(index)
    array = Array(v)
    index = 0
  }
}
```

### 15.6 Coroutines: Do Not Use Thread-Per-Coroutine

LuaJ's design is demonstrably broken at scale. For Scala on JVM:

**Option A: JVM 21+ Virtual Threads (Project Loom)**. Each coroutine gets a virtual thread; the JVM handles scheduling. No OS thread overhead; millions of virtual threads are practical. `synchronized` / `wait` / `notify` work on virtual threads. This is the direct drop-in replacement for LuaJ's design.

**Option B: Continuation-Passing / Exception-based unwind** (Cobalt approach). Define a `LuaYield` throwable; yield throws it; the top-level interpreter loop catches it, saves state, switches coroutines. Requires all call-stack frames to be resumable — the Lua interpreter loop is by definition resumable if you save PC + stack.

**Option C: Coroutine as explicit state machine**. Compile each `LuaClosure.execute()` as a state machine that saves PC + locals on yield. This is what Cobalt's bytecode rewriting does, and what Kotlin coroutines do. More complex but zero allocation on the hot path.

Loom virtual threads (Option A) are the pragmatic choice for a new Scala/JVM implementation targeting JVM 21+.

### 15.7 Tail Calls

LuaJ's `TailcallVarargs` trampoline is the correct JVM approach for `OP_TAILCALL`. Port it directly. Note: tail calls are distinct from Scala `@tailrec` — they involve general mutual recursion, not just self-recursion. The trampoline generalizes to any tail call.

### 15.8 String Representation

Use `Array[Byte]` internally, not `java.lang.String`. Lua strings are byte strings. Provide `def toJavaString: String` via UTF-8 decode only for Java interop. Cache short strings (< 32 bytes) in a recent-strings cache indexed by content hash.

### 15.9 Number Representation

Keep the `LuaNumber` → `LuaInteger(Int)` / `LuaDouble(Double)` split. The `LuaDouble.valueOf` factory that returns `LuaInteger` when `d.toInt.toDouble == d` avoids redundant boxing. The 512-entry integer cache covers the vast majority of Lua programs.

### 15.10 LuaJC-style Compilation for Scala

If building a JIT tier: Scala has Graal Truffle available. A Truffle AST interpreter can self-compile Lua via Partial Evaluation without writing a BCEL back-end. The Graal compiler applies escape analysis, inlining, and unboxing that BCEL cannot. However, Truffle adds significant complexity. For a straightforward port, a Scala-level interpreter is the right first step; add Truffle or LuaJC-style compilation later.

---

## 16. Summary Table

| Feature | LuaJ Approach | JVM Cost | Scala Recommendation |
|---------|--------------|----------|----------------------|
| Value representation | Class hierarchy, all heap | Every value allocated | Sealed trait + integer pool; Loom doesn't help here |
| Integers | LuaInteger(int), pool [-256,255] | Cache miss = alloc | Same pool design |
| Doubles | LuaDouble(double), no pool | One alloc per new double | Same; factory checks if int-representable |
| Strings | byte[], short string cache | Shared backing, low GC | Same byte[] design |
| Tables | Array + open-hash Slot[] | Resize on load | Port directly |
| Varargs | Varargs hierarchy, LuaValue extends Varargs | PairVarargs allocations | Same design |
| Interpreter | while(true)+switch in execute() | JIT-friendly big method | Same in Scala |
| Upvalues | UpValue cell, open→close lifecycle | One alloc per captured var | Port directly |
| Coroutines | One Java Thread per coroutine | Catastrophic at scale | JVM 21 virtual threads OR exception-unwind |
| Tail calls | TailcallVarargs trampoline | One alloc per tail call | Port directly |
| Error handling | LuaError extends RuntimeException | Normal JVM exception cost | Same |
| Java interop | Reflection-based, coercion registry | Method dispatch via reflection | Scala reflection or direct Java interop |
| LuaJC | BCEL bytecode generation | Complexity + BCEL dep | Consider Truffle/Graal for future tier |

---

## Sources

- [LuaJ GitHub repository](https://github.com/luaj/luaj)
- [LuaValue.java source](https://github.com/luaj/luaj/blob/master/src/core/org/luaj/vm2/LuaValue.java)
- [LuaClosure.java source](https://github.com/luaj/luaj/blob/master/src/core/org/luaj/vm2/LuaClosure.java)
- [LuaJC API documentation](http://luaj.org/luaj/3.0/api/org/luaj/vm2/luajc/LuaJC.html)
- [Cobalt: re-entrant LuaJ fork by CC:Tweaked](https://github.com/cc-tweaked/Cobalt)
- [SquidDev: Efficient coroutines by rewriting bytecode](https://squiddev.cc/2023/03/29/coroutines-and-bytecode.html)
- [SquidDev: Tweaking the internals of CC:Tweaked](https://www.squiddev.cc/2019/03/08/tweaking-cc-tweaked.html)
- [LuaJ class hierarchy (Javadoc)](https://luaj.sourceforge.net/api/2.0/overview-tree.html)
- [LuaTable Javadoc](http://luaj.org/luaj/3.0/api/org/luaj/vm2/LuaTable.html)
- [LuaValue Javadoc](http://luaj.org/luaj/3.0/api/org/luaj/vm2/LuaValue.html)
- [CoerceJavaToLua Javadoc](http://luaj.org/luaj/3.0/api/org/luaj/vm2/lib/jse/CoerceJavaToLua.html)
- [CCTweaks-Lua: Lua runtime comparison](https://github.com/SquidDev-CC/CCTweaks-Lua/wiki/Lua-runtimes)
