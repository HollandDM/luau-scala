#!/bin/bash
# Builds a WASI sysroot WITH C++ exceptions (libc++/libc++abi/libunwind) using
# the system LLVM/clang, so the WASM shim can use native wasm exception handling
# (new EH) instead of the abort-stub hack.
#
# Why this exists: released wasi-sdk artifacts ship a -fno-exceptions sysroot
# (no __cxa_throw / no libunwind), so Luau's exception-based error model aborts.
# We rebuild only the sysroot (not LLVM — we reuse the system clang) against the
# wasi-sdk-31 sources (LLVM 22), with -DWASI_SDK_EXCEPTIONS=ON.
#
# Requires: system clang/clang++ (LLVM >= 21 for new EH), cmake, ninja, git.
# Output: $PREFIX/share/wasi-sysroot  and  $PREFIX/clang-resource-dir
# build-wasm.sh consumes these via WASI_SYSROOT / WASI_RESOURCE_DIR.
set -euo pipefail

PREFIX="${1:-$HOME/wasi-eh/install}"
WORK="${WASI_EH_WORK:-$HOME/wasi-eh/wasi-sdk}"
TAG="${WASI_SDK_TAG:-wasi-sdk-31}"   # LLVM 22.1.0; match your system clang major
CC_BIN="${CC:-/usr/bin/clang}"
CXX_BIN="${CXX:-/usr/bin/clang++}"

echo "=== clone $TAG (sysroot sources only) ==="
if [ ! -d "$WORK/.git" ]; then
  git clone --depth 1 -b "$TAG" https://github.com/WebAssembly/wasi-sdk.git "$WORK"
fi
cd "$WORK"
git submodule update --init --depth 1 src/wasi-libc src/config src/llvm-project

echo "=== configure (system clang, exceptions, wasm32-wasi only) ==="
cmake -G Ninja -B build/sysroot -S . \
  -DCMAKE_TOOLCHAIN_FILE="$WORK/wasi-sdk-p1.cmake" \
  -DWASI_SDK_PREFIX=/usr \
  -DWASI_SDK_TARGETS=wasm32-wasi \
  -DCMAKE_C_COMPILER_WORKS=ON -DCMAKE_CXX_COMPILER_WORKS=ON \
  -DWASI_SDK_EXCEPTIONS=ON -DWASI_SDK_INCLUDE_TESTS=OFF \
  -DCMAKE_INSTALL_PREFIX="$PREFIX"

echo "=== build + install sysroot ==="
ninja -C build/sysroot install

echo "=== merge clang resource dir (system headers + wasm builtins) ==="
RD="${WASI_RESOURCE_DIR:-$HOME/wasi-eh/resource-dir}"
SYS_RD="$($CC_BIN -print-resource-dir)"
rm -rf "$RD"; mkdir -p "$RD"
ln -s "$SYS_RD/include" "$RD/include"
ln -s "$PREFIX/clang-resource-dir/lib" "$RD/lib"

echo "=== done ==="
echo "sysroot:      $PREFIX/share/wasi-sysroot"
echo "resource-dir: $RD"
