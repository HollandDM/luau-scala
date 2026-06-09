#!/bin/bash
set -eo pipefail

cd "$(dirname "$0")/.."

echo "=== Build WASM ==="
./shim/build-wasm.sh
cp shim/luau-shim.wasm wasm/test/resources/

echo "=== Link Scala.js ==="
./mill-launcher.sh wasm.test.fastLinkJSTest

echo "=== Link core.js (dependency) ==="
./mill-launcher.sh core.js.test.fastLinkJSTest

echo "=== Prepare test output ==="
# Mill's test sandbox copies from out/wasm/test/fastLinkJSTest.dest/
# but only main.js and main.js.map. We need luau-shim.wasm alongside.
# Workaround: symlink or copy into the linked output after link step.
# Also need it in the .super dir that testForked actually reads from.
for d in $(find out/wasm/test -type d -name '*.dest' 2>/dev/null); do
  cp wasm/test/resources/luau-shim.wasm "$d/" 2>/dev/null || true
done

echo "=== Run tests ==="
# testForked copies from the fastLinkJSTest output dir. If we put wasm there,
# it should be included. But Mill's copy is selective (only JS files).
# fallback: copy to sandbox right before running
mkdir -p out/mill-daemon/sandbox
cp wasm/test/resources/luau-shim.wasm out/mill-daemon/sandbox/

./mill-launcher.sh wasm.test.testLocal 2>&1 | tail -20
