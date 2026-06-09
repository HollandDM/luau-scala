// Stub implementations for WASM exception handling symbols.
// These are needed by the wasm setjmp/longjmp lowering (wasm-enable-sjlj).

#include <stdlib.h>

// The wasm exception handler context - referenced as data symbol by SJLJ lowering
char __wasm_lpad_context;

static void* __wasm_exception_ptr = 0;

void* __cxa_allocate_exception(size_t thrown_size) {
    return malloc(thrown_size);
}

void __cxa_free_exception(void* thrown_exception) {
    free(thrown_exception);
}

void __cxa_throw(void* thrown_exception, void* type_info, void* destructor) {
    __wasm_exception_ptr = thrown_exception;
    abort();
}

void* __cxa_begin_catch(void* exception_object) {
    return exception_object;
}

void __cxa_end_catch(void) {
}

// SJLJ lowering uses: (i32) -> i32
int _Unwind_CallPersonality(int version) {
    return 1; // _URC_HANDLER_FOUND
}
