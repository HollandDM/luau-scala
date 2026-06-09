const fs = require('fs');
const path = require('path');

function LuauShim(options) {
  const wasmPath = path.join(__dirname, 'luau-shim.wasm');
  const wasmBuffer = fs.readFileSync(wasmPath);
  const wasmModule = new WebAssembly.Module(wasmBuffer);

  var memory = new WebAssembly.Memory({ initial: 256, maximum: 32768, shared: false });
  var table = new WebAssembly.Table({ initial: 256, maximum: 65536, element: 'anyfunc' });
  var instance = null;
  var HEAPU8 = new Uint8Array(memory.buffer);
  var HEAP32 = new Int32Array(memory.buffer);

  var nextTableIdx = 0;
  var freeIndices = [];
  var jsFunctions = {};

  // Create a tiny wasm compile cache for wrapper functions
  var wrapperModuleCache = {};

  function createWasmFunction(fn, sig) {
    // Create a wasm function that calls a JS function.
    // The wrapper takes (i32, i32, i32, i32, i32) -> i32
    // We encode the JS function reference as a table ID and use
    // a JS-side call through globalThis.
    var idx = nextTableIdx++;
    var fnId = 'fn_' + idx;
    jsFunctions[fnId] = fn;

    // Compile a tiny wasm module that calls this function via import
    var wrapperBinary = new Uint8Array([
      0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, // magic + version
      0x01, 0x0a, 0x01, 0x60, 0x05, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x01, 0x7f, // type
      0x02, 0x09, 0x01, 0x03, 0x65, 0x6e, 0x76, 0x01, 0x66, 0x00, 0x00, // import
      0x03, 0x02, 0x01, 0x00, // function section
      0x07, 0x05, 0x01, 0x01, 0x77, 0x00, 0x01, // export w = func 1
      0x0a, 0x10, 0x01, 0x0e, 0x00, 0x20, 0x00, 0x20, 0x01, 0x20, 0x02, 0x20, 0x03, 0x20, 0x04, 0x10, 0x00, 0x0b, // body
    ]);
    var wrapperMod = new WebAssembly.Module(wrapperBinary);
    // The import provides a bridge to our JS function
    var wrapperInstance = new WebAssembly.Instance(wrapperMod, {
      e: { f: function(a0, a1, a2, a3, a4) { return fn(a0, a1, a2, a3, a4); } }
    });
    var wasmFn = wrapperInstance.exports.w;
    table.set(idx, wasmFn);
    return idx;
  }

  const importObj = {
    wasi_snapshot_preview1: {
      fd_write: function(fd, iovs, iovsLen, nwritten) { return 0; },
      fd_close: function(fd) { return 0; },
      fd_seek: function(fd, offset, whence, newoffset) { return 0; },
      fd_read: function(fd, iovs, iovsLen, nread) { return 0; },
      fd_fdstat_get: function(fd, buf) { return 0; },
      environ_sizes_get: function(count, bufSize) { return 0; },
      environ_get: function(environ, environBuf) { return 0; },
      proc_exit: function(code) { return 0; },
      clock_time_get: function(id, precision, time) { return 0; },
      args_sizes_get: function(count, bufSize) { return 0; },
      args_get: function(argv, argvBuf) { return 0; },
    },
    env: {
      memory: memory,
      __indirect_function_table: table,
    }
  };

  instance = new WebAssembly.Instance(wasmModule, importObj);
  var exports = instance.exports;

  var refreshViews = function() {
    HEAPU8 = new Uint8Array(memory.buffer);
    HEAP32 = new Int32Array(memory.buffer);
  };

  var api = {
    HEAPU8: HEAPU8,
    HEAP32: HEAP32,
    _malloc: function(size) { refreshViews(); return exports.malloc ? exports.malloc(size) : 0; },
    _free: function(ptr) { refreshViews(); if (exports.free) exports.free(ptr); },
    addFunction: createWasmFunction,
    removeFunction: function(idx) {
      delete jsFunctions['fn_' + idx];
      try { table.set(idx, null); } catch(e) {}
      freeIndices.push(idx);
    },
    dynCall_iiiiii: function(fnPtr, a0, a1, a2, a3, a4) {
      var fn = table.get(fnPtr);
      if (fn) return fn(a0, a1, a2, a3, a4);
      return 0;
    },
  };

  var funcNames = [
    'lx_newstate', 'lx_close', 'lx_main_thread', 'lx_new_thread',
    'lx_thread_status', 'lx_compile_and_load', 'lx_resume',
    'lx_push_nil', 'lx_push_boolean', 'lx_push_number', 'lx_push_integer',
    'lx_push_lstring', 'lx_push_ref', 'lx_push_copy', 'lx_pop', 'lx_stack_top',
    'lx_type', 'lx_to_number', 'lx_to_integer', 'lx_to_boolean', 'lx_to_lstring',
    'lx_rawlen', 'lx_newtable', 'lx_rawget', 'lx_rawset', 'lx_rawgeti', 'lx_rawseti',
    'lx_setarray', 'lx_ref', 'lx_unref', 'lx_register_native',
    'lx_set_suspend_token', 'lx_get_suspend_token',
    'lx_set_global', 'lx_get_global',
    'lx_openlibs', 'lx_sandbox', 'lx_open_libs',
    'lx_gc_step', 'lx_gc_collect', 'lx_copy_error',
  ];

  var prefixMap = {
    'lx_push_integer': 'lx_push_number',
    'lx_to_integer': 'lx_to_number',
  };

  for (var i = 0; i < funcNames.length; i++) {
    var name = funcNames[i];
    var wasmName = name;
    if (exports[wasmName] !== undefined) {
      (function(n) {
        api['_' + n] = function() { refreshViews(); return exports[wasmName].apply(exports, arguments); };
      })(name);
    } else if (prefixMap[name] && exports[prefixMap[name]] !== undefined) {
      (function(n, mapped) {
        api['_' + n] = function() { refreshViews(); return exports[mapped].apply(exports, arguments); };
      })(name, prefixMap[name]);
    } else {
      (function(n) {
        api['_' + n] = function() { throw new Error(n + ' not exported'); };
      })(name);
    }
  }

  return api;
}

module.exports = LuauShim;
