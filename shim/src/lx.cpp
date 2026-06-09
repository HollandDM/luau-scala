extern "C" {
#include "lx.h"
}
#include "lua.h"
#include "lualib.h"
#include "luacode.h"
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <cstdio>

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
    LxStateData* d = new (std::nothrow) LxStateData{upcall, 0};
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

int lx_thread_status(lx_State state, lx_Thread thread) {
    lua_State* L  = static_cast<lua_State*>(state);
    lua_State* co = static_cast<lua_State*>(thread);
    (void)L;
    int s = lua_costatus(L, co);
    switch (s) {
        case LUA_CORUN:  return 0;
        case LUA_COSUS:  return 1;
        case LUA_CONOR:  return 3;
        case LUA_CODEAD: return 2;
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

int lx_resume(lx_State state, lx_Thread thread, int nArgs, int* nResults) {
    lua_State* L  = static_cast<lua_State*>(state);
    lua_State* co = static_cast<lua_State*>(thread);
    (void)L;

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
            *nResults = 0;
            return LX_RESUME_ERR;
    }
}

// -----------------------------------------------------------------------
// Push/pop operations
// -----------------------------------------------------------------------

void lx_push_nil    (lx_State s, lx_Thread t) { lua_pushnil(T(t)); }
void lx_push_boolean(lx_State s, lx_Thread t, int b) { lua_pushboolean(T(t), b); }
void lx_push_number (lx_State s, lx_Thread t, double n) { lua_pushnumber(T(t), n); }
void lx_push_integer(lx_State s, lx_Thread t, int64_t i) { lua_pushinteger(T(t), (lua_Integer)i); }
void lx_push_lstring(lx_State s, lx_Thread t, const char* b, size_t l) { lua_pushlstring(T(t), b, l); }
void lx_push_ref    (lx_State s, lx_Thread t, int ref) { lua_getref(T(t), ref); }
void lx_push_copy   (lx_State s, lx_Thread t, int idx) { lua_pushvalue(T(t), idx); }
void lx_pop         (lx_State s, lx_Thread t, int n)   { lua_pop(T(t), n); }
int  lx_stack_top   (lx_State s, lx_Thread t)          { return lua_gettop(T(t)); }

// -----------------------------------------------------------------------
// Non-raising read accessors
// -----------------------------------------------------------------------

int lx_type(lx_State s, lx_Thread t, int idx) {
    return lua_type(T(t), idx);
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

// -----------------------------------------------------------------------
// Table operations
// -----------------------------------------------------------------------

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
    int base = lua_gettop(L) - count + 1;
    for (int i = 0; i < count; i++) {
        lua_pushvalue(L, base + i);
        lua_rawseti(L, tidx, startIdx + i);
    }
}

// -----------------------------------------------------------------------
// Registry Refs
// -----------------------------------------------------------------------

int lx_ref(lx_State s, lx_Thread t, int idx) {
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

void lx_set_suspend_token(lx_State s, lx_Thread t, int64_t token) {
    LxStateData* d = get_state_data(static_cast<lua_State*>(t));
    d->suspendToken = token;
}

int64_t lx_get_suspend_token(lx_State s, lx_Thread t) {
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

void lx_open_libs(lx_State state) {
    lua_State* L = static_cast<lua_State*>(state);
    luaL_openlibs(L);

    static const char* const blocked[] = {
        "io", "require", "dofile", "loadfile", "load", "loadstring",
        nullptr
    };
    for (int i = 0; blocked[i]; i++) {
        lua_pushnil(L);
        lua_setglobal(L, blocked[i]);
    }

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
