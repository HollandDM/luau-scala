#!/bin/bash
# Copy luau-shim.js and luau-shim.wasm to all relevant test output directories

RES_DIR="$(dirname "$0")/../wasm/test/resources"

# Find all fastLinkJSTest.dest directories 
find out -type d -name 'fastLinkJSTest.dest' 2>/dev/null | while read dir; do
  if [ -f "$dir/main.js" ]; then
    cp "$RES_DIR/luau-shim.js" "$dir/" 2>/dev/null || true
    cp "$RES_DIR/luau-shim.wasm" "$dir/" 2>/dev/null || true
    echo "Copied to $dir"
  fi
done

# Also find any content-addressed dest files in the test output
find out/wasm/test -type f -name '*.json' 2>/dev/null | while read f; do
  # Check if this JSON contains a dest path
  DEST=$(python3 -c "
import json
try:
    with open('$f') as fh: data = json.load(fh)
    v = data.get('value', data)
    if isinstance(v, dict) and 'dest' in v:
        d = v['dest']
        if isinstance(d, str) and ':' in d:
            print(d.split(':')[-1])
except: pass
" 2>/dev/null)
  if [ -n "$DEST" ] && [ -d "$DEST" ] && [ -f "$DEST/main.js" ]; then
    cp "$RES_DIR/luau-shim.js" "$DEST/" 2>/dev/null || true
    cp "$RES_DIR/luau-shim.wasm" "$DEST/" 2>/dev/null || true
    echo "Copied to (cached) $DEST"
  fi
done

echo "Done"
