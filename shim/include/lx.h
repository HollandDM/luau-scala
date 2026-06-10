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

/**
 * Create a new coroutine thread within the state. Returns lx_Thread.
 * The new thread starts in SUSPENDED state with an empty stack.
 * The caller must push the target function and arguments onto the new
 * thread's stack before calling lx_resume.
 */
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

/* ------------------------------------------------------------------ */
/* Standard libraries                                                   */
/* ------------------------------------------------------------------ */

/* Bitmask values for lx_openlibs mask parameter */
#define LX_LIB_BASE      (1u << 0)
#define LX_LIB_MATH      (1u << 1)
#define LX_LIB_STRING    (1u << 2)
#define LX_LIB_TABLE     (1u << 3)
#define LX_LIB_BIT32     (1u << 4)
#define LX_LIB_UTF8      (1u << 5)
#define LX_LIB_OS        (1u << 6)
#define LX_LIB_COROUTINE (1u << 7)
#define LX_LIB_VECTOR    (1u << 8)
#define LX_LIB_BUFFER    (1u << 9)
#define LX_LIB_DEBUG     (1u << 10)
#define LX_LIB_STANDARD  (LX_LIB_BASE | LX_LIB_MATH | LX_LIB_STRING | LX_LIB_TABLE | \
                          LX_LIB_BIT32 | LX_LIB_UTF8 | LX_LIB_OS | LX_LIB_COROUTINE | \
                          LX_LIB_VECTOR | LX_LIB_BUFFER)

/**
 * Open standard libraries selected by mask into the given state.
 * Returns 0 on success. Must be called before lx_sandbox.
 */
int  lx_openlibs(lx_State state, uint32_t mask);

/**
 * Null out unsafe globals (io, os.execute, os.exit, os.getenv, package,
 * dofile, loadfile) and freeze the global table via luaL_sandbox.
 * Must be called once, after all libraries and host tables are installed.
 */
void lx_sandbox(lx_State state);

/**
 * Open the safe Luau standard libraries (legacy, no mask).
 * Excludes: io, os.execute, os.exit, os.getenv, package, require.
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

/* ------------------------------------------------------------------ */
/* Global access                                                        */
/* ------------------------------------------------------------------ */

/**
 * Pop the value at the top of the main thread's stack and assign it to
 * the named global. Wraps lua_setglobal.
 */
void lx_set_global(lx_State state, const char* name);

/**
 * Push the named global onto the main thread's stack.
 * Wraps lua_getglobal.
 */
void lx_get_global(lx_State state, const char* name);

/* ------------------------------------------------------------------ */
/* Resume with error injection                                          */
/* ------------------------------------------------------------------ */

/**
 * Resume a yielded thread by raising the value at the top of its stack
 * as an error inside it. Used by the Host to fail a Suspension: a script
 * pcall around the suspension point observes the failure. Wraps
 * lua_resumeerror; returns the same LX_RESUME_* codes as lx_resume.
 */
int lx_resume_error(lx_State state, lx_Thread thread, int* nResults);

/* ------------------------------------------------------------------ */
/* Conformance-harness environment                                      */
/* ------------------------------------------------------------------ */

/**
 * Set up the script environment the upstream Luau conformance tests
 * expect (mirrors tests/Conformance.test.cpp runConformance):
 *   - registers globals: collectgarbage, loadstring, is_native,
 *     is_native_if_supported, makelud, and (if silencePrint) a no-op print
 *   - luaL_sandbox + luaL_sandboxthread
 *   - sets _G to the thread's global environment
 * Call once after lx_openlibs and before lx_compile_and_load.
 */
void lx_conformance_setup(lx_State state, int silencePrint);

#ifdef __cplusplus
} /* extern "C" */
#endif
#endif /* LX_H */
