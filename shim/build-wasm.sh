#!/bin/bash
set -eo pipefail

# System LLVM 22 (clang) + a from-source wasi sysroot built with
# -DWASI_SDK_EXCEPTIONS=ON, so native wasm C++ exceptions (new EH) work.
# RESOURCE_DIR merges system clang headers with the wasm compiler-rt builtins
# produced by that sysroot build.
CLANG="${WASI_CLANG:-/usr/bin/clang++}"
SYSROOT="${WASI_SYSROOT:-$HOME/wasi-eh/install/share/wasi-sysroot}"
RESOURCE_DIR="${WASI_RESOURCE_DIR:-$HOME/wasi-eh/resource-dir}"
DEST="${1:-$(pwd)/out-wasm}"
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"

mkdir -p "$DEST"

INCLUDES=(
  -I"$SRC_DIR/include"
  -I"$SRC_DIR/luau/VM/include"
  -I"$SRC_DIR/luau/Common/include"
  -I"$SRC_DIR/luau/Compiler/include"
  -I"$SRC_DIR/luau/Ast/include"
  -I"$SRC_DIR/luau/Bytecode/include"
)

LUAU_DIRS=(
  "$SRC_DIR/luau/VM/src"
  "$SRC_DIR/luau/Compiler/src"
  "$SRC_DIR/luau/Ast/src"
  "$SRC_DIR/luau/Bytecode/src"
  "$SRC_DIR/luau/Common/src"
)

CFLAGS=(
  --target=wasm32-wasi
  --sysroot="$SYSROOT"
  -resource-dir "$RESOURCE_DIR"
  -std=c++17
  -O2
  -fwasm-exceptions
  -mllvm -wasm-use-legacy-eh=false
  # Out-of-range float->int casts must wrap/saturate like native C, not trap.
  # bit32 and number coercions rely on it (i32.trunc_f64 traps on overflow).
  -mnontrapping-fptoint
  -fno-rtti
  -D_LUAU_HAS_VECTOR_SIZE=0
  -D_WASI_EMULATED_PROCESS_CLOCKS
  "${INCLUDES[@]}"
  -c
)

WASM_LDFLAGS=(
  --target=wasm32-wasi
  --sysroot="$SYSROOT"
  -resource-dir "$RESOURCE_DIR"
  -std=c++17
  -O2
  -fwasm-exceptions
  -mllvm -wasm-use-legacy-eh=false
  -mnontrapping-fptoint
  -fno-rtti
  -mexec-model=reactor
  -lc++abi
  -lunwind
  -lwasi-emulated-process-clocks
  -Wl,--export=lx_newstate
  -Wl,--export=lx_close
  -Wl,--export=lx_main_thread
  -Wl,--export=lx_new_thread
  -Wl,--export=lx_thread_status
  -Wl,--export=lx_compile_and_load
  -Wl,--export=lx_resume
  -Wl,--export=lx_push_nil
  -Wl,--export=lx_push_boolean
  -Wl,--export=lx_push_number
  -Wl,--export=lx_push_lstring
  -Wl,--export=lx_push_ref
  -Wl,--export=lx_push_copy
  -Wl,--export=lx_pop
  -Wl,--export=lx_stack_top
  -Wl,--export=lx_type
  -Wl,--export=lx_to_number
  -Wl,--export=lx_to_boolean
  -Wl,--export=lx_to_lstring
  -Wl,--export=lx_rawlen
  -Wl,--export=lx_newtable
  -Wl,--export=lx_rawget
  -Wl,--export=lx_rawset
  -Wl,--export=lx_rawgeti
  -Wl,--export=lx_rawseti
  -Wl,--export=lx_setarray
  -Wl,--export=lx_ref
  -Wl,--export=lx_unref
  -Wl,--export=lx_register_native
  -Wl,--export=lx_set_suspend_token
  -Wl,--export=lx_get_suspend_token
  -Wl,--export=lx_set_global
  -Wl,--export=lx_get_global
  -Wl,--export=lx_openlibs
  -Wl,--export=lx_sandbox
  -Wl,--export=lx_open_libs
  -Wl,--export=lx_gc_step
  -Wl,--export=lx_gc_collect
  -Wl,--export=lx_copy_error
  -Wl,--export=lx_conformance_setup
  -Wl,--export=lx_resume_error
  -Wl,--export=lx_push_integer
  -Wl,--export=lx_to_integer
  -Wl,--export=malloc
  -Wl,--export=free
  -Wl,--export-table
  -Wl,--growable-table
  -Wl,-z,stack-size=1048576
  # buffers.luau allocates a full 1GiB buffer (bitops stress); growth is
  # on-demand, so the cap only needs to be high enough. wasm32 max: 4GiB.
  -Wl,--max-memory=4294967296
)

echo "=== Compiling Luau sources ==="

# Every translation unit compiles with the same flags: -fwasm-exceptions and
# new-EH (-wasm-use-legacy-eh=false). Mixing EH encodings across objects breaks
# linking/unwinding, so do NOT special-case the Compiler module here.
OBJS=()
for dir in "${LUAU_DIRS[@]}"; do
  for src in "$dir"/*.cpp; do
    obj="$DEST/$(basename "$src").o"
    echo "  CC $src"
    $CLANG "${CFLAGS[@]}" -o "$obj" "$src"
    OBJS+=("$obj")
  done
done

echo "=== Compiling shim ==="
SHIM_OBJ="$DEST/lx.cpp.o"
$CLANG "${CFLAGS[@]}" -o "$SHIM_OBJ" "$SRC_DIR/src/lx.cpp"
OBJS+=("$SHIM_OBJ")

echo "=== Assembling C++ exception tag ==="
TAG_OBJ="$DEST/cpp_exception_tag.o"
$CLANG --target=wasm32-wasi -resource-dir "$RESOURCE_DIR" -c -o "$TAG_OBJ" "$SRC_DIR/src/cpp_exception_tag.s"
OBJS+=("$TAG_OBJ")

echo "=== Linking WASM binary ==="
$CLANG "${WASM_LDFLAGS[@]}" -o "$DEST/luau-shim.wasm" "${OBJS[@]}"

echo "=== Copying outputs ==="
cp "$DEST/luau-shim.wasm" "$SRC_DIR/luau-shim.wasm"

echo "=== Done ==="
ls -la "$SRC_DIR/luau-shim.wasm"
