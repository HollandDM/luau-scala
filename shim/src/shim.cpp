/*
 * shim.cpp — STUB implementation.
 *
 * This file compiles without any Luau headers. Its only purpose is to
 * verify that the Mill native build task (Task 5a) and Emscripten task
 * (Task 5b) both produce valid shared-library / WASM artifacts.
 *
 * Replace this file with the real Shim in P02.
 */

#include "luau_shim.h"
#include <cstdlib>

/* Opaque state: just a heap-allocated int for stub purposes. */
struct lx_State {
    int sentinel;
};

extern "C" {

int lx_version(void) {
    return 1;
}

lx_State* lx_newstate(void) {
    lx_State* s = static_cast<lx_State*>(std::malloc(sizeof(lx_State)));
    if (s) s->sentinel = 0xCA11AB1E;
    return s;
}

void lx_close(lx_State* L) {
    std::free(L);
}

int lx_resume(lx_State* /* L */, int /* nargs */) {
    return 0; /* LX_OK stub */
}

} /* extern "C" */
