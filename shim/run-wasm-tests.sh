#!/bin/bash
set -eo pipefail

cd "$(dirname "$0")/.."

echo "=== 1. Build WASM binary ==="
./shim/build-wasm.sh

echo "=== 2. Build wasm test resources ==="
cp shim/luau-shim.wasm shim/luau-shim.js wasm/test/resources/

echo "=== 3. Create Scala.js test output ==="
# First link the JS
./mill-launcher.sh wasm.test.fastLinkJSTest

# Find where Mill put the linked output
# Mill 1.x stores linked JS in a content-addressed path
mkdir -p /tmp/wasm-test-run
# Rebuild the linked output by running fastLinkJS explicitly
# The output goes to out/wasm/test/fastLinkJSTest.dest/ via PathRef
# Actually, let's use find to locate it
LINKED_DIR=$(find out -name 'main.js' -path '*/wasm/test/*' 2>/dev/null | head -1 | xargs dirname 2>/dev/null)
if [ -z "$LINKED_DIR" ]; then
  echo "Linked output not found. Trying to compile test directly..."
  ./mill-launcher.sh wasm.test.compile
  
  # For testLocal, Mill copies output to a temp sandbox
  # Let's just build everything and skip the sandbox by running Node directly
  echo "Direct approach: build full JS and run node"
fi

echo "=== 4. Run tests ==="
./mill-launcher.sh wasm.test.testLocal 2>&1 | tail -30
