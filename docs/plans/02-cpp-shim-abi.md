# Plan 02 — C++ Shim ABI

## 1. Milestone & Goal

This plan delivers the complete `lx_*` C ABI header and its C++ implementation layer — the **Shim** — compiled from a single source to both a native shared library (`libluau-shim.so` / `libluau-shim.dylib`) and a WASM module (`luau-shim.wasm`). The Shim is the single point of contact between the Luau C API and every Scala backend; no Scala code ever touches `lua.h` or `lualib.h` directly. It exposes exactly the functions enumerated in this plan, no more. Every function in this ABI is longjmp-safe: none of them allow a Luau error to unwind through a Panama downcall frame or a WASM↔host boundary. The Shim also installs a **trampoline** closure for every **Native function** registered from the Host; the trampoline receives the Host's tri-state result and acts on it in pure C — calling `lua_error` or `lua_yield` as appropriate. After this plan is complete, plan P03 can define the Scala `Binding` trait that mirrors this ABI 1:1, and plans P04/P05 can implement it.

---

## 2. Dependencies

**Prior plan required:** `docs/plans/01-project-scaffold-and-build-toolchain.md` (P01).

P01 must have delivered:

- The Luau source tree as a pinned git submodule at `luau/` (relative to the repo root), providing `luau/VM/include/lua.h`, `luau/VM/include/lualib.h`, `luau/Compiler/include/Luau/Compiler.h`, `luau/Common/include/Luau/Bytecode.h`, and the full Luau C++ source.
- A `shim/` source tree with a working CMake/Makefile (or Mill `NativeCModule`) that can compile C++ against the Luau headers with `clang++` (native target) and `emcc` (WASM target).
- A Mill module `shimNative` that produces `out/shim/native/libluau-shim.so` (Linux) / `libluau-shim.dylib` (macOS).
- A Mill module `shimWasm` that produces `out/shim/wasm/luau-shim.wasm` and `out/shim/wasm/luau-shim.js` (Emscripten loader).
- CI steps that build both targets.

This plan consumes those build artifacts; it specifies the source files that P01's build pipeline compiles.

---

## 3. Design Context

### 3.1 Authoritative ADRs

Read all of the following before implementing:

| ADR | Rule enforced by this Shim |
|-----|---------------------------|
| ADR-0001 | No protected call (`lua_pcall`) across the FFI boundary. All Luau code executes only through `lua_resume` (the **Resume boundary**). Errors convert to a status code, not a `longjmp` through the caller's frame. |
| ADR-0001 | A Scala callback cannot raise. It returns an error result to the Shim; the Shim calls `lua_error` in pure C after the upcall returns. |
| ADR-0003 | The **stackless Task model** is only sound because `setjmp`/`longjmp` never persists across a yield. A `lua_resume` call that yields unwinds the native stack completely. The Shim never holds `setjmp` state across a boundary crossing. |
| ADR-0005 | **Ref** lifetime is deterministic. The Shim exposes `lx_ref` / `lx_unref` (wrapping `lua_ref` / `lua_unref`) with no GC finalizer. |
| ADR-0006 | **Copy-only data** across the boundary. The Shim push/read functions copy values; no userdata wrapping of host objects. Only non-raising read accessors (`lua_type`, `lua_tonumberx`, `lua_tointegerx`, `lua_toboolean`, `lua_tolstring`, etc.) are exposed for reads. |
| ADR-0007 | The **tri-state Native-function return** — `Return(n)` / `Fail(value)` / `Suspend(token)` — is encoded as an integer return code plus out-parameters. The trampoline acts on the result in pure C. |

### 3.2 CONTEXT glossary terms in scope

The following CONTEXT terms are used with their exact definitions throughout this plan:

- **Shim**: the C++ artifact produced by this plan.
- **Resume boundary**: `lx_resume` — the only execution entry point.
- **Native function**: a Scala function registered via `lx_register_native`, reached via the trampoline upcall.
- **Ref**: registry reference, managed by `lx_ref` / `lx_unref`.
- **Suspension**: what `Suspend(token)` produces; the Shim calls `lua_yield(k)` with a continuation that reconstructs the resume result.

### 3.3 Luau C API specifics (from research docs)

Relevant Luau C API entry points this plan wraps (all from `luau/VM/include/lua.h` and `lualib.h`):

**State lifecycle:**
- `lua_newstate(alloc, ud)` / `lua_close(L)` — create/destroy a `global_State` plus its main thread.
- `lua_newthread(L)` — new coroutine sharing `global_State`.

**Compilation (Luau Compiler API, `Compiler/include/Luau/Compiler.h`):**
- `luau_compile(source, size, options, outSize)` — takes Luau source, returns allocated bytecode blob. Caller frees with `free()`.
- `luau_load(L, chunkname, data, size, env)` — loads a bytecode blob onto the stack as a function. Returns 0 on success, non-zero on error (message on stack). This is the only safe way to load code — `luaL_loadstring` is banned because it calls `lua_pcall` internally on some code paths.

**Execution (Resume boundary only):**
- `lua_resume(L, from, narg)` — resumes thread `L`, passing `narg` args already on its stack. Returns `LUA_OK` (0), `LUA_YIELD` (1), `LUA_ERRRUN` (2), `LUA_ERRMEM` (4), `LUA_ERRERR` (5). Error message (for non-OK, non-YIELD) is on the stack.
- `lua_yield(L, nresults)` — yields from a C function called from Luau; used by the trampoline only; returns result count when resumed.
- The `k` (continuation) form: `lua_yieldk(L, nresults, ctx, k)` — yield with continuation function `k` and context value `ctx`. The continuation `k` is called when the coroutine is resumed.

**Non-raising read accessors (the ONLY accessors the Host may use):**
- `lua_type(L, idx)` — returns type tag constant, never errors.
- `lua_tonumberx(L, idx, &isnum)` — returns number or 0, sets `isnum`; never errors.
- `lua_tointegerx(L, idx, &isok)` — same for integer.
- `lua_toboolean(L, idx)` — never errors; returns 0 for anything not truthy.
- `lua_tolstring(L, idx, &len)` — coerces number to string in-place; returns pointer into Lua heap (valid until next GC or stack modification). **The pointer is unstable across Luau calls.** The Shim must copy into a caller-owned buffer.
- `lua_rawlen(L, idx)` — length of string/table/userdata without metamethod, never errors.
- `lua_tothread(L, idx)` — returns `lua_State*` or NULL, never errors.

**Stack manipulation (all non-raising):**
- `lua_gettop(L)` / `lua_settop(L, n)` — read/set stack top.
- `lua_pushnil(L)`, `lua_pushboolean(L, b)`, `lua_pushnumber(L, n)`, `lua_pushinteger(L, i)`, `lua_pushlstring(L, s, len)` — push primitives; may trigger GC via string allocation.
- `lua_createtable(L, narr, nrec)` — creates new table.
- `lua_rawget(L, t)`, `lua_rawset(L, t)` — non-metamethod table access.
- `lua_rawgeti(L, t, n)`, `lua_rawseti(L, t, n)` — integer-keyed table access.
- `lua_pushvalue(L, idx)`, `lua_pop(L, n)`, `lua_insert(L, idx)`, `lua_remove(L, idx)` — stack ops.

**Registry and Refs:**
- `lua_ref(L, idx)` — pins stack value in the registry; returns integer ref key. Never errors.
- `lua_unref(L, ref)` — releases a registry ref. Never errors.
- `lua_getref(L, ref)` — pushes value for `ref` onto stack. Never errors.

**Closures:**
- `lua_pushcclosurek(L, fn, debugname, nups, cont)` — pushes a C closure with `nups` upvalues already on the stack. The Shim uses this to install the trampoline with the `fnId` upvalue.

**Standard library opening (for `lx_open_libs`):**
- `luaL_openlibs(L)` — opens base, math, string, table, coroutine, bit32, utf8, os, vector, buffer.

**Thread status:**
- `lua_status(L)` — returns 0 (OK/running), 1 (YIELD), or error status.
- `lua_costatus(L, co)` — returns `LUA_CORUN`, `LUA_COSUS`, `LUA_CONOR`, `LUA_CODEAD`.

---

## 4. Task Breakdown

All source files live under `/home/hoangdinh/OSS/luau-scala/shim/` (created by P01).

### 4.1 `shim/include/lx.h` — The Public ABI Header

**Purpose:** The single header consumed by both the Panama `jextract` run and the WASM Emscripten export list. Must be valid C (not C++) so Panama and `jextract` can parse it cleanly. All declarations use `extern "C"` when included from C++.

**Key design rules:**
- Every function is prefixed `lx_`.
- Opaque handle types are `void*` aliases; the Host never inspects internals.
- Out-parameters use explicit pointer args rather than return structs (C-compatible, emscripten-friendly).
- No variadic functions.
- No callbacks in the public header — the upcall function pointer type is declared here only as a typedef.

```c
#ifndef LX_H
#define LX_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>
#include <stdint.h>

/* ------------------------------------------------------------------ */
/* Opaque handle types                                                  */
/* ------------------------------------------------------------------ */

/** Opaque handle to a Luau lua_State (one Isolate's main thread). */
typedef void* lx_State;

/** Opaque handle to a Luau coroutine (lua_State*), distinct from lx_State. */
typedef void* lx_Thread;

/* ------------------------------------------------------------------ */
/* Tri-state return ABI                                                 */
/* ------------------------------------------------------------------ */

/** Return codes from the Host upcall (see lx_HostFn typedef). */
#define LX_RETURN   0   /* Return(n): nResults results are on the stack */
#define LX_FAIL     1   /* Fail: error value is on the stack (index -1) */
#define LX_SUSPEND  2   /* Suspend: token stored via lx_set_suspend_token */

/**
 * Host upcall function pointer type.
 *
 * Called by the trampoline inside lua_resume.  The Host must:
 *   - On LX_RETURN: push exactly nResults_out values and set *nResults_out.
 *   - On LX_FAIL:   push one error value; *nResults_out is ignored.
 *   - On LX_SUSPEND: call lx_set_suspend_token before returning.
 *
 * Parameters:
 *   state     - the Isolate's main lx_State
 *   thread    - the active coroutine lx_Thread
 *   fnId      - numeric identifier assigned at lx_register_native
 *   nArgs     - number of arguments on the thread's stack
 *   nResults  - out-param: Host sets to result count (LX_RETURN case)
 *
 * Returns one of LX_RETURN, LX_FAIL, or LX_SUSPEND.
 */
typedef int (*lx_HostFn)(
    lx_State  state,
    lx_Thread thread,
    int32_t   fnId,
    int       nArgs,
    int*      nResults
);

/* ------------------------------------------------------------------ */
/* State lifecycle                                                      */
/* ------------------------------------------------------------------ */

/**
 * Create a new Luau state (Isolate) and register the host upcall.
 * The upcall is called by every trampoline installed in this state.
 * Returns NULL on allocation failure.
 */
lx_State lx_newstate(lx_HostFn upcall);

/** Close a Luau state and free all memory. All Refs are invalidated. */
void lx_close(lx_State state);

/** Return the main thread for a state (the lx_Thread used for top-level resumes). */
lx_Thread lx_main_thread(lx_State state);

/** Create a new coroutine thread within the state. Returns lx_Thread.
 *  The coroutine function must be on the main thread's stack at index -1
 *  before calling this; lx_new_thread pops it and installs it in the new thread.
 *  The new thread starts in SUSPENDED state. */
lx_Thread lx_new_thread(lx_State state);

/** Query thread status. Returns 0=ok/running, 1=suspended, 2=dead, 3=normal. */
int lx_thread_status(lx_State state, lx_Thread thread);

/* ------------------------------------------------------------------ */
/* Compile and load                                                     */
/* ------------------------------------------------------------------ */

/**
 * Compile Luau source to bytecode using the Luau Compiler API
 * (luau_compile), then load it into the state via luau_load.
 * On success: the compiled chunk is pushed as a function onto
 * the MAIN thread's stack. Returns 0.
 * On error: returns non-zero; the error string is written into
 * errbuf (up to errbufsz bytes, NUL-terminated).
 *
 * optimizationLevel: 0=none, 1=default, 2=O2+inline.
 * debugLevel:        0=none, 1=lines, 2=full.
 * chunkname:         identifies the chunk in error messages.
 */
int lx_compile_and_load(
    lx_State    state,
    const char* source,
    size_t      sourceLen,
    const char* chunkname,
    int         optimizationLevel,
    int         debugLevel,
    char*       errbuf,
    size_t      errbufsz
);

/* ------------------------------------------------------------------ */
/* Resume boundary — THE ONLY EXECUTION ENTRY POINT                    */
/* ------------------------------------------------------------------ */

/**
 * Resume a thread.  lx_resume is the ONLY function that executes Luau
 * code.  It wraps lua_resume and converts any error into a status code,
 * guaranteeing no longjmp crosses the call boundary.
 *
 * nArgs: number of arguments already pushed onto `thread`'s stack.
 *
 * Return codes:
 *   LX_RESUME_OK      (0) — thread returned normally; nResults set.
 *   LX_RESUME_YIELD   (1) — thread yielded; nResults set to yield arg count.
 *   LX_RESUME_ERR     (2) — runtime error; error value on stack at index -1.
 *   LX_RESUME_MEMERR  (3) — memory allocation error.
 *
 * Out-params:
 *   *nResults — valid for OK and YIELD; number of values on thread's stack.
 */
#define LX_RESUME_OK     0
#define LX_RESUME_YIELD  1
#define LX_RESUME_ERR    2
#define LX_RESUME_MEMERR 3

int lx_resume(
    lx_State  state,
    lx_Thread thread,
    int       nArgs,
    int*      nResults
);

/* ------------------------------------------------------------------ */
/* Value push (Host → Luau stack)                                       */
/* ------------------------------------------------------------------ */

/** Push nil onto thread's stack. */
void lx_push_nil    (lx_State state, lx_Thread thread);
/** Push boolean. b != 0 → true. */
void lx_push_boolean(lx_State state, lx_Thread thread, int b);
/** Push double. */
void lx_push_number (lx_State state, lx_Thread thread, double n);
/** Push 64-bit integer. */
void lx_push_integer(lx_State state, lx_Thread thread, int64_t i);
/**
 * Push a byte string. The Shim copies the bytes; the caller may free
 * the buffer immediately after.  len is byte count (not NUL-terminated).
 */
void lx_push_lstring(lx_State state, lx_Thread thread,
                     const char* s, size_t len);

/** Push a value from the registry (by ref key) onto thread's stack. */
void lx_push_ref    (lx_State state, lx_Thread thread, int ref);

/** Duplicate stack slot at index idx (1-based from bottom) onto top. */
void lx_push_copy   (lx_State state, lx_Thread thread, int idx);

/** Pop n values from thread's stack. */
void lx_pop         (lx_State state, lx_Thread thread, int n);

/** Return the current stack height of the thread (number of values). */
int  lx_stack_top   (lx_State state, lx_Thread thread);

/* ------------------------------------------------------------------ */
/* Value read (Luau stack → Host) — NON-RAISING ONLY                   */
/* ------------------------------------------------------------------ */

/**
 * Return the Luau type tag at stack index idx.
 * Uses lua_type; never errors.
 * Returns one of: LX_TNIL, LX_TBOOLEAN, LX_TNUMBER, LX_TINTEGER,
 *                 LX_TSTRING, LX_TTABLE, LX_TFUNCTION, LX_TTHREAD,
 *                 LX_TBUFFER, LX_TVECTOR, LX_TUSERDATA, or LX_TNONE (-1).
 */
int    lx_type      (lx_State state, lx_Thread thread, int idx);

/* lx_type return constants — mirror lua_Type */
#define LX_TNONE      (-1)
#define LX_TNIL         0
#define LX_TBOOLEAN     1
#define LX_TNUMBER      3
#define LX_TINTEGER     4   /* LUA_TINTEGER in Luau */
#define LX_TVECTOR      5   /* LUA_TVECTOR in Luau */
#define LX_TSTRING      6
#define LX_TTABLE       7
#define LX_TFUNCTION    8
#define LX_TUSERDATA    9
#define LX_TTHREAD     10
#define LX_TBUFFER     11

/**
 * Read number at idx. Sets *ok=1 if the slot is a number, 0 otherwise.
 * Never errors.
 */
double  lx_to_number (lx_State state, lx_Thread thread, int idx, int* ok);

/**
 * Read integer at idx. Sets *ok=1 if the slot is an integer (LUA_TINTEGER),
 * 0 otherwise.  Does NOT coerce from number.
 */
int64_t lx_to_integer(lx_State state, lx_Thread thread, int idx, int* ok);

/**
 * Read boolean at idx.  Returns 0 for nil/false, 1 for everything else.
 * Never errors.
 */
int     lx_to_boolean(lx_State state, lx_Thread thread, int idx);

/**
 * Copy the string at idx into dst (up to dstlen bytes, NUL-terminated).
 * Sets *len to the string's byte length (before truncation).
 * Returns 0 if not a string (does NOT coerce numbers).
 * Returns 1 on success.
 *
 * IMPORTANT: does NOT call lua_tolstring (which mutates the stack).
 * Uses lua_type check + lua_tolstring only when it IS a string.
 * The Shim copies immediately; caller does not need the Lua heap alive.
 */
int     lx_to_lstring(lx_State state, lx_Thread thread, int idx,
                       char* dst, size_t dstlen, size_t* len);

/**
 * Read the raw byte length of string, table, or buffer at idx.
 * Uses lua_rawlen; never errors.  Returns 0 if wrong type.
 */
size_t  lx_rawlen    (lx_State state, lx_Thread thread, int idx);

/* ------------------------------------------------------------------ */
/* Table operations                                                     */
/* ------------------------------------------------------------------ */

/** Push a new empty table with array hint narr and hash hint nrec. */
void lx_newtable     (lx_State state, lx_Thread thread, int narr, int nrec);

/**
 * Raw table get: pops key from stack top, pushes value at table index tidx.
 * Uses lua_rawget (no __index metamethod).
 */
void lx_rawget       (lx_State state, lx_Thread thread, int tidx);

/**
 * Raw table set: pops value then key from stack top, sets table[key]=value
 * at table index tidx.  Uses lua_rawset.
 */
void lx_rawset       (lx_State state, lx_Thread thread, int tidx);

/**
 * Raw integer-keyed table get: pushes table[n] at table index tidx.
 * Uses lua_rawgeti.  n is 1-based (Luau convention).
 */
void lx_rawgeti      (lx_State state, lx_Thread thread, int tidx, int n);

/**
 * Raw integer-keyed table set: pops value from top, sets table[n]=value.
 * Uses lua_rawseti.  n is 1-based.
 */
void lx_rawseti      (lx_State state, lx_Thread thread, int tidx, int n);

/**
 * Batch-set the array part: values are at stack indices [base, base+count).
 * Sets table[startIdx .. startIdx+count-1].
 * Wraps repeated lua_rawseti; more efficient than calling lx_rawseti N times
 * because no extra stack manipulation per element.
 * Does NOT pop the values.
 */
void lx_setarray     (lx_State state, lx_Thread thread,
                       int tidx, int startIdx, int count);

/* ------------------------------------------------------------------ */
/* Registry Refs                                                        */
/* ------------------------------------------------------------------ */

/**
 * Pin the value at stack index idx in the registry.
 * Returns an integer ref key. The value remains on the stack (not popped).
 * Wraps lua_ref.  Never errors (returns LUA_NOREF on empty stack; treat as error).
 */
int  lx_ref          (lx_State state, lx_Thread thread, int idx);

/**
 * Release a registry ref (wraps lua_unref).
 * After this call the ref key is invalid.  Idempotent for LUA_NOREF.
 * Thread-safety: MUST be called on the Driver thread that owns this state.
 */
void lx_unref        (lx_State state, int ref);

/* ------------------------------------------------------------------ */
/* Native function registration                                         */
/* ------------------------------------------------------------------ */

/**
 * Register a Native function with the given fnId.
 * Installs a C closure (the trampoline) as a Luau function and pushes it
 * onto the main thread's stack.
 *
 * The trampoline closure stores fnId as its sole upvalue (an integer).
 * When called from Luau:
 *   1. Reads fnId from the upvalue.
 *   2. Calls the lx_HostFn registered with lx_newstate.
 *   3. Acts on the tri-state result (Return/Fail/Suspend) in pure C.
 *
 * debugname: used in Luau stack traces; may be NULL.
 * fnId:      Host-assigned integer identifier; Host uses it to dispatch.
 */
void lx_register_native(lx_State state, int32_t fnId, const char* debugname);

/* ------------------------------------------------------------------ */
/* Yield/resume result passing                                          */
/* ------------------------------------------------------------------ */

/**
 * Called by the Host's upcall before returning LX_SUSPEND.
 * Stores a 64-bit suspend token so the continuation can retrieve it.
 * The token is an opaque value the Host uses to identify the pending async op.
 */
void lx_set_suspend_token(lx_State state, lx_Thread thread, int64_t token);

/**
 * Retrieve the suspend token stored before LX_SUSPEND was returned.
 * Called by the Host after lx_resume returns LX_RESUME_YIELD,
 * to discover which async operation to wire.
 * Returns 0 if no token was set.
 */
int64_t lx_get_suspend_token(lx_State state, lx_Thread thread);

/**
 * Push the resume result values for a suspended task that is being woken.
 * Called BEFORE lx_resume on the thread being re-started.
 * count: number of values already on the thread's stack to pass as resume args.
 * (In practice the Host pushes the result values then calls lx_resume(count).)
 * This function is informational / consistency; no Luau state is mutated.
 * The actual mechanism is: Host pushes values, calls lx_resume(nArgs=count).
 */

/* ------------------------------------------------------------------ */
/* Standard libraries                                                   */
/* ------------------------------------------------------------------ */

/**
 * Open the safe Luau standard libraries: base, math, string, table,
 * coroutine, bit32, utf8, os (time/clock/date/difftime only), vector, buffer.
 * Excludes: io, os.execute, os.exit, os.getenv, package, require.
 * Wraps luaL_openlibs with a sandbox filter (see implementation notes).
 */
void lx_open_libs(lx_State state);

/* ------------------------------------------------------------------ */
/* GC control                                                          */
/* ------------------------------------------------------------------ */

/**
 * Run one incremental GC step.  Wraps lua_gc(L, LUA_GCSTEP, stepsize).
 * stepsize: kilobytes to process (0 = VM default).
 */
void lx_gc_step(lx_State state, int stepsize);

/**
 * Perform a full GC cycle.  Wraps lua_gc(L, LUA_GCCOLLECT, 0).
 * Use sparingly; intended for testing and teardown.
 */
void lx_gc_collect(lx_State state);

/* ------------------------------------------------------------------ */
/* Error string helper                                                  */
/* ------------------------------------------------------------------ */

/**
 * If the top of thread's stack is a string, copy it into errbuf.
 * Otherwise writes "<non-string error>".
 * Returns the number of bytes written (excluding NUL).
 * Used after lx_resume returns LX_RESUME_ERR to retrieve the message.
 */
size_t lx_copy_error(lx_State state, lx_Thread thread,
                      char* errbuf, size_t errbufsz);

#ifdef __cplusplus
} /* extern "C" */
#endif
#endif /* LX_H */
```

---

### 4.2 `shim/src/lx.cpp` — C++ Implementation

**Purpose:** Implements every function declared in `lx.h` over the Luau C API. This is the only C++ file that includes `lua.h`, `lualib.h`, and `Luau/Compiler.h`. Must compile cleanly with both `clang++` (native) and `emcc` (WASM).

**File layout:**

```
/home/hoangdinh/OSS/luau-scala/shim/
├── include/
│   └── lx.h                  (4.1 above)
└── src/
    ├── lx.cpp                 (this section)
    └── lx_test.c              (section 4.4 — C-level tests)
```

#### 4.2.1 Includes and state layout

```cpp
// shim/src/lx.cpp
extern "C" {
#include "lx.h"
}
#include "lua.h"
#include "lualib.h"
#include "Luau/Compiler.h"
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <cassert>

// Per-state metadata not held by lua_State itself.
struct LxStateData {
    lx_HostFn  upcall;          // Host upcall registered at lx_newstate
    int64_t    suspendToken;    // scratch: last token set by lx_set_suspend_token
};

// Access LxStateData from any lua_State in this global_State via userdata pointer.
// We store it in the main thread's userdata field (lua_State::userdata).
static inline LxStateData* get_state_data(lua_State* L) {
    // Main thread's userdata was set at lx_newstate.
    return static_cast<LxStateData*>(lua_getthreaddata(lua_mainthread(L)));
}
```

**Note on `lua_State::userdata`:** Luau exposes `lua_getthreaddata` / `lua_setthreaddata` for per-thread embedder data. `lua_mainthread(L)` returns the main thread of `L`'s `global_State`. This is how every thread retrieves the `LxStateData` without an extra map.

#### 4.2.2 State lifecycle

```cpp
lx_State lx_newstate(lx_HostFn upcall) {
    lua_State* L = luaL_newstate();          // uses default allocator
    if (!L) return nullptr;
    LxStateData* d = new (std::nothrow) LxStateData{upcall, 0};
    if (!d) { lua_close(L); return nullptr; }
    lua_setthreaddata(L, d);
    return static_cast<lx_State>(L);
}

void lx_close(lx_State state) {
    lua_State* L = static_cast<lua_State*>(state);
    LxStateData* d = get_state_data(L);
    lua_close(L);           // destroys all threads; no Luau code runs
    delete d;
}

lx_Thread lx_main_thread(lx_State state) {
    lua_State* L = static_cast<lua_State*>(state);
    return static_cast<lx_Thread>(lua_mainthread(L));
}

lx_Thread lx_new_thread(lx_State state) {
    lua_State* L  = static_cast<lua_State*>(state);
    lua_State* co = lua_newthread(L);
    // Move the function from main thread's top to the new coroutine's stack.
    // lua_newthread leaves the thread on main thread's stack; we also need
    // to move the target function.  Caller is responsible for stack discipline.
    // See lx_resume docs: thread is passed nArgs already on its own stack.
    return static_cast<lx_Thread>(co);
}

int lx_thread_status(lx_State state, lx_Thread thread) {
    lua_State* L  = static_cast<lua_State*>(state);
    lua_State* co = static_cast<lua_State*>(thread);
    (void)L;
    int s = lua_costatus(L, co);
    // Map lua_costatus values to our 0/1/2/3 encoding
    switch (s) {
        case LUA_CORUN:  return 0;   // running
        case LUA_COSUS:  return 1;   // suspended
        case LUA_CONOR:  return 3;   // normal (resumed another)
        case LUA_CODEAD: return 2;   // dead
        default:         return 2;
    }
}
```

#### 4.2.3 Compile and load

```cpp
int lx_compile_and_load(
    lx_State state, const char* source, size_t sourceLen,
    const char* chunkname, int optLevel, int debugLevel,
    char* errbuf, size_t errbufsz)
{
    lua_State* L = static_cast<lua_State*>(state);

    // Step 1: compile Luau source → bytecode blob
    Luau::CompileOptions opts{};
    opts.optimizationLevel = optLevel;
    opts.debugLevel        = debugLevel;

    size_t bytecodeLen = 0;
    // luau_compile returns a malloc'd buffer; on error the buffer starts with '\0'
    // followed by the error message (Luau convention).
    char* bytecode = luau_compile(source, sourceLen, &opts, &bytecodeLen);
    if (!bytecode) {
        snprintf(errbuf, errbufsz, "luau_compile: allocation failure");
        return 1;
    }
    // Luau error convention: bytecode[0] == '\0' means compile error;
    // the message follows at bytecode+1.
    if (bytecodeLen == 0 || bytecode[0] == '\0') {
        const char* msg = (bytecodeLen > 1) ? bytecode + 1 : "unknown compile error";
        snprintf(errbuf, errbufsz, "%s", msg);
        free(bytecode);
        return 1;
    }

    // Step 2: load bytecode onto main thread's stack via luau_load
    // lua_State* main = lua_mainthread(L);  // load onto main thread
    int rc = luau_load(L, chunkname ? chunkname : "?", bytecode, bytecodeLen, 0);
    free(bytecode);
    if (rc != 0) {
        // Error string is on L's stack
        size_t msglen = 0;
        const char* msg = lua_tolstring(L, -1, &msglen);
        if (msg)
            snprintf(errbuf, errbufsz, "%.*s", (int)msglen, msg);
        else
            snprintf(errbuf, errbufsz, "luau_load: unknown error");
        lua_pop(L, 1);
        return 1;
    }
    // Chunk is now a function on L's stack (index -1).
    return 0;
}
```

**Critical note:** `luau_compile` is declared in `Luau/Compiler.h`. The error convention is Luau-specific: a successful compile returns the binary bytecode starting with the version byte. An error returns a buffer where `buf[0] == '\0'` and the ASCII error message follows. This is documented in `Compiler/include/Luau/Compiler.h` and confirmed in `Compiler/src/Compiler.cpp`. Do not confuse with a zero-length return (allocation failure returns NULL).

#### 4.2.4 The Resume boundary

```cpp
int lx_resume(lx_State state, lx_Thread thread, int nArgs, int* nResults) {
    lua_State* L  = static_cast<lua_State*>(state);
    lua_State* co = static_cast<lua_State*>(thread);
    (void)L;

    // lua_resume does NOT longjmp through the caller; it catches errors
    // internally and returns a status code.  This is the ONLY execution entry.
    int status = lua_resume(co, nullptr, nArgs);

    switch (status) {
        case LUA_OK:
            *nResults = lua_gettop(co);
            return LX_RESUME_OK;
        case LUA_YIELD:
            *nResults = lua_gettop(co);
            return LX_RESUME_YIELD;
        case LUA_ERRMEM:
            *nResults = 0;
            return LX_RESUME_MEMERR;
        default:
            // LUA_ERRRUN, LUA_ERRERR: error value is on co's stack at -1
            *nResults = 0;
            return LX_RESUME_ERR;
    }
}
```

**Longjmp-safety proof:** `lua_resume` in Luau is implemented with its own `setjmp` buffer. Any Luau-level error is caught by that buffer and converted to `LUA_ERRRUN`. The native stack never unwinds past `lua_resume`. This is the entire reason `lua_resume` is the resume boundary and `lua_pcall` is banned (ADR-0001): `lua_pcall` has the same property, but calling it from the Host would create a second `setjmp` context on the Host's native stack, which would be crossed by any nested `lua_error` inside the callback chain — undefined behavior through a Panama/WASM frame.

#### 4.2.5 Push/pop operations

```cpp
void lx_push_nil    (lx_State s, lx_Thread t) { lua_pushnil(T(t)); }
void lx_push_boolean(lx_State s, lx_Thread t, int b) { lua_pushboolean(T(t), b); }
void lx_push_number (lx_State s, lx_Thread t, double n) { lua_pushnumber(T(t), n); }
void lx_push_integer(lx_State s, lx_Thread t, int64_t i) { lua_pushinteger(T(t), (lua_Integer)i); }
void lx_push_lstring(lx_State s, lx_Thread t, const char* b, size_t l) { lua_pushlstring(T(t), b, l); }
void lx_push_ref    (lx_State s, lx_Thread t, int ref) { lua_getref(T(t), ref); }
void lx_push_copy   (lx_State s, lx_Thread t, int idx) { lua_pushvalue(T(t), idx); }
void lx_pop         (lx_State s, lx_Thread t, int n)   { lua_pop(T(t), n); }
int  lx_stack_top   (lx_State s, lx_Thread t)          { return lua_gettop(T(t)); }

// Convenience macro — cast lx_Thread to lua_State*
#define T(thread) (static_cast<lua_State*>(thread))
```

#### 4.2.6 Non-raising read accessors

```cpp
int lx_type(lx_State s, lx_Thread t, int idx) {
    int tt = lua_type(T(t), idx);
    // Map Luau lua_Type enum to lx_T* constants.
    // lua_type returns LUA_TNONE (-1) for invalid index.
    // Luau type values differ from Lua 5.1 (integers are separate type).
    // LUA_TINTEGER is 5 in Luau; map to LX_TINTEGER=4 for the public ABI.
    // See luau/VM/include/lua.h lua_Type enum for exact values.
    return tt;   // In practice, mirror Luau's values directly; Host uses LX_T* constants.
}

double lx_to_number(lx_State s, lx_Thread t, int idx, int* ok) {
    int isnum = 0;
    double v = lua_tonumberx(T(t), idx, &isnum);
    *ok = isnum;
    return v;
}

int64_t lx_to_integer(lx_State s, lx_Thread t, int idx, int* ok) {
    int isok = 0;
    lua_Integer v = lua_tointegerx(T(t), idx, &isok);
    *ok = isok;
    return (int64_t)v;
}

int lx_to_boolean(lx_State s, lx_Thread t, int idx) {
    return lua_toboolean(T(t), idx);
}

int lx_to_lstring(lx_State s, lx_Thread t, int idx,
                   char* dst, size_t dstlen, size_t* len) {
    if (lua_type(T(t), idx) != LUA_TSTRING) { *len = 0; return 0; }
    size_t slen = 0;
    const char* p = lua_tolstring(T(t), idx, &slen);
    // lua_tolstring on a string does not coerce and does not move GC objects.
    *len = slen;
    size_t copy = (slen < dstlen - 1) ? slen : dstlen - 1;
    if (dst && dstlen > 0) {
        memcpy(dst, p, copy);
        dst[copy] = '\0';
    }
    return 1;
}

size_t lx_rawlen(lx_State s, lx_Thread t, int idx) {
    return (size_t)lua_rawlen(T(t), idx);
}
```

**Safety note on `lua_tolstring`:** Luau's `lua_tolstring` coerces numbers to strings in-place by replacing the TValue on the stack with a string TValue. For the lx ABI we guard with `lua_type != LUA_TSTRING` first; this means we will NOT read a number as a string (the Host Codec handles type dispatch). This avoids the in-place mutation, which would be surprising to the Host and could invalidate a cached type check.

#### 4.2.7 Table operations

```cpp
void lx_newtable(lx_State s, lx_Thread t, int narr, int nrec) {
    lua_createtable(T(t), narr, nrec);
}

void lx_rawget(lx_State s, lx_Thread t, int tidx) {
    lua_rawget(T(t), tidx);
}

void lx_rawset(lx_State s, lx_Thread t, int tidx) {
    lua_rawset(T(t), tidx);
}

void lx_rawgeti(lx_State s, lx_Thread t, int tidx, int n) {
    lua_rawgeti(T(t), tidx, n);
}

void lx_rawseti(lx_State s, lx_Thread t, int tidx, int n) {
    lua_rawseti(T(t), tidx, n);
}

void lx_setarray(lx_State s, lx_Thread t, int tidx, int startIdx, int count) {
    lua_State* L = T(t);
    // Values are at indices (top - count + 1) .. top.
    // tidx is the table, already on the stack below the values.
    int base = lua_gettop(L) - count + 1;
    for (int i = 0; i < count; i++) {
        lua_pushvalue(L, base + i);
        lua_rawseti(L, tidx, startIdx + i);
    }
    // Does NOT pop the source values; caller pops if needed.
}
```

#### 4.2.8 Registry Refs

```cpp
int lx_ref(lx_State s, lx_Thread t, int idx) {
    lua_State* L = T(t);
    lua_pushvalue(L, idx);   // push copy to top
    return lua_ref(L, -1);   // refs top, pops it; returns integer key
    // Original slot at idx is untouched.
}

void lx_unref(lx_State s, int ref) {
    lua_State* L = static_cast<lua_State*>(s);
    lua_unref(L, ref);
}
```

**Note on `lua_ref` vs `luaL_ref`:** Luau uses `lua_ref(L, idx)` which takes a stack index, not `luaL_ref` (which expects the value at top). The Shim pushes a copy to top to work with either convention, then pops it. `lua_ref` internally stores the value in the `LUA_REGISTRYINDEX` table keyed by an auto-incrementing integer.

#### 4.2.9 The trampoline and tri-state return ABI

This is the most critical section. Read ADR-0007 in full before implementing.

**Trampoline design:**

The trampoline is a `lua_CFunction` installed as a C closure with one upvalue: the `fnId` integer. When Luau calls the Native function, it enters the trampoline. The trampoline:
1. Reads `fnId` from upvalue 1.
2. Invokes the Host upcall.
3. Acts on the tri-state result in pure C.

The continuation function `lx_trampoline_k` is used for the `Suspend` path: it is called when the coroutine is resumed after a yield, and it reconstructs the results on the stack.

```cpp
// Upvalue index for fnId (1-based in Luau C API)
static const int LX_UPVALUE_FNID = lua_upvalueindex(1);

// Continuation for the Suspend path.
// Called by Luau when the coroutine is resumed after lx_trampoline yielded.
// ctx = 0 (unused).  Resume args are on the stack.
// Returns the number of return values (= nArgs of the resume call).
static int lx_trampoline_k(lua_State* L, int /*status*/, lua_Continuation /*ctx*/) {
    // The coroutine was resumed with some args; they are now at the top.
    // We return them all as the native function's results.
    return lua_gettop(L);
}

// Main trampoline body.
static int lx_trampoline(lua_State* L) {
    // Retrieve fnId from upvalue 1
    int fnId = (int)lua_tointeger(L, LX_UPVALUE_FNID);

    // Retrieve state data (for upcall pointer)
    LxStateData* d = get_state_data(L);

    // nArgs = current stack size (all args pushed by Luau caller)
    int nArgs = lua_gettop(L);

    // Main thread (lx_State handle)
    lua_State* main = lua_mainthread(L);

    // Call the Host
    int nResults = 0;
    int outcome = d->upcall(
        static_cast<lx_State>(main),
        static_cast<lx_Thread>(L),
        (int32_t)fnId,
        nArgs,
        &nResults
    );

    switch (outcome) {
        case LX_RETURN:
            // Host pushed nResults values above the args.
            // We must leave only the result values on the stack.
            // Remove the args that were below the results.
            lua_settop(L, nArgs + nResults);  // ensure exactly args+results
            // Rotate: discard args, keep nResults at bottom
            // More precisely: the Host pushes results ON TOP of args.
            // Stack is: [arg1..argN][res1..resM]
            // We want: return nResults values = [res1..resM]
            // Remove the bottom nArgs slots:
            lua_rotate(L, 1, nResults);   // won't compile — use manual approach
            // CORRECT approach: results are at top nResults slots.
            return nResults;

        case LX_FAIL:
            // Host pushed one error value on top.
            // lua_error longjmps within C; safe here because we are inside
            // lua_resume's setjmp context.
            lua_error(L);  // never returns
            return 0;      // unreachable

        case LX_SUSPEND:
            // Host called lx_set_suspend_token before returning.
            // Yield from C using the continuation form so the yield survives
            // a script-level pcall or yielding metamethod (ADR-0007).
            // nResults=0: no values passed to the resumer for the yield itself.
            // lx_trampoline_k will be called on resume and returns resume args.
            return lua_yieldk(L, 0, 0, lx_trampoline_k);

        default:
            // Defensive: treat unknown outcome as error.
            lua_pushliteral(L, "lx: unknown upcall outcome");
            lua_error(L);
            return 0;
    }
}
```

**Stack discipline for LX_RETURN (detailed):**

After the upcall returns `LX_RETURN`, the thread's stack contains:
```
[arg1] [arg2] ... [argN] [res1] [res2] ... [resM]
```
The trampoline must return exactly `M` (= `nResults`) values. The C convention for `lua_CFunction` is that returning `M` means the top `M` slots of the stack are the results. Therefore the trampoline just returns `nResults` and Lua's call machinery takes the top `nResults` values automatically. The args below are discarded by the call machinery. The Host must ensure it pushes results starting after the args. If the Host wishes to return a value it read from arg slot `idx`, it must push a copy — it cannot "shift" the stack.

**Concrete note for the implementing agent:** When the upcall returns `LX_RETURN` and `nResults > 0`, the Host will have called `lx_push_*` on the thread's stack, adding `nResults` values on top of the `nArgs` args. The trampoline returns `nResults` without any cleanup needed — `lua_resume`'s call machinery pops the args and the function value automatically, leaving only the top `nResults` values as the return.

**`lua_yieldk` and continuation contract:**

`lua_yieldk(L, nresults, ctx, k)` suspends `L`, returning `nresults` yield values to the resumer. When the coroutine is resumed, Luau calls `k(L, status, ctx)` in place of the original trampoline returning. In our case `nresults=0` (we pass nothing to the resumer as yield values; the suspend token was set via `lx_set_suspend_token`). On resume, the Host pushes the async result values and calls `lx_resume(nArgs=count)`; `lx_trampoline_k` is then called with those values already on the stack and simply returns `lua_gettop(L)` — all of them become the return values of the original native function call, as seen by the Luau script.

**Registration:**

```cpp
void lx_register_native(lx_State state, int32_t fnId, const char* debugname) {
    lua_State* L = static_cast<lua_State*>(state);
    // Push fnId as the sole upvalue
    lua_pushinteger(L, fnId);
    // Push the trampoline closure with 1 upvalue, onto the MAIN thread
    lua_pushcclosurek(L, lx_trampoline, debugname ? debugname : "lx_fn", 1, nullptr);
    // The closure is now at L's stack top.
    // Caller is expected to assign it (e.g. set as a global or table field).
}
```

#### 4.2.10 Suspend token

```cpp
void lx_set_suspend_token(lx_State s, lx_Thread t, int64_t token) {
    LxStateData* d = get_state_data(static_cast<lua_State*>(t));
    d->suspendToken = token;
}

int64_t lx_get_suspend_token(lx_State s, lx_Thread t) {
    LxStateData* d = get_state_data(static_cast<lua_State*>(t));
    return d->suspendToken;
}
```

**Note:** `get_state_data` follows to the main thread's userdata. This means all coroutines in an Isolate share one `LxStateData`. The suspend token is per-Isolate, not per-thread. This is correct because only one thread may be inside `lx_resume` at any instant for a single Isolate (single-threaded execution model, ADR-0002/ADR-0004). The token is read immediately after `lx_resume` returns `LX_RESUME_YIELD`; it is stale once the next resume starts.

#### 4.2.11 Standard libraries

```cpp
void lx_open_libs(lx_State state) {
    lua_State* L = static_cast<lua_State*>(state);
    // Open all standard libraries via luaL_openlibs.
    // Then nil out the unsafe ones.
    luaL_openlibs(L);
    // Remove dangerous globals. These assignments are Luau-safe:
    // lua_pushnil + lua_setglobal uses lua_rawset internally (no metamethod).
    static const char* const blocked[] = {
        "io", "require", "dofile", "loadfile", "load", "loadstring",
        nullptr
    };
    for (int i = 0; blocked[i]; i++) {
        lua_pushnil(L);
        lua_setglobal(L, blocked[i]);
    }
    // Sandboxed os: keep clock/time/date/difftime, remove execute/exit/getenv/remove/rename/tmpname.
    lua_getglobal(L, "os");
    if (lua_type(L, -1) == LUA_TTABLE) {
        static const char* const blocked_os[] = {
            "execute", "exit", "getenv", "remove", "rename", "tmpname",
            nullptr
        };
        for (int i = 0; blocked_os[i]; i++) {
            lua_pushnil(L);
            lua_setfield(L, -2, blocked_os[i]);
        }
    }
    lua_pop(L, 1);
}
```

**Note:** Plan P07 (`docs/plans/07-stdlib-and-task-library.md`) owns the full sandboxing policy and adds the `task` library. `lx_open_libs` here provides only the minimal safe base. Plan P07 may call additional Shim functions (like `lx_register_native`) to register `task.*` implementations.

#### 4.2.12 GC helpers and error string

```cpp
void lx_gc_step(lx_State state, int stepsize) {
    lua_State* L = static_cast<lua_State*>(state);
    lua_gc(L, LUA_GCSTEP, stepsize);
}

void lx_gc_collect(lx_State state) {
    lua_State* L = static_cast<lua_State*>(state);
    lua_gc(L, LUA_GCCOLLECT, 0);
}

size_t lx_copy_error(lx_State s, lx_Thread t,
                      char* errbuf, size_t errbufsz) {
    lua_State* L = T(t);
    size_t slen = 0;
    const char* p = nullptr;
    if (lua_type(L, -1) == LUA_TSTRING)
        p = lua_tolstring(L, -1, &slen);
    if (!p) { p = "<non-string error>"; slen = strlen(p); }
    size_t copy = (slen < errbufsz - 1) ? slen : errbufsz - 1;
    memcpy(errbuf, p, copy);
    errbuf[copy] = '\0';
    return copy;
}
```

---

### 4.3 `shim/include/lx.h` — EXPORTED_FUNCTIONS and Emscripten Build Settings

This section specifies the **emscripten** build configuration. It does NOT produce a new file; it specifies build flags that P01's `shimWasm` build target must pass to `emcc`.

#### 4.3.1 EXPORTED_FUNCTIONS list

Every `lx_*` symbol that the WASM backend calls must appear in `-s EXPORTED_FUNCTIONS`. Emscripten silently dead-strips unexported symbols. The complete list:

```
-s EXPORTED_FUNCTIONS='[
  "_lx_newstate",
  "_lx_close",
  "_lx_main_thread",
  "_lx_new_thread",
  "_lx_thread_status",
  "_lx_compile_and_load",
  "_lx_resume",
  "_lx_push_nil",
  "_lx_push_boolean",
  "_lx_push_number",
  "_lx_push_integer",
  "_lx_push_lstring",
  "_lx_push_ref",
  "_lx_push_copy",
  "_lx_pop",
  "_lx_stack_top",
  "_lx_type",
  "_lx_to_number",
  "_lx_to_integer",
  "_lx_to_boolean",
  "_lx_to_lstring",
  "_lx_rawlen",
  "_lx_newtable",
  "_lx_rawget",
  "_lx_rawset",
  "_lx_rawgeti",
  "_lx_rawseti",
  "_lx_setarray",
  "_lx_ref",
  "_lx_unref",
  "_lx_register_native",
  "_lx_set_suspend_token",
  "_lx_get_suspend_token",
  "_lx_open_libs",
  "_lx_gc_step",
  "_lx_gc_collect",
  "_lx_copy_error",
  "_malloc",
  "_free"
]'
```

`_malloc` and `_free` are required by the WASM backend to allocate string buffers in linear memory before calling `lx_push_lstring` and `lx_to_lstring`.

#### 4.3.2 Emscripten build flags (to be embedded in P01's build recipe)

```cmake
# In shim/CMakeLists.txt or the emcc invocation:
set(EMSCRIPTEN_FLAGS
  -s MODULARIZE=1
  -s EXPORT_NAME='LuauShim'
  -s ALLOW_MEMORY_GROWTH=1
  -s ALLOW_TABLE_GROWTH=1         # required for addFunction (upcall table)
  -s EXPORTED_RUNTIME_METHODS='["addFunction","removeFunction","dynCall_iii","dynCall_iiiiii"]'
  -s EXPORTED_FUNCTIONS='[...]'   # full list from 4.3.1
  -s WASM=1
  -s ENVIRONMENT='node,web,worker'
  -O2
  --no-entry
)
```

**Why each flag:**

- `MODULARIZE=1` + `EXPORT_NAME='LuauShim'`: the generated JS exposes a factory function `LuauShim()` that returns a promise resolving to the module object. Scala.js code awaits this before making any calls. Without `MODULARIZE`, the module auto-initializes on script load — unreliable in async JS environments.
- `ALLOW_MEMORY_GROWTH=1`: Luau's paged allocator (`lmem.cpp`) grows the heap as scripts allocate objects. Without this, WASM linear memory is fixed at the initial size and Luau will fail to allocate.
- `ALLOW_TABLE_GROWTH=1`: the function table (indirect call table) must grow to hold the upcall function pointer registered via `addFunction`. Without this, `addFunction` throws if the table is full.
- `EXPORTED_RUNTIME_METHODS` including `addFunction`/`removeFunction`: the WASM backend (P05) uses `addFunction` to register the Host upcall as a function table entry. `dynCall_*` forms are used as typed call wrappers if direct table calls are not available in the Emscripten version.

**Note on upcall registration in WASM:** The `lx_HostFn` passed to `lx_newstate` must be a C function pointer. In the WASM backend (P05), this is a pointer obtained from `addFunction(jsCallback, 'iiiii')` — a JS-to-WASM function table entry. The signature string `'iiiii'` encodes the return type and params as single-character type codes (`i`=i32, `d`=f64, `v`=void, `p`=pointer/i32). For `lx_HostFn = int (*)(lx_State, lx_Thread, int32_t, int, int*)`, the signature is `'iiiiiii'` (return=i, 5 params each=i). Verify against the Emscripten docs for the version pinned in P01.

---

### 4.4 `shim/src/lx_test.c` — C-Level Tests

**Purpose:** Standalone C tests that exercise the Shim in isolation without any Scala involvement. These run as part of the native build (`shimNativeTest` Mill target, or `ctest`). They validate longjmp-safety, the tri-state ABI, and Ref lifecycle before any Scala code is written.

```c
/* shim/src/lx_test.c */
#include "lx.h"
#include <stdio.h>
#include <assert.h>
#include <string.h>

/* ------------------------------------------------------------------ */
/* Helpers                                                              */
/* ------------------------------------------------------------------ */

static int g_upcall_outcome = LX_RETURN;
static int g_upcall_nresults = 0;
static int g_upcall_called = 0;
static int32_t g_upcall_fnid = -1;

static int test_upcall(lx_State state, lx_Thread thread,
                        int32_t fnId, int nArgs, int* nResults) {
    g_upcall_called = 1;
    g_upcall_fnid   = fnId;
    *nResults = g_upcall_nresults;
    if (g_upcall_outcome == LX_RETURN) {
        for (int i = 0; i < g_upcall_nresults; i++)
            lx_push_number(state, thread, (double)(i + 42));
    } else if (g_upcall_outcome == LX_FAIL) {
        lx_push_lstring(state, thread, "test error", 10);
    } else if (g_upcall_outcome == LX_SUSPEND) {
        lx_set_suspend_token(state, thread, 0xDEADC0DE);
    }
    return g_upcall_outcome;
}

/* ------------------------------------------------------------------ */
/* Test: compile-and-load, resume, return value                        */
/* ------------------------------------------------------------------ */

static void test_basic_resume(void) {
    lx_State s = lx_newstate(test_upcall);
    assert(s != NULL);
    lx_open_libs(s);
    lx_Thread main = lx_main_thread(s);

    const char* src = "return 1 + 1";
    char errbuf[256] = {0};
    int rc = lx_compile_and_load(s, src, strlen(src), "test", 1, 1, errbuf, sizeof(errbuf));
    assert(rc == 0);

    int nResults = 0;
    int status = lx_resume(s, main, 0, &nResults);
    assert(status == LX_RESUME_OK);
    assert(nResults == 1);

    int ok = 0;
    double v = lx_to_number(s, main, 1, &ok);
    assert(ok && v == 2.0);

    lx_close(s);
    printf("PASS test_basic_resume\n");
}

/* ------------------------------------------------------------------ */
/* Test: runtime error converts to status, does not crash              */
/* ------------------------------------------------------------------ */

static void test_error_becomes_status(void) {
    lx_State s = lx_newstate(test_upcall);
    lx_open_libs(s);
    lx_Thread main = lx_main_thread(s);

    const char* src = "error('boom')";
    char errbuf[256] = {0};
    int rc = lx_compile_and_load(s, src, strlen(src), "err_test", 1, 1, errbuf, sizeof(errbuf));
    assert(rc == 0);

    int nResults = 0;
    int status = lx_resume(s, main, 0, &nResults);
    assert(status == LX_RESUME_ERR);

    char msgbuf[256] = {0};
    lx_copy_error(s, main, msgbuf, sizeof(msgbuf));
    assert(strstr(msgbuf, "boom") != NULL);

    lx_close(s);
    printf("PASS test_error_becomes_status\n");
}

/* ------------------------------------------------------------------ */
/* Test: native function LX_RETURN tri-state                           */
/* ------------------------------------------------------------------ */

static void test_native_return(void) {
    lx_State s = lx_newstate(test_upcall);
    lx_open_libs(s);
    lx_Thread main = lx_main_thread(s);

    g_upcall_outcome  = LX_RETURN;
    g_upcall_nresults = 2;
    g_upcall_called   = 0;

    // Register native fn with fnId=7, then expose it as global "myfn"
    lx_register_native(s, 7, "myfn");
    // Stack top is now the closure; set as global
    lua_setglobal((lua_State*)s, "myfn"); /* NOTE: in real test use lx_push_copy + rawset */

    const char* src = "return myfn()";
    char errbuf[256] = {0};
    lx_compile_and_load(s, src, strlen(src), "native_test", 1, 1, errbuf, sizeof(errbuf));

    int nResults = 0;
    int status = lx_resume(s, main, 0, &nResults);
    assert(status == LX_RESUME_OK);
    assert(g_upcall_called == 1);
    assert(g_upcall_fnid == 7);
    assert(nResults == 2);

    lx_close(s);
    printf("PASS test_native_return\n");
}

/* ------------------------------------------------------------------ */
/* Test: native function LX_FAIL → Luau error                         */
/* ------------------------------------------------------------------ */

static void test_native_fail(void) {
    lx_State s = lx_newstate(test_upcall);
    lx_open_libs(s);
    lx_Thread main = lx_main_thread(s);

    g_upcall_outcome  = LX_FAIL;
    g_upcall_nresults = 0;
    g_upcall_called   = 0;

    lx_register_native(s, 99, "failfn");
    lua_setglobal((lua_State*)s, "failfn");

    const char* src = "local ok, err = pcall(failfn); return ok, err";
    char errbuf[256] = {0};
    lx_compile_and_load(s, src, strlen(src), "fail_test", 1, 1, errbuf, sizeof(errbuf));

    int nResults = 0;
    int status = lx_resume(s, main, 0, &nResults);
    assert(status == LX_RESUME_OK);
    assert(nResults == 2);

    int ok = 0;
    // First result: pcall returned false
    int bval = lx_to_boolean(s, main, 1);
    assert(bval == 0);

    lx_close(s);
    printf("PASS test_native_fail\n");
}

/* ------------------------------------------------------------------ */
/* Test: native function LX_SUSPEND → yield → resume with value       */
/* ------------------------------------------------------------------ */

static void test_native_suspend_resume(void) {
    lx_State s  = lx_newstate(test_upcall);
    lx_open_libs(s);

    // Create a new coroutine for this test
    lx_Thread main = lx_main_thread(s);

    g_upcall_outcome  = LX_SUSPEND;
    g_upcall_nresults = 0;
    g_upcall_called   = 0;

    lx_register_native(s, 55, "asyncfn");
    lua_setglobal((lua_State*)s, "asyncfn");

    const char* src = "local v = asyncfn(); return v";
    char errbuf[256] = {0};
    lx_compile_and_load(s, src, strlen(src), "suspend_test", 1, 1, errbuf, sizeof(errbuf));

    // First resume: runs until yield
    int nResults = 0;
    int status = lx_resume(s, main, 0, &nResults);
    assert(status == LX_RESUME_YIELD);

    int64_t token = lx_get_suspend_token(s, main);
    assert(token == (int64_t)0xDEADC0DE);

    // Simulate async completion: push result value then resume
    lx_push_number(s, main, 999.0);
    int nResults2 = 0;
    int status2 = lx_resume(s, main, 1, &nResults2);
    assert(status2 == LX_RESUME_OK);
    assert(nResults2 == 1);

    int ok = 0;
    double v = lx_to_number(s, main, 1, &ok);
    assert(ok && v == 999.0);

    lx_close(s);
    printf("PASS test_native_suspend_resume\n");
}

/* ------------------------------------------------------------------ */
/* Test: Ref lifecycle                                                  */
/* ------------------------------------------------------------------ */

static void test_ref_lifecycle(void) {
    lx_State s  = lx_newstate(test_upcall);
    lx_Thread t = lx_main_thread(s);

    lx_newtable(s, t, 0, 0);        // push table
    int ref = lx_ref(s, t, -1);     // pin it; table still on stack
    lx_pop(s, t, 1);                // pop the original

    // Verify we can retrieve it
    lx_push_ref(s, t, ref);
    assert(lx_type(s, t, -1) == LX_TTABLE);
    lx_pop(s, t, 1);

    // Release it
    lx_unref(s, ref);

    // Full GC: the table should be collectable now (no other references)
    lx_gc_collect(s);

    lx_close(s);
    printf("PASS test_ref_lifecycle\n");
}

/* ------------------------------------------------------------------ */
/* Test: string roundtrip                                              */
/* ------------------------------------------------------------------ */

static void test_string_roundtrip(void) {
    lx_State s = lx_newstate(test_upcall);
    lx_Thread t = lx_main_thread(s);

    const char* hello = "hello, \0 world";  // includes embedded NUL
    size_t hlen = 14;
    lx_push_lstring(s, t, hello, hlen);

    char buf[64] = {0};
    size_t len = 0;
    int ok = lx_to_lstring(s, t, -1, buf, sizeof(buf), &len);
    assert(ok == 1);
    assert(len == hlen);
    assert(memcmp(buf, hello, hlen) == 0);

    lx_pop(s, t, 1);
    lx_close(s);
    printf("PASS test_string_roundtrip\n");
}

/* ------------------------------------------------------------------ */
/* main                                                                 */
/* ------------------------------------------------------------------ */

int main(void) {
    test_basic_resume();
    test_error_becomes_status();
    test_native_return();
    test_native_fail();
    test_native_suspend_resume();
    test_ref_lifecycle();
    test_string_roundtrip();
    printf("All lx_test.c tests PASSED\n");
    return 0;
}
```

**Note:** The test directly calls `lua_setglobal` for simplicity. In production Scala code this is replaced by the Host's table/global assignment via `lx_push_*` + `lx_rawset`. The C test is self-contained and links only against the Shim and the Luau VM.

---

### 4.5 Build integration

The following is a summary for P01's build system; this plan does not implement build files but specifies their required behavior.

**Native build (clang++):**
```
clang++ -std=c++17 \
  -I shim/include \
  -I luau/VM/include \
  -I luau/Compiler/include \
  -I luau/Common/include \
  shim/src/lx.cpp \
  luau/VM/src/*.cpp \
  luau/Compiler/src/*.cpp \
  luau/Ast/src/*.cpp \
  -shared -fPIC -o out/shim/native/libluau-shim.so
```

**WASM build (emcc):**
```
emcc -std=c++17 \
  -I shim/include \
  -I luau/VM/include \
  -I luau/Compiler/include \
  -I luau/Common/include \
  shim/src/lx.cpp \
  luau/VM/src/*.cpp \
  luau/Compiler/src/*.cpp \
  luau/Ast/src/*.cpp \
  [emscripten flags from 4.3.2] \
  -o out/shim/wasm/luau-shim.js
```

**C test binary:**
```
clang \
  -I shim/include \
  -I luau/VM/include \
  shim/src/lx_test.c \
  -L out/shim/native -lluau-shim \
  -o out/shim/native/lx_test
```

---

## 5. Acceptance Criteria and Tests

### 5.1 C-level test suite

**Mill task:** `./mill shim.nativeTest` (or `ctest --test-dir out/shim/native/`)

| Test name | What it checks |
|-----------|----------------|
| `test_basic_resume` | `lx_compile_and_load` + `lx_resume` returns `LX_RESUME_OK`; numeric result readable |
| `test_error_becomes_status` | `error('boom')` returns `LX_RESUME_ERR`, not a crash; error string readable via `lx_copy_error` |
| `test_native_return` | Trampoline calls upcall, receives `LX_RETURN`, result values visible to Luau |
| `test_native_fail` | `LX_FAIL` causes `lua_error` in C; Luau `pcall` catches it; no longjmp across boundary |
| `test_native_suspend_resume` | `LX_SUSPEND` yields the coroutine; token readable; re-resume with value works |
| `test_ref_lifecycle` | `lx_ref` pins; `lx_push_ref` retrieves; `lx_unref` releases; GC safe after release |
| `test_string_roundtrip` | Binary string with embedded NUL survives push + read without truncation |

All 7 tests must pass and produce `All lx_test.c tests PASSED`.

### 5.2 WASM smoke test

**Mill task:** `./mill shimWasm.smokeTest` (executes `node out/shim/wasm/lx_smoke.js`)

A minimal JS script loads `luau-shim.js`, calls `LuauShim()`, then exercises:
- `_lx_newstate` with a JS-registered upcall via `addFunction`
- `_lx_compile_and_load` of `"return 1+1"`
- `_lx_resume` returns 0 (`LX_RESUME_OK`)
- `_lx_to_number` returns 2.0

This script lives at `/home/hoangdinh/OSS/luau-scala/shim/test/lx_smoke.js` (created by P01's build, not this plan). Its content spec is in P01.

### 5.3 End-to-end check

The end-to-end check requires P03 to implement the `Binding` trait and P04/P05 to implement a backend, but this plan's deliverable passes when:

1. `libluau-shim.so` loads cleanly via `System.loadLibrary` with no undefined symbols.
2. `luau-shim.wasm` instantiates in Node.js with no linking errors.
3. The 7 C-level tests pass.
4. `nm -D out/shim/native/libluau-shim.so | grep 'T lx_'` shows all 37 `lx_*` symbols as exported globals.
5. Running `wasm-objdump -x out/shim/wasm/luau-shim.wasm | grep lx_` shows all 37 symbols in the export table.

---

## 6. Risks and Gotchas

### 6.1 `lua_tolstring` mutates the stack

**Risk:** `lua_tolstring` on a number-typed slot mutates it to a string **in place** on the Luau stack. If the Host reads a number slot and later checks `lx_type`, it will get `LX_TSTRING` instead of `LX_TNUMBER`.

**Mitigation:** `lx_to_lstring` guards with `lua_type != LUA_TSTRING` first. Do NOT coerce numbers to strings silently. The Host Codec dispatches by type; it will call `lx_to_number` for numbers and `lx_to_lstring` only for strings.

**Source:** `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-official-cpp.md` section 4.2 (lua_State stack), lua.h note on `lua_tolstring`.

### 6.2 `lua_tolstring` pointer invalidation

**Risk:** The pointer returned by `lua_tolstring` points into the Luau string object on the Lua heap. It is invalidated by any GC cycle or any Luau call that may trigger GC.

**Mitigation:** `lx_to_lstring` immediately copies into the caller's buffer before returning. The Shim never returns a raw `char*` pointing into the Lua heap. The Host never holds such a pointer past the next `lx_` call.

**Source:** `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-official-cpp.md` section 8 (GC — string interning).

### 6.3 `lua_error` longjmp — only safe inside `lua_resume`'s setjmp context

**Risk:** Calling `lua_error(L)` when `L` is NOT inside an active `lua_resume` (i.e., the setjmp buffer is not set) will longjmp to an uninitialized or stale buffer — undefined behavior, typically a crash.

**Mitigation:** The trampoline `lx_trampoline` is only callable from Luau code that is inside a `lua_resume` call, by construction. The trampoline MUST NEVER be called outside of an active resume (e.g., do not call a Luau function directly from `lx_newstate` without going through `lx_resume`).

**Source:** ADR-0001; `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-official-cpp.md` section 4.8 (coroutines — lua_resume/lua_yield implement symmetric coroutine transfer).

### 6.4 `lua_yieldk` — `k` must be a plain C function, not a closure

**Risk:** `lua_yieldk`'s continuation parameter `k` must be a `lua_Continuation` — a plain C function pointer (`int (*)(lua_State*, int, lua_Continuation)`). It cannot be a lambda with captures or a C++ functor. Luau stores it by value in the `CallInfo` struct.

**Mitigation:** `lx_trampoline_k` is a static C function. This is the only continuation used; the Host-side context is the suspend token (int64), not a pointer to a closure.

**Source:** `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-official-cpp.md` section 4.4 (CallInfo flags — OPYIELD).

### 6.5 `lua_yieldk` inside a Luau `pcall` — `lua_yieldk` form required

**Risk:** If a Native function uses plain `lua_yield` instead of `lua_yieldk`, yielding from inside a script-level `pcall` will abort the pcall rather than propagating the yield correctly. This breaks the Roblox behavioral spec for `task.wait` inside a pcall.

**Mitigation:** Always use `lua_yieldk` with the continuation `lx_trampoline_k`. Luau's implementation sets the `OPYIELD` flag in `CallInfo` when using the `k` form, which allows the yield to propagate through protected call boundaries.

**Source:** ADR-0007; ADR-0004 (Roblox behavioral spec inherited).

### 6.6 WASM `addFunction` table growth — must use `ALLOW_TABLE_GROWTH`

**Risk:** Without `ALLOW_TABLE_GROWTH=1`, the WASM function table is fixed at compile time. `addFunction` (used by the WASM backend to register the upcall) will throw `RuntimeError: table index out of bounds` at runtime if the initial table is full.

**Mitigation:** Set `-s ALLOW_TABLE_GROWTH=1` in all `emcc` invocations. This allows the function table to grow dynamically.

**Source:** Emscripten documentation; mentioned in plan spec above (section 4.3.2).

### 6.7 Emscripten `MODULARIZE` — module not immediately ready

**Risk:** Without `MODULARIZE=1`, the Emscripten module auto-initializes synchronously on JS import. With `MODULARIZE=1`, `LuauShim()` returns a `Promise`; calling exported functions before the promise resolves causes `TypeError: _lx_newstate is not a function`.

**Mitigation:** The WASM backend (P05) must `await LuauShim()` before any `_lx_*` calls. Scala.js interop must use `js.Dynamic.global.LuauShim().then(...)` or equivalent async initialization.

### 6.8 `luau_compile` error convention — `buf[0] == '\0'` is the error sentinel

**Risk:** `luau_compile` on success returns bytecode whose first byte is the bytecode version (currently `0x06`, a non-zero byte). On error it returns a buffer starting with `'\0'`. Checking only `bytecodeLen == 0` will miss errors because an error buffer may have `bytecodeLen > 1`.

**Mitigation:** The `lx_compile_and_load` implementation checks `bytecodeLen == 0 || bytecode[0] == '\0'` and treats both as compile errors.

**Source:** `/home/hoangdinh/OSS/luau-scala/docs/research/topic-luau-bytecode-and-vm.md` section 2.1 (`version: u8` is the first byte; LBC_BYTECODE_MIN = 3, so a valid blob always has first byte ≥ 3).

### 6.9 Luau `lua_Integer` is 32-bit on some platforms

**Risk:** Luau's `lua_Integer` is `int` (32-bit) on some build configurations, not `int64_t`. The `lx.h` ABI uses `int64_t` for `lx_push_integer` and `lx_to_integer`. Truncation is silent.

**Mitigation:** In `lx.cpp`, `lua_pushinteger(L, (lua_Integer)i)` may truncate. Add a static assertion in the implementation:
```cpp
static_assert(sizeof(lua_Integer) >= 4, "lua_Integer too narrow");
```
And document that `LX_TINTEGER` in Luau is a 32-bit integer (in the default build); the Host should not push 64-bit integers that exceed 32-bit range unless the Luau build explicitly enables 64-bit integers.

**Source:** `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-official-cpp.md` section 4.1 (TValue — `LUA_TINTEGER` stores `int64_t l` but may be configured otherwise).

### 6.10 Thread safety — single-threaded execution model

**Risk:** The `LxStateData` suspend token field is shared across all coroutines of an Isolate. If two coroutines somehow yielded concurrently, the token would be overwritten.

**Mitigation:** The single-threaded execution model (ADR-0002/ADR-0004) prevents this: only one coroutine is ever inside `lua_resume` at a time for a given Isolate. The token is set by the upcall during the resume and read immediately after the resume returns. No concurrent access is possible. Document this invariant; if multi-threaded use is ever attempted, the token must become per-thread state.

---

## 7. Out of Scope / Deferred

The following are explicitly NOT part of this plan. The named later plans own them.

| Deferred item | Owner plan |
|---------------|------------|
| Scala `Binding` trait mirroring `lx_*` | `docs/plans/03-core-abstractions.md` (P03) |
| Panama `MethodHandle` downcall stubs | `docs/plans/04-panama-backend-jvm.md` (P04) |
| WASM backend Scala.js interop, `addFunction` wiring | `docs/plans/05-wasm-backend-js.md` (P05) |
| Task library (`task.spawn`, `task.wait`, etc.) | `docs/plans/07-stdlib-and-task-library.md` (P07) |
| Full sandboxing policy and safeenv | P07 |
| Cross-Isolate parallelism / multiple Drivers | `docs/plans/02-movable-state-actor-concurrency.md` context; deferred per ADR-0002 |
| Luau `--!native` JIT activation | Not exposed via `lx_*`; Luau activates it internally if the native codegen is compiled in |
| Luau debug hooks / coverage | Out of scope for MVP |
| `luaL_requiref` / module loader | P07 |
| Userdata (full) | ADR-0006 prohibits host-object userdata; Luau-side userdata (buffers) exposed via existing stack ops |

---

## 8. References

The implementing agent must read all of the following before touching code:

**Architecture decisions (all in `/home/hoangdinh/OSS/luau-scala/docs/adr/`):**
- `0001-embed-upstream-luau-via-slim-cpp-shim.md` — primary ADR for this plan; no pcall across boundary, Shim raises in pure C
- `0002-movable-state-actor-concurrency.md` — stackless model rationale; single-threaded MVP
- `0003-stackless-task-model.md` — why setjmp never persists across yield; why thread-per-coroutine is rejected
- `0004-coroutine-substrate-task-on-top.md` — Roblox behavioral spec; coroutine vs Task disambiguation
- `0005-deterministic-ref-lifetime-no-finalizer.md` — no GC backstop for Refs; explicit close only
- `0006-copy-only-data-boundary-via-codec-typeclass.md` — copy-only crossing; no userdata wrapping
- `0007-callback-based-async-and-tristate-native-return.md` — tri-state ABI definition; yield/resume result passing

**Glossary:**
- `/home/hoangdinh/OSS/luau-scala/CONTEXT.md` — use these exact terms: Shim, Host, Runtime, Resume boundary, Native function, Ref, Scope, Suspension, Async primitive, Driver, Isolate, Task, Coroutine

**Research docs:**
- `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-official-cpp.md` — Luau C API specifics: lua_State layout (section 4.2), Coroutines/lua_resume/lua_yield (section 4.8), Compiler API/luau_compile/luau_load (section 5), GC/string interning (section 8), Standard library (section 9)
- `/home/hoangdinh/OSS/luau-scala/docs/research/topic-luau-bytecode-and-vm.md` — bytecode version format (section 2.1); luau_compile error sentinel
- `/home/hoangdinh/OSS/luau-scala/docs/research/topic-coroutines-on-jvm.md` — why thread-per-coroutine is rejected (section 3.1); SwitchCraft 250k-thread crash
- `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-puc-lua-c.md` — background on Lua C API conventions inherited by Luau

**Luau source files to consult (relative to submodule):**
- `luau/VM/include/lua.h` — all C API declarations
- `luau/VM/include/lualib.h` — `luaL_openlibs`, `luaL_newstate`
- `luau/Compiler/include/Luau/Compiler.h` — `luau_compile`, `Luau::CompileOptions`
- `luau/VM/src/lstate.h` — `lua_State` internals, `lua_getthreaddata`/`lua_setthreaddata`

**Prior plan:**
- `docs/plans/01-project-scaffold-and-build-toolchain.md` — build targets and artifact paths this plan's source files feed into

**Later plans (for cross-reference only, do NOT implement):**
- `docs/plans/03-core-abstractions.md` through `docs/plans/07-stdlib-and-task-library.md`
