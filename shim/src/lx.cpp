extern "C" {
#include "lx.h"
}
#include "lua.h"
#include "lualib.h"
#include "luacode.h"
#include "Luau/Common.h"
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <cstdio>
#include <cmath>

extern "C" {

// Per-state metadata not held by lua_State itself.
struct LxStateData {
    lx_HostFn  upcall;
    int64_t    suspendToken;
};

// Access LxStateData from any lua_State in this global_State via thread data.
static inline LxStateData* get_state_data(lua_State* L) {
    return static_cast<LxStateData*>(lua_getthreaddata(lua_mainthread(L)));
}

// Convenience cast from lx_Thread to lua_State*
#define T(thread) (static_cast<lua_State*>(thread))

// -----------------------------------------------------------------------
// Continuation for the Suspend path.
// Called by Luau when the coroutine is resumed after lx_trampoline yielded.
// Returns the number of resume arg values on the stack.
// -----------------------------------------------------------------------
static int lx_trampoline_k(lua_State* L, int /*status*/) {
    return lua_gettop(L);
}

// -----------------------------------------------------------------------
// Main trampoline body.
// A lua_CFunction installed as a C closure with one upvalue (fnId).
// Called by Luau when the script invokes a registered Native function.
// -----------------------------------------------------------------------
static int lx_trampoline(lua_State* L) {
    int fnId = (int)lua_tointeger(L, lua_upvalueindex(1));

    LxStateData* d = get_state_data(L);
    int nArgs = lua_gettop(L);
    lua_State* main = lua_mainthread(L);

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
            // Lua's call machinery takes the top nResults values as returns.
            return nResults;

        case LX_FAIL:
            // Host pushed one error value.
            // lua_error longjmps within C; safe here inside lua_resume's setjmp.
            lua_error(L);
            return 0;

        case LX_SUSPEND:
            // Host called lx_set_suspend_token before returning.
            // Yield from C; on resume Luau calls lx_trampoline_k (set via
            // lua_pushcclosurek's continuation).
            return lua_yield(L, 0);

        default:
            lua_pushliteral(L, "lx: unknown upcall outcome");
            lua_error(L);
            return 0;
    }
}

// -----------------------------------------------------------------------
// State lifecycle
// -----------------------------------------------------------------------

lx_State lx_newstate(lx_HostFn upcall) {
    lua_State* L = luaL_newstate();
    if (!L) return nullptr;
    LxStateData* d = new LxStateData{upcall, 0};
    if (!d) { lua_close(L); return nullptr; }
    lua_setthreaddata(L, d);
    return static_cast<lx_State>(L);
}

void lx_close(lx_State state) {
    lua_State* L = static_cast<lua_State*>(state);
    LxStateData* d = get_state_data(L);
    lua_close(L);
    delete d;
}

lx_Thread lx_main_thread(lx_State state) {
    lua_State* L = static_cast<lua_State*>(state);
    return static_cast<lx_Thread>(lua_mainthread(L));
}

lx_Thread lx_new_thread(lx_State state) {
    lua_State* L  = static_cast<lua_State*>(state);
    lua_State* co = lua_newthread(L);
    // Thread ref is left on L's stack; caller may pop if unused.
    return static_cast<lx_Thread>(co);
}

int lx_thread_status(lx_Thread thread) {
    lua_State* co = static_cast<lua_State*>(thread);
    // lua_costatus needs a "current thread" perspective; the main thread of
    // co's VM reproduces the old (state, thread) behaviour for every caller.
    int s = lua_costatus(lua_mainthread(co), co);
    switch (s) {
        case LUA_CORUN:  return 0;
        case LUA_COSUS:  return 1;
        case LUA_CONOR:  return 3;
        case LUA_COFIN: return 2;
        default:         return 2;
    }
}

// -----------------------------------------------------------------------
// Compile and load
// -----------------------------------------------------------------------

int lx_compile_and_load(
    lx_State state, const char* source, size_t sourceLen,
    const char* chunkname, int optLevel, int debugLevel,
    char* errbuf, size_t errbufsz)
{
    lua_State* L = static_cast<lua_State*>(state);

    lua_CompileOptions opts;
    memset(&opts, 0, sizeof(opts));
    opts.optimizationLevel = optLevel;
    opts.debugLevel        = debugLevel;

    size_t bytecodeLen = 0;
    char* bytecode = luau_compile(source, sourceLen, &opts, &bytecodeLen);
    if (!bytecode) {
        snprintf(errbuf, errbufsz, "luau_compile: allocation failure");
        return 1;
    }
    if (bytecodeLen == 0 || bytecode[0] == '\0') {
        const char* msg = (bytecodeLen > 1) ? bytecode + 1 : "unknown compile error";
        snprintf(errbuf, errbufsz, "%s", msg);
        free(bytecode);
        return 1;
    }

    int rc = luau_load(L, chunkname ? chunkname : "?", bytecode, bytecodeLen, 0);
    free(bytecode);
    if (rc != 0) {
        size_t msglen = 0;
        const char* msg = lua_tolstring(L, -1, &msglen);
        if (msg)
            snprintf(errbuf, errbufsz, "%.*s", (int)msglen, msg);
        else
            snprintf(errbuf, errbufsz, "luau_load: unknown error");
        lua_pop(L, 1);
        return 1;
    }
    return 0;
}

// -----------------------------------------------------------------------
// Resume boundary
// -----------------------------------------------------------------------

static int lx_map_resume_status(lua_State* co, int status, int* nResults) {
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
            *nResults = 0;
            return LX_RESUME_ERR;
    }
}

int lx_resume(lx_Thread thread, int nArgs, int* nResults) {
    lua_State* co = static_cast<lua_State*>(thread);
    return lx_map_resume_status(co, lua_resume(co, nullptr, nArgs), nResults);
}

int lx_resume_error(lx_Thread thread, int* nResults) {
    lua_State* co = static_cast<lua_State*>(thread);
    // Resumes the yielded thread by raising the value at the top of its
    // stack as an error inside it (a script pcall around the suspension
    // point observes the failure). Wraps lua_resumeerror.
    return lx_map_resume_status(co, lua_resumeerror(co, nullptr), nResults);
}

// -----------------------------------------------------------------------
// Push/pop operations
// -----------------------------------------------------------------------

void lx_push_nil    (lx_Thread t) { lua_pushnil(T(t)); }
void lx_push_boolean(lx_Thread t, int b) { lua_pushboolean(T(t), b); }
void lx_push_number (lx_Thread t, double n) { lua_pushnumber(T(t), n); }
void lx_push_integer(lx_Thread t, int64_t i) { lua_pushinteger(T(t), (lua_Integer)i); }
void lx_push_lstring(lx_Thread t, const char* b, size_t l) { lua_pushlstring(T(t), b, l); }
void lx_push_ref    (lx_Thread t, int ref) { lua_getref(T(t), ref); }
void lx_push_copy   (lx_Thread t, int idx) { lua_pushvalue(T(t), idx); }
void lx_pop         (lx_Thread t, int n)   { lua_pop(T(t), n); }
int  lx_stack_top   (lx_Thread t)          { return lua_gettop(T(t)); }

// -----------------------------------------------------------------------
// Non-raising read accessors
// -----------------------------------------------------------------------

int lx_type(lx_Thread t, int idx) {
    return lua_type(T(t), idx);
}

double lx_to_number(lx_Thread t, int idx, int* ok) {
    int isnum = 0;
    double v = lua_tonumberx(T(t), idx, &isnum);
    *ok = isnum;
    return v;
}

int64_t lx_to_integer(lx_Thread t, int idx, int* ok) {
    int isok = 0;
    lua_Integer v = lua_tointegerx(T(t), idx, &isok);
    *ok = isok;
    return (int64_t)v;
}

int lx_to_boolean(lx_Thread t, int idx) {
    return lua_toboolean(T(t), idx);
}

int lx_to_lstring(lx_Thread t, int idx,
                   char* dst, size_t dstlen, size_t* len) {
    if (lua_type(T(t), idx) != LUA_TSTRING) { *len = 0; return 0; }
    size_t slen = 0;
    const char* p = lua_tolstring(T(t), idx, &slen);
    *len = slen;
    if (dst && dstlen > 0) {
        size_t copy = (slen < dstlen - 1) ? slen : dstlen - 1;
        memcpy(dst, p, copy);
        dst[copy] = '\0';
    }
    return 1;
}

size_t lx_rawlen(lx_Thread t, int idx) {
    return (size_t)lua_objlen(T(t), idx);
}

// -----------------------------------------------------------------------
// Table operations
// -----------------------------------------------------------------------

void lx_newtable(lx_Thread t, int narr, int nrec) {
    lua_createtable(T(t), narr, nrec);
}

void lx_rawget(lx_Thread t, int tidx) {
    lua_rawget(T(t), tidx);
}

void lx_rawset(lx_Thread t, int tidx) {
    lua_rawset(T(t), tidx);
}

void lx_rawgeti(lx_Thread t, int tidx, int n) {
    lua_rawgeti(T(t), tidx, n);
}

void lx_rawseti(lx_Thread t, int tidx, int n) {
    lua_rawseti(T(t), tidx, n);
}

int lx_table_next(lx_Thread t, int tidx) {
    return lua_next(T(t), tidx);
}

void lx_setarray(lx_Thread t, int tidx, int startIdx, int count) {
    lua_State* L = T(t);
    int base = lua_gettop(L) - count + 1;
    for (int i = 0; i < count; i++) {
        lua_pushvalue(L, base + i);
        lua_rawseti(L, tidx, startIdx + i);
    }
}

// -----------------------------------------------------------------------
// Registry Refs
// -----------------------------------------------------------------------

int lx_ref(lx_Thread t, int idx) {
    return lua_ref(T(t), idx);
}

void lx_unref(lx_State s, int ref) {
    lua_State* L = static_cast<lua_State*>(s);
    lua_unref(L, ref);
}

// -----------------------------------------------------------------------
// Native function registration
// -----------------------------------------------------------------------

void lx_register_native(lx_State state, int32_t fnId, const char* debugname) {
    lua_State* L = static_cast<lua_State*>(state);
    lua_pushinteger(L, fnId);
    lua_pushcclosurek(L, lx_trampoline, debugname ? debugname : "lx_fn", 1, lx_trampoline_k);
}

// -----------------------------------------------------------------------
// Suspend token
// -----------------------------------------------------------------------

void lx_set_suspend_token(lx_Thread t, int64_t token) {
    LxStateData* d = get_state_data(static_cast<lua_State*>(t));
    d->suspendToken = token;
}

int64_t lx_get_suspend_token(lx_Thread t) {
    LxStateData* d = get_state_data(static_cast<lua_State*>(t));
    return d->suspendToken;
}

// -----------------------------------------------------------------------
// Global access
// -----------------------------------------------------------------------

void lx_set_global(lx_State state, const char* name) {
    lua_State* L = static_cast<lua_State*>(state);
    lua_setglobal(L, name);
}

void lx_get_global(lx_State state, const char* name) {
    lua_State* L = static_cast<lua_State*>(state);
    lua_getglobal(L, name);
}

// -----------------------------------------------------------------------
// Standard libraries
// -----------------------------------------------------------------------

int lx_openlibs(lx_State state, uint32_t mask) {
    lua_State* L = static_cast<lua_State*>(state);
    // luaopen_* leave their library table on the stack; restore the caller's
    // stack height once registration into globals is done.
    int base = lua_gettop(L);

    if (mask & LX_LIB_BASE)      luaopen_base(L);
    if (mask & LX_LIB_MATH)      luaopen_math(L);
    if (mask & LX_LIB_STRING)    luaopen_string(L);
    if (mask & LX_LIB_TABLE)     luaopen_table(L);
    if (mask & LX_LIB_BIT32)     luaopen_bit32(L);
    if (mask & LX_LIB_UTF8)      luaopen_utf8(L);
    if (mask & LX_LIB_COROUTINE) luaopen_coroutine(L);
    if (mask & LX_LIB_VECTOR)    luaopen_vector(L);
    if (mask & LX_LIB_BUFFER)    luaopen_buffer(L);
    if (mask & LX_LIB_DEBUG)     luaopen_debug(L);
    if (mask & LX_LIB_OS) {
        luaopen_os(L);
        lua_getglobal(L, "os");
        lua_pushnil(L); lua_setfield(L, -2, "execute");
        lua_pushnil(L); lua_setfield(L, -2, "exit");
        lua_pushnil(L); lua_setfield(L, -2, "getenv");
        lua_pop(L, 1);
    }

    lua_pushnil(L); lua_setglobal(L, "dofile");
    lua_pushnil(L); lua_setglobal(L, "loadfile");
    lua_pushnil(L); lua_setglobal(L, "require");
    lua_pushnil(L); lua_setglobal(L, "io");
    lua_pushnil(L); lua_setglobal(L, "package");

    lua_settop(L, base);
    return 0;
}

void lx_sandbox(lx_State state) {
    lua_State* L = static_cast<lua_State*>(state);
    luaL_sandbox(L);
}

void lx_open_libs(lx_State state) {
    lx_openlibs(state, LX_LIB_STANDARD);
}

// -----------------------------------------------------------------------
// GC helpers and error string
// -----------------------------------------------------------------------

void lx_gc_step(lx_State state, int stepsize) {
    lua_State* L = static_cast<lua_State*>(state);
    lua_gc(L, LUA_GCSTEP, stepsize);
}

void lx_gc_collect(lx_State state) {
    lua_State* L = static_cast<lua_State*>(state);
    lua_gc(L, LUA_GCCOLLECT, 0);
}

size_t lx_copy_error(lx_Thread t,
                      char* errbuf, size_t errbufsz) {
    if (!errbuf || errbufsz == 0) return 0;
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

// -----------------------------------------------------------------------
// Conformance-harness environment
// Replicates the script environment of the upstream Luau conformance
// runner (tests/Conformance.test.cpp runConformance): the extra globals
// scripts expect, then sandbox + sandboxthread + _G. Host calls this
// once after lx_openlibs and before lx_compile_and_load.
// -----------------------------------------------------------------------

static int conf_collectgarbage(lua_State* L) {
    const char* option = luaL_optstring(L, 1, "collect");
    int data = luaL_optinteger(L, 2, 0);

    int what = -1;
    int boolResult = 0;
    if      (strcmp(option, "collect") == 0)     what = LUA_GCCOLLECT;
    else if (strcmp(option, "count") == 0)       what = LUA_GCCOUNT;
    else if (strcmp(option, "stop") == 0)        what = LUA_GCSTOP;
    else if (strcmp(option, "restart") == 0)     what = LUA_GCRESTART;
    else if (strcmp(option, "step") == 0)        { what = LUA_GCSTEP; boolResult = 1; }
    else if (strcmp(option, "isrunning") == 0)   { what = LUA_GCISRUNNING; boolResult = 1; }
    else if (strcmp(option, "setgoal") == 0)     what = LUA_GCSETGOAL;
    else if (strcmp(option, "setstepmul") == 0)  what = LUA_GCSETSTEPMUL;
    else if (strcmp(option, "setstepsize") == 0) what = LUA_GCSETSTEPSIZE;
    else luaL_error(L, "collectgarbage: unsupported option '%s'", option);

    int res = lua_gc(L, what, data);
    if (boolResult) lua_pushboolean(L, res);
    else            lua_pushnumber(L, res);
    return 1;
}

static int conf_loadstring(lua_State* L) {
    size_t len = 0;
    const char* source = luaL_checklstring(L, 1, &len);
    const char* chunkname = luaL_optstring(L, 2, source);

    // The chunk produced by loadstring must not inherit the safeenv bit.
    lua_setsafeenv(L, LUA_ENVIRONINDEX, false);

    size_t bytecodeLen = 0;
    char* bytecode = luau_compile(source, len, nullptr, &bytecodeLen);
    int rc = luau_load(L, chunkname, bytecode, bytecodeLen, 0);
    free(bytecode);
    if (rc == 0)
        return 1; // compiled closure
    lua_pushnil(L);
    lua_insert(L, -2);
    return 2; // nil, error message
}

static int conf_silence(lua_State* L) {
    return 0;
}

static int conf_false(lua_State* L) {
    lua_pushboolean(L, 0);
    return 1;
}

// This embedding never runs codegen, so "native if supported" is vacuously
// satisfied (mirrors the upstream helper's !codegen_supported branch).
static int conf_true(lua_State* L) {
    lua_pushboolean(L, 1);
    return 1;
}

// ---- vector test helpers: Magnitude/Unit properties, Dot/Cross methods ----

static int conf_vector_dot(lua_State* L) {
    const float* a = luaL_checkvector(L, 1);
    const float* b = luaL_checkvector(L, 2);
    lua_pushnumber(L, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]);
    return 1;
}

static int conf_vector_cross(lua_State* L) {
    const float* a = luaL_checkvector(L, 1);
    const float* b = luaL_checkvector(L, 2);
    lua_pushvector(L,
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]);
    return 1;
}

static int conf_vector_index(lua_State* L) {
    const float* v = luaL_checkvector(L, 1);
    const char* name = luaL_checkstring(L, 2);

    // Component access: once an __index metamethod is installed, dynamic
    // string indexing routes here, so serve both lower- and uppercase.
    if (name[0] != '\0' && name[1] == '\0') {
        switch (name[0]) {
            case 'x': case 'X': lua_pushnumber(L, v[0]); return 1;
            case 'y': case 'Y': lua_pushnumber(L, v[1]); return 1;
            case 'z': case 'Z': lua_pushnumber(L, v[2]); return 1;
        }
    }
    if (strcmp(name, "Magnitude") == 0) {
        lua_pushnumber(L, sqrtf(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]));
        return 1;
    }
    if (strcmp(name, "Unit") == 0) {
        float inv = 1.0f / sqrtf(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        lua_pushvector(L, v[0] * inv, v[1] * inv, v[2] * inv);
        return 1;
    }
    if (strcmp(name, "Dot") == 0) {
        lua_pushcfunction(L, conf_vector_dot, nullptr);
        return 1;
    }
    if (strcmp(name, "Cross") == 0) {
        lua_pushcfunction(L, conf_vector_cross, nullptr);
        return 1;
    }
    // Match the VM's own unknown-member message (vector_library.luau
    // asserts on its exact wording).
    luaL_error(L, "attempt to index vector with '%s'", name);
}

static int conf_vector_namecall(lua_State* L) {
    if (const char* name = lua_namecallatom(L, nullptr)) {
        if (strcmp(name, "Dot") == 0)
            return conf_vector_dot(L);
        if (strcmp(name, "Cross") == 0)
            return conf_vector_cross(L);
    }
    luaL_error(L, "%s is not a valid method of vector", luaL_checkstring(L, 1));
}

static void conf_install_vector_metatable(lua_State* L) {
    lua_pushvector(L, 0.0f, 0.0f, 0.0f);
    luaL_newmetatable(L, "vector");

    lua_pushstring(L, "__index");
    lua_pushcfunction(L, conf_vector_index, nullptr);
    lua_settable(L, -3);

    lua_pushstring(L, "__namecall");
    lua_pushcfunction(L, conf_vector_namecall, nullptr);
    lua_settable(L, -3);

    lua_setreadonly(L, -1, true);
    lua_setmetatable(L, -2);
    lua_pop(L, 1);
}

static void conf_enable_fflag(const char* name) {
    for (Luau::FValue<bool>* flag = Luau::FValue<bool>::list; flag; flag = flag->next) {
        if (strcmp(flag->name, name) == 0) {
            flag->value = true;
            return;
        }
    }
}

static int conf_makelud(lua_State* L) {
    if (lua_isnumber(L, 1)) {
        double v = lua_tonumber(L, 1);
        lua_pushlightuserdata(L, reinterpret_cast<void*>(static_cast<uintptr_t>(v)));
    } else {
        lua_pushlightuserdata(L, const_cast<void*>(lua_topointer(L, 1)));
    }
    return 1;
}

void lx_conformance_setup(lx_State state, int silencePrint) {
    lua_State* L = static_cast<lua_State*>(state);

    // The upstream harness runs iter.luau with LuauYieldIter2 enabled
    // (yield inside a for-in iterator). Process-global, test-only.
    conf_enable_fflag("LuauYieldIter2");

    lua_pushcfunction(L, conf_collectgarbage, "collectgarbage");
    lua_setglobal(L, "collectgarbage");
    lua_pushcfunction(L, conf_loadstring, "loadstring");
    lua_setglobal(L, "loadstring");
    lua_pushcfunction(L, conf_false, "is_native");
    lua_setglobal(L, "is_native");
    lua_pushcfunction(L, conf_true, "is_native_if_supported");
    lua_setglobal(L, "is_native_if_supported");
    lua_pushcfunction(L, conf_makelud, "makelud");
    lua_setglobal(L, "makelud");
    if (silencePrint) {
        lua_pushcfunction(L, conf_silence, "print");
        lua_setglobal(L, "print");
    }

    conf_install_vector_metatable(L);

    // Freeze the shared globals, then give the running thread its own
    // writable global table proxying the frozen one.
    luaL_sandbox(L);
    luaL_sandboxthread(L);

    // Scripts treat _G as a synonym for their global environment.
    lua_pushvalue(L, LUA_GLOBALSINDEX);
    lua_setfield(L, -1, "_G");
}
} /* extern "C" */
