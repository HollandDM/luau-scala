#!/bin/bash
set -eo pipefail

WASI_SDK=/tmp/wasi-sdk/opt/wasi-sdk
CLANG=$WASI_SDK/bin/clang++
SYSROOT=$WASI_SDK/share/wasi-sysroot
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
  -std=c++17
  -O2
  -fwasm-exceptions
  -fno-rtti
  -D_LUAU_HAS_VECTOR_SIZE=0
  -D_WASI_EMULATED_PROCESS_CLOCKS
  "${INCLUDES[@]}"
  -c
)

WASM_LDFLAGS=(
  --target=wasm32-wasi
  --sysroot="$SYSROOT"
  -std=c++17
  -O2
  -fwasm-exceptions
  -fno-rtti
  -mexec-model=reactor
  -lc++abi
  -lwasi-emulated-process-clocks
  -Wl,--export-dynamic
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
  -Wl,--export=lx_push_integer
  -Wl,--export=lx_to_integer
  -Wl,--export=malloc
  -Wl,--export=free
  -Wl,--export-table
  -Wl,--growable-table
  -Wl,-z,stack-size=1048576
  -Wl,--max-memory=33554432
)

echo "=== Compiling Luau sources ==="

# Compiler modules need wasm exceptions (they use throw for CompileError)
COMPILER_CFLAGS=()
skip=false
for f in "${CFLAGS[@]}"; do
  if [ "$f" = "-fno-exceptions" ]; then COMPILER_CFLAGS+=("-fwasm-exceptions"); continue; fi
  if [ "$f" = "-DLUA_USE_LONGJMP=1" ]; then continue; fi
  if [ "$f" = "-mllvm" ]; then skip=true; continue; fi
  if [ "$skip" = true ]; then skip=false; continue; fi
  COMPILER_CFLAGS+=("$f")
done
COMPILER_CFLAGS+=("-fwasm-exceptions")

OBJS=()
for dir in "${LUAU_DIRS[@]}"; do
  for src in "$dir"/*.cpp; do
    obj="$DEST/$(basename "$src").o"
    echo "  CC $src"
    # Compiler files use wasm exceptions
    if [[ "$src" == */Compiler/* ]]; then
      $CLANG "${COMPILER_CFLAGS[@]}" -o "$obj" "$src"
    else
      $CLANG "${CFLAGS[@]}" -o "$obj" "$src"
    fi
    OBJS+=("$obj")
  done
done

echo "=== Compiling shim ==="
SHIM_OBJ="$DEST/lx.cpp.o"
$CLANG "${CFLAGS[@]}" -o "$SHIM_OBJ" "$SRC_DIR/src/lx.cpp"
OBJS+=("$SHIM_OBJ")

echo "=== Compiling wasm EH stubs ==="
EH_STUB_OBJ="$DEST/wasm_eh_stubs.c.o"
$WASI_SDK/bin/clang --target=wasm32-wasi --sysroot="$SYSROOT" -O2 -mllvm -wasm-enable-sjlj -c -o "$EH_STUB_OBJ" "$SRC_DIR/src/wasm_eh_stubs.c"
OBJS+=("$EH_STUB_OBJ")

echo "=== Linking WASM binary ==="
$CLANG "${WASM_LDFLAGS[@]}" -o "$DEST/luau-shim.wasm" "${OBJS[@]}"

echo "=== Copying outputs ==="
cp "$DEST/luau-shim.wasm" "$SRC_DIR/luau-shim.wasm"

echo "=== Done ==="
ls -la "$SRC_DIR/luau-shim.wasm"
