# Fengari: Lua 5.3 VM as a Literal C-to-JS Port

**Source**: [github.com/fengari-lua/fengari](https://github.com/fengari-lua/fengari)  
**Target Lua version**: 5.3 (tracks PUC-Rio C reference implementation)  
**Language**: JavaScript ES6, targeting browser and Node.js  
**Strategy**: Near-literal transliteration of the PUC-Rio C source

---

## 1. Overview and Design Philosophy

Fengari (Greek: "moon") is not a reimagining of Lua in idiomatic JS — it is a line-by-line port of the PUC-Rio C implementation. Every source file in `src/` has a direct C counterpart:

| JS file | C counterpart | Purpose |
|---|---|---|
| `lstate.js` | `lstate.c/h` | lua_State, global_State, CallInfo |
| `lobject.js` | `lobject.c/h` | TValue, LClosure, CClosure, Udata |
| `lvm.js` | `lvm.c` | bytecode interpreter loop |
| `ldo.js` | `ldo.c` | call stack, coroutines, error handling |
| `lstring.js` | `lstring.c` | string interning, TString |
| `ltable.js` | `ltable.c` | hash table |
| `lopcodes.js` | `lopcodes.c/h` | opcode definitions and encoding |
| `lparser.js` | `lparser.c` | recursive-descent parser |
| `llex.js` | `llex.c` | lexer |
| `lcode.js` | `lcode.c` | code generator |
| `lfunc.js` | `lfunc.c` | upvalue management |
| `ltm.js` | `ltm.c` | metamethod dispatch |
| `ldebug.js` | `ldebug.c` | debug API |
| `lundump.js` | `lundump.c` | binary chunk loader |
| `ldump.js` | `ldump.c` | binary chunk writer |
| `lmem.js` | `lmem.c` | memory allocation facade |
| `lzio.js` | `lzio.c` | buffered input stream |
| `lapi.js` | `lapi.c` | public C API surface |
| `lauxlib.js` | `lauxlib.c` | auxiliary library |
| `linit.js` | `linit.c` | standard library init |
| `lbaselib.js`..`lutf8lib.js` | `lbaselib.c`..`lutf8lib.c` | standard libraries |

The exported module surface mirrors `lua.h`, `lauxlib.h`, and `lualib.h` exactly: callers import `fengari.lua`, `fengari.lauxlib`, `fengari.lualib`.

The one deliberate exception to literalism: Fengari delegates garbage collection entirely to the JS engine rather than porting Lua's incremental tri-color mark-and-sweep. Consequence: weak tables and `__gc` finalizers are unsupported.

---

## 2. Value Representation: TValue

### 2.1 The C Union → JS Object Translation

In C Lua, `TValue` is a tagged union:

```c
typedef struct lua_TValue {
  TValuefields;  // Value_ union + int tt_
} TValue;
```

The `Value_` union holds a `lua_Number`, `lua_Integer`, `GCObject *`, pointer, or boolean in overlapping memory. This is impossible in JS. Fengari uses a plain object with two fields:

```javascript
class TValue {
    constructor(type, value) {
        this.type = type;   // integer tag: base type in bits 0-3, variant in bits 4-5
        this.value = value; // JS number, TString ref, Table ref, closure ref, etc.
    }
    ttype()  { return this.type & 0x3F; }  // full tag with variant bits
    ttnov()  { return this.type & 0x0F; }  // base type only
}
```

### 2.2 Type Tag Constants

Tags follow the C values exactly:

```
LUA_TNIL          = 0
LUA_TBOOLEAN      = 1
LUA_TLIGHTUSERDATA = 2
LUA_TNUMBER       = 3
LUA_TSTRING       = 4
LUA_TTABLE        = 5
LUA_TFUNCTION     = 6
LUA_TUSERDATA     = 7
LUA_TTHREAD       = 8

Variants (high bits):
LUA_TNUMFLT  = LUA_TNUMBER | (0 << 4)
LUA_TNUMINT  = LUA_TNUMBER | (1 << 4)
LUA_TSHRSTR  = LUA_TSTRING | (0 << 4)
LUA_TLNGSTR  = LUA_TSTRING | (1 << 4)
LUA_TLCL     = LUA_TFUNCTION | (0 << 4)  // Lua closure
LUA_TLCF     = LUA_TFUNCTION | (1 << 4)  // light C function
LUA_TCCL     = LUA_TFUNCTION | (2 << 4)  // C closure
LUA_TPROTO   (internal)
LUA_TDEADKEY (internal dead table key)
```

Type predicates (`ttisinteger()`, `ttisfloat()`, `ttistable()`, etc.) are methods on `TValue`, mirroring the C macros in `lobject.h`.

### 2.3 Value Storage Per Type

| Lua type | JS `value` field |
|---|---|
| nil | `null` |
| boolean | `true` / `false` |
| integer | JS `number` (32-bit semantics enforced via `|0`) |
| float | JS `number` (64-bit double — native JS) |
| string | `TString` object ref |
| table | `Table` object ref |
| Lua closure | `LClosure` object ref |
| C/JS closure | `CClosure` object ref |
| light C fn | JS `Function` ref directly |
| userdata | `Udata` object ref |
| thread | `lua_State` object ref |
| light userdata | arbitrary JS value |

No boxing occurs for numbers: the `value` field already holds a JS number primitive.

### 2.4 Integer Arithmetic: 32-bit Enforcement

Lua 5.3 integers are 64-bit in C but Fengari uses 32-bit (`LUA_MAXINTEGER = 2147483647`, `LUA_MININTEGER = -2147483648`) due to JS's 53-bit safe-integer limit.

Operations use bitwise coercion:

```javascript
// Addition
(v1 + v2)|0

// Floor division
Math.floor(m / n)|0

// Multiplication — uses Math.imul (or shim)
const luaV_imul = Math.imul || function(a, b) {
    let aHi = (a >>> 16) & 0xffff; let aLo = a & 0xffff;
    let bHi = (b >>> 16) & 0xffff; let bLo = b & 0xffff;
    return ((aLo * bLo) + (((aHi * bLo + aLo * bHi) << 16) >>> 0) | 0);
};

// Modulo (Lua floor-mod semantics)
(m - Math.floor(m / n) * n)|0
```

Bitwise ops (`&`, `|`, `^`, `~`, `<<`, `>>`, `>>>`) are directly JS-native and already 32-bit.

---

## 3. lua_State: Thread State

### 3.1 Structure

```javascript
// lstate.js
class lua_State {
    id           // unique integer ID
    stack        // TValue[] — the value stack
    top          // integer offset: first free slot
    stack_last   // integer: last usable stack slot
    ci           // CallInfo — current call frame
    base_ci      // CallInfo — bottom frame
    oldpc        // program counter for traces/debug
    l_G          // global_State reference
    errorJmp     // linked error-handler chain (replaces setjmp buffer)
    nCcalls      // depth of nested C (JS) calls
    nny          // non-yieldable counter
    status       // LUA_OK | LUA_YIELD | LUA_ERR*
    errfunc      // stack offset of error handler
    hook         // debug hook function
    hookmask     // hook event mask
    hookcount    // hook countdown
    openupval    // head of open upvalue list
}
```

The `stack` is a flat `TValue[]` array. Lua register-based addressing uses integer offsets, not pointers. All C pointer arithmetic (`base + ra`, `ci->top`) becomes integer addition.

### 3.2 global_State

```javascript
class global_State {
    id_counter   // monotonic ID for objects
    ids          // WeakMap<object, number> for identity
    mainthread   // the primary lua_State
    l_registry   // TValue holding the global registry Table
    panic        // JS function — uncaught error handler
    atnativeerror // handler for native JS exceptions
    version      // "Lua 5.3"
    tmname       // string[] — metamethod name cache
    mt           // Table[] — metatables by base type index
}
```

No GC fields: Fengari removes `GCObject` chains, `gcstate`, `gray`/`grayagain` lists, and all incremental GC machinery from the C `global_State`.

### 3.3 CallInfo

Each active call frame:

```javascript
{
    func: TValue,      // function being called
    funcOff: number,   // stack offset of the function slot
    top: number,       // top of stack for this frame
    previous: CallInfo,
    next: CallInfo,

    // Lua function fields
    l_base: number,    // base register offset
    l_code: object[],  // decoded instruction array (Proto.code)
    l_savedpc: number, // current program counter (index into l_code)

    // C function fields
    c_k: function,     // continuation on yield (lua_KFunction)
    c_old_errfunc: number,
    c_ctx: any,        // context for continuation

    nresults: number,  // expected return count
    callstatus: number // CIST_LUA | CIST_FRESH | CIST_YPCALL | ...
    extra: number,     // saved funcOff for yield/resume
}
```

The `l_savedpc` field is an integer index into `l_code`, replacing the C `StkId` pointer. Increment is `ci.l_savedpc++` per fetch.

---

## 4. VM Execution Loop

### 4.1 Instruction Encoding

Instructions are decoded at load time from the 32-bit encoded integer into plain JS objects with fields:

```
opcode  (bits 0-5)
A       (bits 6-13)
B       (bits 15-23)
C       (bits 24-32)
Bx      (bits 15-32, 18 bits unsigned)
sBx     (Bx - MAXARG_sBx for signed offset)
```

`GETARG_A(i)`, `GETARG_B(i)`, etc. extract these fields. At load time (`lundump.js`, `lparser.js`) instructions are decoded with `fullins()` into objects; the VM reads `i.A`, `i.B`, `i.C` directly — no per-instruction bit-shifting at runtime.

### 4.2 The Dispatch Loop

`luaV_execute` in `lvm.js`:

```javascript
const luaV_execute = function(L) {
    let ci = L.ci;
    ci.callstatus |= CIST_FRESH;

    newframe:
    for (;;) {
        let i = ci.l_code[ci.l_savedpc++];
        let ra = RA(L, base, i);   // destination register

        switch (GET_OPCODE(i)) {
            case OP_MOVE:  ...
            case OP_LOADK: ...
            case OP_CALL:  ...
            case OP_RETURN: ...
            // ~47 cases total
        }
    }
};
```

The `newframe:` label is critical. When `OP_CALL` enters a Lua function, `ci` is updated to the new frame and `continue newframe` restarts the loop header — no JS recursion, no stack growth. Tail calls reuse the frame and also `continue newframe`.

### 4.3 OP_CALL

```javascript
case OP_CALL: {
    let b = i.B;
    let nresults = i.C - 1;
    if (b !== 0) ldo.adjust_top(L, ra + b);  // fix arg count
    if (ldo.luaD_precall(L, ra, nresults)) {
        // C function: already returned, adjust stack
        if (nresults >= 0) ldo.adjust_top(L, ci.top);
    } else {
        // Lua function: new ci pushed, re-enter loop
        ci = L.ci;
        continue newframe;
    }
    break;
}
```

`luaD_precall` handles both JS functions (returns `true`, result already on stack) and Lua closures (returns `false`, pushes new `CallInfo`).

### 4.4 OP_RETURN

```javascript
case OP_RETURN: {
    if (cl.p.p.length > 0) lfunc.luaF_close(L, base); // close upvalues
    let b = ldo.luaD_poscall(L, ci, ra,
        (i.B !== 0 ? i.B - 1 : L.top - ra));
    if (ci.callstatus & CIST_FRESH) return;  // called from C, exit
    ci = L.ci;
    if (b) ldo.adjust_top(L, ci.top);
    continue newframe;  // return to outer Lua frame
}
```

### 4.5 Operand Helpers

```javascript
const RA  = (L, base, i) => base + i.A;
const RKB = (L, base, k, i) => ISK(i.B) ? k[INDEXK(i.B)] : L.stack[base + i.B];
const RKC = (L, base, k, i) => ISK(i.C) ? k[INDEXK(i.C)] : L.stack[base + i.C];
```

Constants are inlined when the `ISK` bit is set, otherwise read from the stack.

---

## 5. Strings: TString and Uint8Array

Lua strings are 8-bit-clean byte sequences. JavaScript strings are UTF-16 and lose byte identity. Fengari uses `Uint8Array` throughout:

```javascript
class TString {
    constructor(L, str) {
        this.hash = null;
        this.realstring = str;  // Uint8Array of raw bytes
    }
    getstr()  { return this.realstring; }
    tsslen()  { return this.realstring.length; }
}
```

### 5.1 String Interning

Short strings are interned via a hash table on `global_State`. The hash key is computed as a hex-encoded byte string: `"|" + hex(b0) + hex(b1) + ...` by `luaS_hash`. `luaS_eqlngstr` compares by byte equality. This is much slower than C's pointer equality for interned strings — each comparison potentially walks the byte array.

### 5.2 JS ↔ Lua String Conversion

`defs.js` provides bidirectional conversion:

- `to_luastring(jsStr)` — UTF-8 encodes a JS string to `Uint8Array`; results are cached
- `to_jsstring(uint8)` — UTF-8 decodes `Uint8Array` to JS string; invalid bytes replaced with U+FFFD
- `luastring_eq(a, b)` — byte-by-byte comparison
- `from_userstring(v)` — accepts either JS string or `Uint8Array`

---

## 6. Tables

`ltable.js` uses a single `Map` per table (not separate array + hash parts as in C):

```javascript
class Table {
    strong      // Map<hashKey, Entry> — live entries
    dead_strong // Map<hashKey, Entry> — tombstoned entries
    metatable   // Table | null
    flags       // metamethod cache invalidation bits
}
```

Key hashing (`table_hash`) handles type disambiguation:
- Numbers: `"#" + value`
- Strings: `"*" + luaS_hashlongstr()`
- Booleans: `"?true"` / `"?false"`
- Objects/functions: `WeakMap` indirection for identity

Dead entries are kept in `dead_strong` to allow safe iteration during modification. This differs from C's rehash approach.

The C Lua table has a dual structure: a compact array part (integer keys 1..n) and a hash part. Fengari collapses both into a single `Map`, losing the cache-friendly array part optimization.

---

## 7. Error Handling: setjmp/longjmp → try/catch

C Lua uses `setjmp`/`longjmp` for non-local exits. Fengari replaces this with JS exceptions.

### 7.1 Error Jump Chain

```javascript
// luaD_rawrunprotected
const luaD_rawrunprotected = function(L, f, ud) {
    let lj = { status: LUA_OK, previous: L.errorJmp };
    L.errorJmp = lj;
    try {
        f(L, ud);
    } catch(e) {
        if (lj.status === LUA_OK) {
            // native JS error — route through atnativeerror
            let atnativeerror = L.l_G.atnativeerror;
            if (atnativeerror) { /* invoke handler */ }
            else { lj.status = -1; }
        }
    }
    L.errorJmp = lj.previous;
    L.nCcalls = oldnCcalls;
    return lj.status;
};
```

`luaD_throw` sets `L.errorJmp.status` then `throw L.errorJmp`. The catch block in the nearest `luaD_rawrunprotected` frame catches it. Nested protected calls chain via `lj.previous`.

### 7.2 Native JS Error Bridge

If a JS exception is thrown from within a Lua call (not a Lua error object), Fengari tries `atnativeerror` (a user-supplied handler on `global_State`) to convert it to a Lua error. This is the seam between JS and Lua error domains.

---

## 8. Coroutines in Single-Threaded JS

This is Fengari's most complex mapping. C Lua coroutines use separate C stacks (`lua_State` per coroutine). JS has one call stack per thread with no native fiber/continuation support.

### 8.1 Each Coroutine Is a Separate lua_State

`lua_newthread` creates a fresh `lua_State` with its own `stack[]`, `ci` chain, and `status`. The main thread and all coroutines share `global_State`.

### 8.2 Yield via Exception Throw

```javascript
const lua_yieldk = function(L, nresults, ctx, k) {
    if (L.nny > 0) {
        // inside a C call boundary — yield forbidden
        ldebug.luaG_runerror(L, "attempt to yield across a JS-call boundary");
    }
    L.status = LUA_YIELD;
    let ci = L.ci;
    ci.extra = ci.funcOff;       // save func position for resume
    ci.c_k = k;                  // continuation (null for Lua coroutines)
    ci.c_ctx = ctx;
    ci.funcOff = L.top - nresults - 1;
    ci.func = L.stack[ci.funcOff];
    luaD_throw(L, LUA_YIELD);    // throws L.errorJmp — unwinds JS stack
};
```

The throw unwinds the JS call stack all the way back to the `luaD_rawrunprotected` that called `lua_resume`. This is the key insight: a yield terminates the current JS execution of the coroutine. The coroutine's Lua state (its `stack[]` and `ci` chain) remains intact on the heap.

### 8.3 Resume by Re-entering

```javascript
const lua_resume = function(L, from, nargs) {
    L.nny = 0;  // allow yields
    let status = luaD_rawrunprotected(L, resume, nargs);
    // ...error recovery...
    L.nny = oldnny;
    return status;
};
```

`resume` (the internal function) checks `L.status`:
- `LUA_OK` + `L.ci === L.base_ci` → first call, invoke function normally
- `LUA_YIELD` → call `luaD_resume` which re-enters `luaV_execute` from `L.ci.l_savedpc`

The coroutine's saved `l_savedpc` and stack state mean execution continues exactly where `coroutine.yield()` was called.

### 8.4 The nny Counter

`L.nny` (non-yieldable depth) prevents yields from within JS function calls:

```javascript
const luaD_callnoyield = function(L, off, nResults) {
    L.nny++;
    luaD_call(L, off, nResults);
    L.nny--;
};
```

Any C API call that must not be interrupted uses `luaD_callnoyield`. When `nny > 0`, `lua_yieldk` throws a runtime error instead of yielding.

### 8.5 Coroutine Limitation: No Async JS Interop

Yielding only works within the synchronous execution of `lua_resume`. A coroutine cannot yield, perform an async JS operation (e.g., `fetch`), and resume when the Promise resolves — that crosses the JS event loop boundary. The `fengari-interop` docs acknowledge this as a known gap (see [issue #2](https://github.com/fengari-lua/fengari-interop/issues/2)). The workaround is to resume the coroutine from a JS callback:

```lua
-- From the fengari.io demo
local function sleep(delay)
    local co = assert(coroutine.running())
    window:setTimeout(function()
        assert(coroutine.resume(co))
    end, delay * 1000)
    coroutine.yield()
end
```

The JS `setTimeout` callback calls back into Lua to resume. This works but requires manual wiring per async operation.

---

## 9. The Lua C API Surface in JS

`lapi.js` exports the full C API as JS functions. The calling convention maps naturally: the "stack" is an array, indices are integers.

### 9.1 Stack Manipulation

```javascript
lua_pushstring(L, s)     // luaS_new() + pushsvalue2s()
lua_tonumber(L, idx)     // index2addr() + lvm.tonumber()
lua_call(L, n, r)        // → lua_callk(L, n, r, 0, null)
lua_pcall(L, n, r, f)    // → ldo.luaD_pcall()
lua_newuserdata(L, size) // luaS_newudata() + push TValue
```

Pseudo-indices (`LUA_REGISTRYINDEX`, upvalue indices) are handled in `index2addr`:

```javascript
const index2addr = function(L, idx) {
    if (idx > 0) return L.stack[L.ci.l_base + idx - 1];
    else if (idx > LUA_REGISTRYINDEX) return L.stack[L.top + idx];
    else if (idx === LUA_REGISTRYINDEX) return L.l_G.l_registry;
    else /* upvalue */ return L.ci.func.upvals[-idx - 1 - MAXUPVAL].val;
};
```

### 9.2 JS Extensions Beyond C API

Fengari adds several JS-specific functions not present in C Lua:

| Function | Purpose |
|---|---|
| `lua_pushjsfunction(L, f)` | push a JS function as a Lua light C function |
| `lua_pushjsclosure(L, f, n)` | push JS function with n upvalues |
| `lua_toproxy(L, idx)` | return a JS closure that can re-push the value into any state sharing the same `global_State` |
| `lua_todataview(L, idx)` | return `DataView` over a Lua string's `Uint8Array` buffer |
| `lua_tojsstring(L, idx)` | decode Lua string to JS string via `to_jsstring` |

`lua_toproxy` is especially important for passing values between states:

```javascript
const lua_toproxy = function(L, idx) {
    let tv = index2addr(L, idx);
    return create_proxy(L.l_G, tv.type, tv.value);
};
// proxy(L2) re-pushes the value onto L2 if L2 shares L.l_G
```

---

## 10. JS ↔ Lua Interop (fengari-interop)

`fengari-interop` ([github.com/fengari-lua/fengari-interop](https://github.com/fengari-lua/fengari-interop)) is a separate package that bridges the object models.

### 10.1 push(): JS → Lua

```javascript
const push = function(L, v) {
    switch (typeof v) {
        case "undefined":  lua_pushnil(L); break;
        case "number":     lua_pushnumber(L, v); break;
        case "string":     lua_pushstring(L, to_luastring(v)); break;
        case "boolean":    lua_pushboolean(L, v); break;
        case "symbol":     lua_pushlightuserdata(L, v); break;
        case "function":
        case "object":
            // check WeakMap for existing proxy; create userdata if new
            pushjs(L, v);
    }
};
```

JS objects become Lua userdata with the `"js object"` metatable. A per-state `WeakMap` deduplicates: the same JS object always maps to the same Lua userdata identity within a state.

### 10.2 Userdata Wrapping

```javascript
const pushjs = function(L, v) {
    let b = lua_newuserdata(L);
    b.data = v;                    // store JS ref in Udata.data
    luaL_setmetatable(L, js_tname);
};
```

The `"js object"` metatable defines `__index`, `__newindex`, `__call`, `__pairs`, `__len` — all implemented as JS functions that delegate to the wrapped object.

### 10.3 Lua → JS: tojs() and wrap()

```javascript
const tojs = function(L, idx) {
    switch (lua_type(L, idx)) {
        case LUA_TNIL:    return undefined;
        case LUA_TBOOLEAN: return lua_toboolean(L, idx);
        case LUA_TNUMBER: return lua_tonumber(L, idx);
        case LUA_TSTRING: return to_jsstring(lua_tolstring(L, idx));
        case LUA_TUSERDATA: return lua_touserdata(L, idx).data;
        default:          return wrap(L, idx);
    }
};
```

Tables, functions, and threads become `wrap()` proxies: JS objects with `.get(k)`, `.set(k,v)`, `.invoke(...)`, `.apply(...)` methods that call back into Lua.

### 10.4 ES6 Proxy for Full Transparency

When `Proxy` is available, `createproxy()` generates a transparent proxy:

```javascript
new Proxy(target, {
    get(t, k)             { /* lua __index */ },
    set(t, k, v)          { /* lua __newindex */ },
    apply(t, thisArg, args){ /* lua __call */ },
    construct(t, args)    { /* js.new semantics */ },
    deleteProperty(t, k)  { /* lua rawset nil */ },
});
```

This allows a Lua table or function to appear as a native JS object. Limitation: JS operator traps (`+`, `==` outside `===`) cannot be intercepted.

### 10.5 js Library in Lua

```lua
local js = require "js"
js.global          -- window / globalThis
js.null            -- JS null (distinct from Lua nil)
js.new(ctor, ...)  -- new ctor(...)
js.typeof(v)       -- typeof v
js.instanceof(v,t) -- v instanceof t
js.of(iter)        -- iterate Symbol.iterator
js.createproxy(L, p, "object"|"function"|"arrow_function")
```

---

## 11. Parser and Code Generator

The parser (`lparser.js`) is a direct port of the C recursive-descent parser. It generates bytecode in a single pass — no AST intermediate representation. `FuncState` tracks register allocation (`freereg`), active locals (`nactvar`), pending jump lists (`jpc`), and upvalue table (`nups`).

`lcode.js` emits instructions via `luaK_codeABC(fs, op, a, b, c)`, handles peephole optimization (constant folding, dead-code elimination), and patches jump targets via linked lists embedded in instruction operands.

The lexer (`llex.js`) scans `Uint8Array` input. Token characters accumulate into a `Uint8Array` buffer (`ls.buff`). Byte values are accessed as integers; negative values (JS typed array coercion artifact) are normalized with `c < 0 ? 255 + c + 1 : c`.

---

## 12. Upvalues

`lfunc.js` implements open/closed upvalues. Open upvalues reference stack slots by index; `luaF_close` copies the value out of the stack into the closure's own storage when the variable goes out of scope.

```javascript
// luaF_findupval
const luaF_findupval = function(L, level) {
    return L.stack[level];  // simplified: returns TValue at stack offset
};

// luaF_close: for all open upvalues >= level, copy value to closure
const luaF_close = function(L, level) {
    // iterate L.openupval linked list
    // for each upval with stack offset >= level: copy TValue
};
```

Upvalues inside closures are stored as `TValue` references. The `LClosure.upvals` array holds `UpVal` objects. Unlike C, there is no need for pointer indirection: JS object references serve as stable heap cells.

---

## 13. Metamethod System

`ltm.js` mirrors `ltm.c`. `TMS` constants enumerate 24 metamethods (`TM_ADD`=0 through `TM_GC`=23). Lookup:

```javascript
const luaT_gettmbyobj = function(L, o, event) {
    let mt;
    if (ttistable(o)) mt = o.value.metatable;
    else if (ttisfulluserdata(o)) mt = o.value.metatable;
    else mt = L.l_G.mt[ttype(o)];
    return mt ? luaH_getstr(mt, L.l_G.tmname[event]) : luaO_nilobject;
};
```

The `flags` bitmask on `Table` caches which metamethods are absent, avoiding repeated hash lookups (`fasttm` macro equivalent). `luaT_callTM` pushes function + args + calls `luaD_call`.

---

## 14. Binary Chunk Loading

`lundump.js` (`BytecodeParser` class) reads precompiled `.luac` files via a `DataView` over `Uint8Array`. Header validation checks:
- Signature bytes `0x1b 0x4c 0x75 0x61`
- Version `0x53` (Lua 5.3)
- Endianness via `0x5678` test
- Float format via `370.5` test

`LoadFunction` builds `Proto` objects: source name, line ranges, code (instruction objects), constants (`TValue[]`), upvalue descriptors, nested protos, debug info. This is the same structure as `luaU_undump` in C.

---

## 15. Performance Characteristics

### 15.1 Benchmarks

The SciMark benchmark reports native Lua at ~49 vs. Fengari at ~2.17 — roughly **22× slower** than native Lua. This is the expected cost of interpretation-inside-interpretation (JS engine interpreting/JIT-compiling JS that interprets Lua bytecode).

### 15.2 Structural Overheads of the Literal Port

| Source | Cost | C approach |
|---|---|---|
| `TValue` heap object per value | GC pressure; no stack allocation | unboxed union on C stack |
| Integer 32-bit coercion `\|0` on every int op | extra JS op per arithmetic | native 64-bit register |
| String hash = hex-encode byte array | O(n) hash computation | pointer equality for interned |
| Table as JS `Map` (no array part) | cache miss for integer keys | compact C array part for seq. tables |
| Instruction as decoded JS object | property lookup per field | bitfield extraction from uint32 |
| `TValue[]` stack vs. C stack | heap allocation, GC scan | native CPU stack frame |
| V8/SpiderMonkey JIT may not optimize dynamic switch | depends on engine | branch predictor on tight C loop |

### 15.3 Memory Model Mismatch

C Lua stack frames are stack-allocated structs. Fengari's `TValue[]` and `CallInfo` objects are heap-allocated, scanned by GC. Heavy Lua programs generate significant allocation pressure.

### 15.4 What Performs Relatively Well

- Pure control flow (loops, conditionals) — JS JIT handles the `for(;;)` loop with switch well
- String operations — `Uint8Array` methods (`indexOf`, `subarray`) are native in all engines
- Table access on small tables — JS `Map` is well-optimized

---

## 16. Pros and Cons of the Literal Port Strategy

### Pros

**Correctness by construction.** The C implementation is the reference. Line-for-line translation means any bug in PUC-Rio Lua is also a bug in Fengari — no new semantic errors introduced. `99%` of Lua's test suite passes.

**Easy to track upstream.** When Lua 5.4 changes semantics, diffing C source to the JS port is straightforward. Files have the same name, same structure.

**Familiar to Lua C API users.** Developers who know `lua_State`, `TValue`, `luaL_openlibs` can read and extend Fengari without JS Lua expertise. The API is identical.

**Complete feature surface.** Parser, code generator, full standard libraries, debug API, binary chunk loader — all present because they were all ported.

**Coroutine semantics preserved exactly.** Coroutine semantics are notoriously tricky. The literal port inherits the tested C behavior.

### Cons

**Performance ceiling is low.** No path to approach native speed. A JIT-aware idiomatic rewrite could exploit V8's hidden classes and typed arrays to reduce allocation and indirection. The literal port has fundamental overhead that JIT cannot optimize away (e.g., `TValue` object allocation per stack push).

**32-bit integers.** C Lua 5.3 uses 64-bit integers. Fengari uses 32-bit to fit within JS's safe integer range, breaking programs that use large integers.

**No weak tables / no `__gc` finalizers.** The GC delegation to JS means these C-GC-dependent features are absent. Code relying on finalizers silently misbehaves.

**String interning cost.** The hex-encode hash is O(n) per string and produces large keys. C Lua interns short strings with O(1) pointer equality after first interning.

**No coroutine ↔ async JS without manual wiring.** Can't suspend a coroutine to await a JS `Promise`. Every async JS API requires a callback that manually resumes the coroutine.

**Table without array part optimization.** Sequential integer-keyed tables (the common Lua case) lose the contiguous-array fast path.

**Bundle size.** The full source is ~160 KB minified. Loading the entire Lua VM (parser + all stdlib) for small scripts is expensive in browser context.

**Single-threaded only.** No path to Web Workers sharing a `global_State` — the same limitation as C Lua, inherited verbatim.

---

## 16. Source File Reference

All files under `src/` at [github.com/fengari-lua/fengari/tree/master/src](https://github.com/fengari-lua/fengari/tree/master/src):

- **`defs.js`** — `Uint8Array` helpers, `to_luastring`, `to_jsstring`, `luastring_eq`
- **`luaconf.js`** — `LUA_MAXINTEGER`, stack limits, `lua_number2str`, locale config
- **`llimits.js`** — `MAXUPVAL`, `LUAI_MAXCCALLS`, etc.
- **`lobject.js`** — `TValue`, `LClosure`, `CClosure`, `Udata`, `luaO_arith`
- **`lstate.js`** — `lua_State`, `global_State`, `CallInfo`, `luaE_newstate`
- **`lstring.js`** — `TString`, `luaS_new`, `luaS_hash`, `luaS_bless`
- **`ltable.js`** — `Table`, `luaH_get`, `luaH_set`, `table_hash`
- **`lopcodes.js`** — opcode constants, `GETARG_*`, `fullins`
- **`lvm.js`** — `luaV_execute`, `luaV_imul`, `luaV_mod`, `luaV_div`
- **`ldo.js`** — `luaD_rawrunprotected`, `luaD_throw`, `lua_yieldk`, `lua_resume`
- **`lapi.js`** — full C API, `lua_toproxy`, `lua_todataview`, `index2addr`
- **`lfunc.js`** — `luaF_close`, `luaF_findupval`, upvalue management
- **`ltm.js`** — `TMS.*`, `luaT_gettmbyobj`, `luaT_callTM`
- **`ldebug.js`** — `lua_getinfo`, `lua_sethook`, `luaG_traceexec`
- **`lundump.js`** — `BytecodeParser`, `luaU_undump`
- **`llex.js`** — `LexState`, `llex`, token types, `Uint8Array` scanning
- **`lparser.js`** — `FuncState`, recursive-descent parser, direct bytecode emission
- **`lcode.js`** — `luaK_codeABC`, jump patching, constant folding

---

## References

- [github.com/fengari-lua/fengari](https://github.com/fengari-lua/fengari) — main repo
- [github.com/fengari-lua/fengari-interop](https://github.com/fengari-lua/fengari-interop) — JS interop library
- [github.com/fengari-lua/fengari-web](https://github.com/fengari-lua/fengari-web) — browser integration
- [fengari.io](http://fengari.io/) — live demo with coroutine/DOM examples
- [lua.org/wshop17/fengari.html](https://www.lua.org/wshop17/fengari.html) — Lua Workshop 2017 slides
- [medium.com/hackernoon/why-we-rewrote-lua-in-js](https://medium.com/hackernoon/why-we-rewrote-lua-in-js-a66529a8278d) — design rationale post
- [fengari-interop issue #2: async yield](https://github.com/fengari-lua/fengari-interop/issues/2)
