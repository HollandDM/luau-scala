# Luau-Scala: Master Architecture Synthesis

**Status**: Reference design for new Luau runtime on JVM (Scala 3, JDK 21+)
**Date**: 2026-06-09
**Sources**: See Section 7

---

## 1. Existing Runtime Landscape

| Runtime | Impl Lang | Lua Version | VM Model | Coroutine Strategy | GC Strategy | Maturity | License |
|---|---|---|---|---|---|---|---|
| **Luau (official)** | C++ | Luau (Lua 5.1 derived) | Register-based bytecode, computed-goto dispatch, inline caches, optional native JIT (x64/arm64) | Stackful; `lua_State` per coroutine, linked `CallInfo` list, OS-stack yield via `longjmp` | Incremental tri-color mark-sweep; PID-paced; paged sweeper; weak table support | Production (Roblox) | MIT |
| **PUC-Rio Lua 5.1** | C | 5.1 | Register-based bytecode, switch dispatch | Stackful; `lua_State` per thread, `longjmp` yield | Stop-world incremental mark-sweep | Reference impl | MIT |
| **PUC-Rio Lua 5.4** | C | 5.4 | Register-based, switch, generational GC flag | Stackful; same `longjmp` model | Generational + incremental | Reference impl | MIT |
| **LuaJ** | Java | 5.2 | Interpreted bytecode + optional JVM-bytecode compile (BCEL) | One OS thread per coroutine; `WeakReference` + `OrphanedThread` for GC | Host JVM heap; no custom GC | Abandoned (2019) | MIT |
| **Rembulan** | Java | 5.3 | Compiles Lua → JVM bytecode; state-machine IR with type inference | Exception-based state machine; `CallPausedException` for yield | Host JVM heap | Abandoned (~2018) | Apache 2.0 |
| **Luna** | Java | 5.3 | Fork of Rembulan; same Lua→JVM-bytecode approach | Same exception/state-machine as Rembulan | Host JVM heap | Maintenance only | Apache 2.0 |
| **MoonSharp** | C# | 5.2 | Interpreted bytecode; CLR object model | Coroutines as C# iterators (`IEnumerable`); no OS threads | CLR GC | Abandoned (2016) | BSD-3 |
| **gopher-lua** | Go | 5.1 | Interpreted bytecode | One goroutine per coroutine; channel rendezvous | Go GC | Active | MIT |
| **Piccolo** | Rust | 5.4-ish | Stackless trampoline; `Sequence`/`Executor` poll loop | Stackless continuations; no OS threads needed | `gc-arena` crate; cycle-detecting incremental arena GC | Experimental | MIT |
| **Fengari** | JS | 5.3 | Near-literal C→JS port | JS generator functions; generator-based yield | JS GC | Abandoned | MIT |

---

## 2. Most Relevant Prior Art (Ranked for Scala/JVM Target)

### #1 — LuaJ (Java, same JVM)

**Why**: Direct ancestor archetype. Proves: `LuaValue` sealed class hierarchy works on JVM; `Varargs` abstraction handles multi-return cleanly; `LuaTable` hybrid array+hash impl is correct; `LuaThread` + one OS thread per coroutine is simplest correct coroutine model. Source: [github.com/luaj/luaj](https://github.com/luaj/luaj).

**Lessons learned**:
- One-thread-per-coroutine works but scales badly (OS thread stack = ~512 KB each; 10k coroutines = 5 GB stack).
- JVM-bytecode compilation path (via BCEL) is complex; skip for MVP.
- `LuaValue` as abstract class + subclasses incurs virtual dispatch overhead — Scala 3 `sealed trait` + `@specialized` or `opaque type` can do better.
- No Luau-specific types: no `vector`, no `buffer`, no `integer`, no class system.

### #2 — Rembulan / Luna (Java 5.3, JVM)

**Why**: Solves coroutine-on-JVM without OS threads. Exception-based state machine (`CallPausedException`) + continuation resume is the pattern for pre-Loom JVMs. Also demonstrates that compiling Lua directly to JVM bytecode (bypassing Lua bytecode) is viable with type inference.

**Lessons learned**:
- Exception-based coroutine overhead is real (~microseconds per yield). Loom virtual threads are cheaper.
- Compiling to JVM bytecode is complex IR work — defer post-MVP.
- CPU accounting via interrupt counter is clean; reuse pattern.
- Source: [github.com/mjanicek/rembulan](https://github.com/mjanicek/rembulan), [github.com/kroepke/luna](https://github.com/kroepke/luna).

### #3 — Piccolo (Rust, stackless)

**Why**: Best design proof that a Lua VM can implement coroutines without OS threads AND without exception tricks. `Sequence`/`Executor` poll model maps cleanly to Scala `Future`/`IO` or custom continuation type. Relevant if Loom is unavailable or undesirable.

**Lessons learned**:
- Stackless = complex user-facing API for embedding Scala callbacks that call Lua that yield. Loom is simpler if JDK 21+ guaranteed.
- `gc-arena` branding technique has no direct JVM equivalent — host GC handles it.

### #4 — gopher-lua (Go)

**Why**: Shows `LValue` interface + goroutine-per-coroutine as idiomatic host-language mapping. Closest philosophical match to Scala trait dispatch + Loom virtual thread per coroutine.

### #5 — MoonSharp (C#, CLR)

**Why**: CLR ≈ JVM. C# iterator coroutines = ergonomic but limited (can't yield across C# stack frames into Lua). Demonstrates that `IEnumerable`-style coroutines break for deeply nested yield-from-metamethod scenarios.

---

## 3. Recommended Architecture for luau-scala

### 3.1 Module Breakdown

```
luau-scala/
├── lexer/        -- LuauLexer: tokenizes Luau source, handles UTF-8, string escapes, `//` floor-div, type annotation tokens
├── parser/       -- LuauParser: recursive-descent → AST; handles type annotations as optional nodes
├── ast/          -- LuauAST: sealed ADT nodes (Expr, Stat, TypeExpr); mirrors Luau grammar exactly
├── compiler/     -- LuauCompiler: AST → LuauBytecode; register allocator, upvalue analysis, constant folding
├── bytecode/     -- LuauBytecode: instruction decoder/encoder; Bytecode.h-compatible; version 3–11 support
├── vm/           -- LuauVM: interpreter loop, CallInfo stack, dispatch, inline cache stubs, interrupt hook
├── stdlib/       -- LuauStdlib: base, math, table, string, coroutine, bit32, utf8, os, debug, buffer, vector
├── types/        -- (optional) LuauTypeChecker: gradual type checker; Analysis pipeline port; separate from VM
└── embed/        -- LuauState: public embedding API; Java-friendly wrapper; sandbox config
```

Key dependency direction: `lexer → parser → ast → compiler → bytecode ← vm`. `types` depends on `ast` only, never on `vm`.

### 3.2 Value Model

Luau has 14 types (from `lua.h`): nil, boolean, lightuserdata, number (double), integer (int64), vector (3×float or 4×float), string, table, function, userdata, thread, buffer, class, object.

**Recommended Scala encoding**:

```scala
// Core sealed hierarchy
sealed trait LuauValue

case object LuaNil                                          extends LuauValue
case class  LuaBoolean(v: Boolean)                         extends LuauValue
case class  LuaInt(v: Long)                                extends LuauValue   // LUA_TINTEGER
case class  LuaFloat(v: Double)                            extends LuauValue   // LUA_TNUMBER
case class  LuaVector(x: Float, y: Float, z: Float)        extends LuauValue   // LUA_TVECTOR (3-wide default)
case class  LuaString(s: String)                           extends LuauValue   // interned; immutable
case class  LuaTable(t: LuauTable)                         extends LuauValue
case class  LuaFunction(f: LuauClosure)                    extends LuauValue   // Lua closure OR native fn
case class  LuaUserdata(u: AnyRef, tag: Int)               extends LuauValue
case class  LuaLightUserdata(p: AnyRef)                    extends LuauValue
case class  LuaThread(co: LuauCoroutine)                   extends LuauValue
case class  LuaBuffer(b: Array[Byte])                      extends LuauValue
```

**Why not NaN-boxing on JVM**: NaN-boxing exploits 64-bit IEEE 754 spare bits to pack type tag + pointer in one `long`. On JVM: pointers are object refs (not raw ints); compressed oops make pointer arithmetic unsafe; JIT may deoptimize on bit-cast tricks. Use sealed hierarchy + JIT polymorphic inline cache instead.

**Stack representation**: `Array[LuauValue]` per call frame, indexed by register number (0–254 per proto). Pre-allocate per `CallInfo`. Avoids boxing overhead for primitive `LuaInt`/`LuaFloat` if Scala value classes used carefully (or Scala 3 opaque types for hot paths).

**String interning**: `ConcurrentHashMap[String, LuaString]` at `LuauState` level. Luau strings are immutable and compared by identity in hash/eq — intern on creation.

### 3.3 Dispatch Strategy

Luau has 62+ opcodes (Bytecode.h; version 6 target). Dispatch options on JVM:

1. **`while(true) + tableswitch`** — JVM compiles `switch` on int → `tableswitch` bytecode (O(1) jump table). Best starting point. Matches Luau C++ `switch` dispatch exactly.
2. **Method dispatch table** — `Array[Instruction => Unit]`; one closure per opcode. More OO but indirection overhead.
3. **Ahead-of-time compilation to JVM bytecode** (Rembulan strategy) — complex; defer.

**Recommended**: `tableswitch` in a `@tailrec` loop within `LuauVM.execute()`. Single-method main loop; Scala compiler will keep JIT-friendly.

**Inline cache**: Luau C++ uses per-instruction cache slots (feedback vectors). For MVP: skip inline caches, use direct `LuauTable.rawget` dispatch. Post-MVP: `CacheSlot` array parallel to instruction array; store last-seen table shape hash + result offset.

**FastCall**: Luau `LOP_FASTCALL*` family bypasses full call setup for ~30 builtin fns. Implement as direct dispatch in `when` arm: detect builtin function identity, call native Scala fn directly without pushing `CallInfo`.

### 3.4 Coroutine Strategy — Loom Virtual Threads

JDK 21 (GA Sept 2023) ships Virtual Threads (JEP 444) as production feature. JDK 24+ fixes synchronized-block pinning.

**Model**: One `VirtualThread` per `LuauCoroutine`. Yield = `SynchronousQueue.put(values)` on coroutine thread + block; Resume = `SynchronousQueue.put(resumeValues)` on caller thread + block on coroutine's result queue.

```scala
class LuauCoroutine(proto: LuauProto, state: LuauState):
  private val resumeChannel = new SynchronousQueue[Array[LuauValue]]
  private val yieldChannel  = new SynchronousQueue[Array[LuauValue]]
  private var vthread: Thread = _

  def resume(args: Array[LuauValue]): Array[LuauValue] =
    if vthread == null then
      vthread = Thread.ofVirtual().start(() => runBody(args))
    else
      resumeChannel.put(args)
    yieldChannel.take()  // block until coroutine yields or returns

  // Called from within coroutine execution context
  def yield(values: Array[LuauValue]): Array[LuauValue] =
    yieldChannel.put(values)     // signal caller
    resumeChannel.take()         // block until resumed
```

**Why Loom over alternatives**:
- LuaJ one-OS-thread: ~512 KB stack each; 10k coroutines = 5 GB. Loom virtual thread stack = few KB, grows dynamically.
- Rembulan exception state-machine: correct but complex; every yield point requires manual continuation serialization.
- Piccolo stackless: most efficient CPU-wise but requires redesigning entire call model.
- Loom: coroutine yield/resume maps 1:1 to blocking channel ops; JVM parks virtual thread on carrier, minimal overhead. JDK 24+ removes synchronized pinning constraint.

**Limitation**: Loom still pins on JNI. Keep Scala native interop out of coroutine hot path.

### 3.5 GC Strategy

No custom GC needed. Host JVM heap handles all `LuauValue` objects.

**Upvalues**: Closed upvalues = `Array[LuauValue](1)` wrapped in `UpvalueCell`. Open upvalues point into call-frame register array. On `LOP_CLOSEUPVALS`: copy register value into `UpvalueCell`, redirect all refs. Standard Lua upvalue semantics, no GC tricks needed.

**Weak tables** (`__mode = "k"`, `"v"`, `"kv"`): Use `WeakHashMap[LuaString, LuauValue]` for weak-key tables; `WeakReference[LuauValue]` array for weak-value tables. JVM GC handles reclamation; implement Lua-compatible `next()` traversal that skips cleared refs.

**String GC**: String intern table uses `WeakReference<LuaString>` values so unreferenced interned strings can be collected.

**Buffer type**: `Array[Byte]` — JVM manages lifecycle.

### 3.6 Table Implementation

Luau table = hybrid array-part + hash-part (same as PUC-Lua 5.1).

```scala
class LuauTable:
  private var array: Array[LuauValue]  = Array.empty   // index 1..n (0-indexed internally)
  private var hash:  HashMap[LuauValue, LuauValue] = _  // for non-integer or out-of-range keys
  var metatable: LuauTable = _
  var flags: Int = 0          // cached absent-metamethod bitmask (mirrors ltable.h invalidateTMcache)
  var readonly: Boolean = false  // table.freeze() support
```

**Array part heuristic** (match `luaH_resize` from ltable.cpp): when integer key `k` satisfies `1 <= k <= array.length * 2`, store in array part. On resize: count keys fitting array threshold, pick size where ≥ 50% slots occupied.

**Hash part**: `java.util.HashMap[LuauValue, LuauValue]` for MVP. Post-MVP: open-addressing with Robin Hood hashing (matches Luau C++ `LuaNode` chained hash). `LuauValue.hashCode()` must be consistent: `LuaFloat(1.0).hashCode == LuaInt(1L).hashCode` (Lua 1.0 == 1 for table key purposes).

**Metamethod cache** (`flags` bitmask): bit `i` set = metamethod `i` absent. Clear on `setmetatable()`. Avoids repeated `rawget` on metatable for absent `__index` etc.

**`table.freeze()`**: Set `readonly = true`; any write throws `LuauRuntimeError("attempt to modify a readonly table")`.

---

## 4. Scope Decision: MVP Cut Line

### Bytecode: Compile from Source vs Consume Official Luau Bytecode

Two valid approaches:

**Option A — Implement full pipeline (lexer→parser→compiler→bytecode→VM)**
- Full control; no native dependency.
- ~6–9 months to match Luau compiler quality (constant folding, upvalue analysis, register allocation).
- Risk: compiler bugs diverge from official semantics.

**Option B — Consume official Luau bytecode (emit via luau-lang/luau C++ compiler)**
- Ship official `luauc` as sidecar binary; call via `ProcessBuilder` or JNI wrapper (`mlua`/`mluau` crates as model).
- VM only needs to decode + execute; no compiler to write.
- Risk: JNI/subprocess overhead; version coupling to official release.
- Benefit: get Luau's aggressive constant folding, `FASTCALL` emission, and type-annotated bytecode for free.

**Recommendation**: **Option B for MVP, Option A long-term.**

Use the official `luauc` compiler (invoked as subprocess or via `ProcessBuilder` with pre-compiled binary) to emit bytecode. Implement only the VM + stdlib in Scala for MVP. Bytecode decoder (`LuauBytecodeDecoder`) reads LBC format (version 3–11). This de-risks the largest unknown (compiler correctness) and lets the team focus on VM + JVM integration.

### Type Checking

**Do NOT implement a type checker for MVP.** Luau's Analysis pipeline (`Analysis/` dir in luau-lang/luau) is ~50 KLOC C++. The type system is actively evolving (new `Luau::TypeSolver`, bidirectional inference in 2025). Porting is a 12+ month effort with high drift risk.

**For MVP**: accept `--!nocheck` scripts. Expose a flag `luau.typecheck = false` in `LuauState` config. Post-1.0: either JNI-bridge to official `Luau::Frontend`, or implement a minimal read-only type annotator for IDE tooling.

### MVP Cut Line Summary

| Component | MVP | Post-MVP |
|---|---|---|
| Lexer | Yes (needed for error messages + future compiler) | — |
| Parser (AST) | Yes | — |
| Compiler (AST→bytecode) | No — use official `luauc` | Phase 2 |
| Bytecode decoder (LBC v3–11) | Yes | — |
| VM interpreter (tableswitch loop) | Yes | — |
| Coroutines (Loom) | Yes | — |
| Full stdlib (base/math/table/string/coroutine/bit32/utf8/buffer/vector) | Yes | — |
| `os` / `io` libs | Partial (os.clock, os.time; no filesystem by default) | Phase 2 |
| Inline caches / feedback vectors | No | Phase 2 |
| JVM-bytecode compilation | No | Phase 3 |
| Type checker | No | Phase 3+ |
| Native JIT | No | Never (JVM JIT handles it) |
| `debug` lib (full) | Partial (`debug.traceback` only) | Phase 2 |

---

## 5. Hardest Problems & Risks

### 5.1 Luau-Specific Risks (vs Vanilla Lua 5.1)

**Integer type (`LUA_TINTEGER` / `lua_Integer = int64`)**: Luau adds a distinct integer type alongside `double`. Coercion rules: integer + integer = integer (no float promotion); integer division (`//`) always integer. JVM `Long` maps cleanly, but all arithmetic dispatch must handle mixed int/float. Every opcode variant (`LOP_ADD`, `LOP_ADDK`) must branch on both operand types. Risk: coercion bugs under edge cases (integer overflow, `math.maxinteger`, modulo sign semantics).

**Vector type (3×float / 4×float)**: `LUA_TVECTOR` is a value type in C++ TValue (stored inline, no heap alloc). On JVM, `LuaVector(x,y,z)` is a heap-allocated case class object. For game-code workloads doing millions of vector ops per frame, this is a significant allocation pressure. **Mitigation**: Scala 3 value classes (`opaque type` + AnyVal wrapper) for 3-float vector — can be stack-allocated by Escape Analysis if short-lived. Alternative: encode vector as 3 parallel `float` register arrays (struct-of-arrays) for hot paths. Risk: EA failure → GC pressure.

**`table.freeze()` semantics**: Frozen tables in Luau are deeply immutable at the VM level (not just advisory). Any write = runtime error. Implementing `readonly` flag is easy; the risk is forgetting to check it in SETTABLE, SETTABLEKS, SETTABLEN, SETGLOBAL, NEWKEY paths. Miss one → security sandbox escape.

**Class system (`LOP_NEWCLASSMEMBER`, `LOP_CMPPROTO`, `LUA_TCLASS`, `LUA_TOBJECT`)**: Bytecode.h v10+ adds class opcodes. These are Luau-specific extensions beyond Lua 5.1 ancestry. Limited public documentation. Risk: undocumented semantics; need to test against reference VM extensively.

**`LOP_FASTCALL*` family**: 5 variants. Requires mapping Luau's built-in function IDs (`LBF_*` constants from Bytecode.h) to Scala implementations. Miss a mapping → fall through to slow path (wrong semantics if fallback not tested). The FASTCALL pattern: `FASTCALL` followed immediately by `CALL` as fallback; VM must skip `CALL` when fastcall succeeds.

**`LOP_GETIMPORT` with AUX encoding (1–3 level import chain)**: Roblox-specific module import path encoded as chain of constant indices. Must decode AUX word correctly: bits [23:0] = first key index; bits [31:24] used for nesting level. Incorrect decode = silent wrong lookup.

**Bytecode version negotiation (v3–v11)**: Luau adds fields per version increment. LBC decoder must handle version-conditional parsing: type annotations (v1+), `linedefined` (v2+), native flags (v4+), class opcodes (v10+). Parsing wrong field offset for a version = corrupted proto state.

### 5.2 JVM Hosting Risks

**Loom pinning on synchronized blocks (JDK 21–23)**: If any stdlib function or user callback holds a `synchronized` monitor while calling Lua code that yields, the carrier thread pins. With JDK 21 this can exhaust the carrier pool under high coroutine concurrency. **Mitigation**: use `java.util.concurrent.locks.ReentrantLock` instead of `synchronized` everywhere in VM/stdlib; upgrade requirement to JDK 24+ recommended.

**Stack overflow in deep Lua call chains**: Lua allows recursive calls to arbitrary depth. JVM stack depth is limited (~500–1000 frames depending on frame size). Lua's `CallInfo` model is explicit (heap-allocated linked list) — if implemented correctly as a heap list in Scala, deep recursion uses heap not JVM stack. Risk: accidentally using Scala recursion in interpreter loop (e.g., implementing `pcall` as a recursive Scala call) → JVM StackOverflowError instead of Lua-level error.

**`pcall`/`xpcall` + `coroutine.yield` interaction**: In Luau 2025, `pcall` inside yieldable context is stackless. This means `pcall` must NOT use JVM try/catch across a yield boundary — doing so pins the JVM stack frame. Correct impl: explicit error-propagation flag in `CallInfo`; `pcall` checks frame state on return rather than wrapping in `try`.

**GC pressure from polymorphic dispatch**: `LuauValue` sealed hierarchy + Scala pattern-match dispatch → JVM sees polymorphic call sites. JIT may fail to inline if >2 types at a dispatch site. Monitor with JFR/async-profiler. Hotspot: table get/set, arithmetic, comparisons. Consider unboxed primitives for `LuaInt`/`LuaFloat` via Scala 3 value classes or specialized register arrays.

**String interning contention**: `ConcurrentHashMap` for string intern pool can become bottleneck under multi-`LuauState` scenarios. Partition by hash bucket or use lock-striped intern map.

**`require()` and module system**: Luau's `require` model differs from PUC-Lua (no `package.path`; Roblox uses `@` prefixed paths). Must implement a pluggable module loader interface in `LuauState` so embedder controls resolution. Default impl = filesystem loader; Roblox-mode = ID-based registry.

---

## 6. Phased Roadmap

### Phase 0 — Foundation (Weeks 1–4)

- Repo structure: Mill build, Scala 3.5+, JDK 21+ baseline
- `LuauValue` sealed trait hierarchy + unit tests for all 14 types
- `LuauTable` hybrid array/hash impl + resize + metatable stub + `freeze`
- String intern pool
- `LuauBytecodeDecoder`: parse LBC v3–v6 (MVP target version); decode all opcodes, protos, constants, upvalues
- Round-trip test: `luauc` (official binary) → LBC → decoded proto → re-encoded → matches

### Phase 1 — VM Core (Weeks 5–10)

- `LuauVM.execute()`: full `tableswitch` dispatch loop for all 62+ opcodes
- `CallInfo` heap-linked list; register array allocation per frame
- Upvalue open/close lifecycle (`LOP_CLOSEUPVALS`, `LOP_CAPTURE`)
- Arithmetic: all variants (RR, RK, KR) for int/float dispatch
- String concatenation (`LOP_CONCAT`) with Luau coercion rules
- `LOP_FASTCALL*`: map all `LBF_*` IDs to native Scala impls for math/string builtins
- `pcall`/`xpcall` with explicit `CallInfo` error flag (no JVM try-catch across yield)
- `LOP_GETIMPORT` AUX decode
- Basic test suite: run official Luau test scripts via subprocess `luauc` → LBC → `LuauVM`

### Phase 2 — Coroutines + Stdlib (Weeks 11–16)

- `LuauCoroutine` + Loom virtual thread + `SynchronousQueue` rendezvous
- `coroutine.*` stdlib (create, resume, yield, wrap, status, close, isyieldable)
- Full stdlib: `base`, `math` (with `lerp`/`map`/`clamp`/`noise`), `table` (with `freeze`/`isfrozen`/`clone`/`create`/`find`), `string`, `bit32`, `utf8`, `os` (clock/time only), `debug` (traceback)
- `buffer` lib: fixed-size `Array[Byte]`; all read/write/readbits/writebits ops
- `vector` lib: 3-component ops; `magnitude`, `normalize`, `dot`, `cross`
- Weak table support (`__mode`)
- Class opcode stubs (`LOP_NEWCLASSMEMBER`, `LOP_CMPPROTO`) — error if encountered until fully specified
- Comprehensive stdlib conformance tests vs official Luau

### Phase 3 — Embedding API + Performance (Weeks 17–24)

- `LuauState`: public API; sandbox config; module loader interface; interrupt hook
- `luau.embed` Java-friendly API (no Scala knowledge required for embedder)
- JSR-223 `ScriptEngine` wrapper (optional; enables drop-in for Java apps)
- Inline caches: `CacheSlot[Shape, offset]` array parallel to instructions; invalidate on table reshape
- Profiling: JFR events for opcode dispatch, GC alloc hotspots, coroutine switches
- Escape analysis audit for `LuaVector` allocation; evaluate struct-of-arrays register layout
- LBC version 7–11 support (class system opcodes fully implemented)

### Phase 4 — Compiler + Type Checker (Post 1.0)

- `LuauLexer` + `LuauParser` (recursive descent, full grammar including type annotations)
- `LuauCompiler`: register allocator (linear scan), upvalue analysis, constant folding, `FASTCALL` emission
- Remove subprocess `luauc` dependency; pure-Scala pipeline
- Optional `LuauTypeChecker`: port Analysis frontend; `--!strict` / `--!nonstrict` / `--!nocheck` modes
- JVM-bytecode compilation (Rembulan-style): hot Lua functions → ASM-generated classfiles

---

## 7. References Index

| Research Doc | Primary URL | Key Facts Sourced |
|---|---|---|
| `runtime-luau-official-cpp.md` | [github.com/luau-lang/luau](https://github.com/luau-lang/luau) | Repo layout, VM dispatch, inline cache, GC PID pacing, stdlib, bytecode versioning |
| `runtime-puc-lua-c.md` | [lua.org/source/5.1](https://www.lua.org/source/5.1/) | TValue union, upvalue open/close, register VM ancestry |
| `runtime-luaj-jvm.md` | [github.com/luaj/luaj](https://github.com/luaj/luaj) | `LuaValue` hierarchy, one-thread-per-coroutine, `Varargs`, `LuaTable` hybrid |
| `runtime-moonsharp-csharp.md` | [github.com/moonsharp-devs/moonsharp](https://github.com/moonsharp-devs/moonsharp) | Iterator-based coroutine limits, CLR value boxing lessons |
| `runtime-gopher-lua-go.md` | [github.com/yuin/gopher-lua](https://github.com/yuin/gopher-lua) | `LValue` interface, goroutine-per-coroutine, `LTable` design |
| `runtime-piccolo-rust.md` | [github.com/kyren/piccolo](https://github.com/kyren/piccolo) | Stackless `Sequence`/`Executor`, gc-arena, stackless coroutine model |
| `runtime-fengari-js.md` | [github.com/fengari-lua/fengari](https://github.com/fengari-lua/fengari) | Generator-based coroutine limits, C-to-JS port lessons |
| `runtime-luau-rust-ecosystem.md` | [docs.rs/mlua](https://docs.rs/mlua/latest/mlua/) | `mlua`/`mluau` FFI binding patterns (model for JNI fallback) |
| `topic-luau-bytecode-and-vm.md` | [Bytecode.h](https://github.com/luau-lang/luau/blob/master/Common/include/Luau/Bytecode.h) | All 62 opcodes, instruction formats (ABC/AD/E), AUX encoding, FASTCALL IDs, LBC versions |
| `topic-luau-type-system.md` | [luau.org/types](https://luau.org/types) | Gradual typing, --!strict/nonstrict/nocheck, structural typing, scope of Analysis pipeline |
| `topic-coroutines-on-jvm.md` | [openjdk.org/jeps/444](https://openjdk.org/jeps/444) | Loom Virtual Threads, pinning risks JDK 21–23, fix in JDK 24, `SynchronousQueue` rendezvous pattern |
| `topic-value-representation-and-tables.md` | [VM/src/lobject.h](https://github.com/luau-lang/luau/blob/master/VM/src/lobject.h) | TValue struct, Value union, type tags, vector inline storage, hybrid table struct |

### Additional Primary Sources Fetched Directly

- [github.com/luau-lang/luau/blob/master/Common/include/Luau/Bytecode.h](https://github.com/luau-lang/luau/blob/master/Common/include/Luau/Bytecode.h) — opcode table, LBC version range, FASTCALL IDs
- [github.com/luau-lang/luau/blob/master/VM/src/lobject.h](https://github.com/luau-lang/luau/blob/master/VM/src/lobject.h) — TValue/Value union layout, type tag enumeration
- [luau.org/library](https://luau.org/library) — full stdlib function list including Luau-specific additions
- [luau.org/performance](https://luau.org/performance) — inline cache, FASTCALL, PID GC, vector type rationale
- [github.com/mjanicek/rembulan](https://github.com/mjanicek/rembulan) — exception-based coroutine state machine
- [github.com/kroepke/luna](https://github.com/kroepke/luna) — Rembulan fork; same coroutine model confirmation
- JEP 444 / Java Virtual Threads docs — pinning behavior, SynchronousQueue pattern, JDK 21/24 differences
