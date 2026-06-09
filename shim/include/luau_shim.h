/*
 * luau_shim.h — PUBLIC ABI for the Luau Shim.
 *
 * THIS FILE IS A STUB. The real declarations are written in P02
 * (docs/plans/02-cpp-shim-abi.md). The functions below are placeholders
 * so that the native + WASM build pipelines can be exercised end-to-end
 * in P01 without P02 being complete.
 *
 * Rules (enforced here and in the real header):
 *   - All functions are `extern "C"` to avoid C++ name mangling.
 *   - No Luau headers are exposed; all Luau types are opaque.
 *   - lx_resume is the ONLY execution entry point (ADR-0001).
 *   - No lua_pcall, no lua_call, no lua_error from the host side.
 */

#ifndef LUAU_SHIM_H
#define LUAU_SHIM_H

#ifdef __cplusplus
extern "C" {
#endif

/* Opaque handle to a Luau state. Allocated by lx_newstate, freed by lx_close. */
typedef struct lx_State lx_State;

/* Placeholder: version query. Returns 1 (stub). */
int lx_version(void);

/*
 * STUB lifecycle functions. Real implementations in P02.
 * lx_newstate / lx_close are the outer lifetime bracket of an Isolate.
 */
lx_State* lx_newstate(void);
void      lx_close(lx_State* L);

/*
 * STUB resume-boundary entry. Real signature in P02 will carry
 * argument count, result count, and a tri-state status return.
 * For now: takes a state pointer, does nothing, returns 0 (ok).
 */
int lx_resume(lx_State* L, int nargs);

#ifdef __cplusplus
}
#endif
#endif /* LUAU_SHIM_H */
