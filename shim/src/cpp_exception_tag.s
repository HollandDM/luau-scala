# Defines the C++ exception tag used by the WebAssembly exception-handling
# proposal (new EH). clang emits references to `__cpp_exception` for every
# throw/catch, and libc++abi/libunwind import it, but wasm-ld (LLVM 22) does
# not synthesize the tag itself (see wasi-sdk issue #565). Provide the single
# canonical definition here so native C++ exceptions link and unwind correctly.
	.tagtype	__cpp_exception i32
	.globl	__cpp_exception
__cpp_exception:
