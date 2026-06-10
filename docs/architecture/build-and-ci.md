# Build and CI Reference

**Date:** 2026-06-10

This document is the authoritative reference for how luau-scala is compiled, linked, tested, and verified in CI. It covers every Mill module and task in `build.mill`, the two parallel WASM build pipelines (`shim.wasmBuild` via Emscripten and `shim.wasmBuildNative` via native clang/WASI), the EH sysroot setup script, the CI workflow jobs, known fragilities in the current setup, and the exact commands a new contributor needs to build and test each platform.

---

## 1. Repository structure overview

The repo has five active Mill modules declared in `build.mill` and three on-disk module trees that are **not** registered in `build.mill`:

| Directory | Mill module | Status |
|---|---|---|
| `core/jvm/` | `core.jvm` | Active |
| `core/js/` | `core.js` | Active |
| `panama/` | `panama` | Active |
| `wasm/` | `wasm` | Active |
| `shim/` | `shim` | Active (task-only, no Scala sources) |
| `scheduler/jvm/` | — | **Not registered** |
| `stdlib/jvm/` | — | **Not registered** |
| `zio/` | — | **Not registered** |
| `ce/` | — | **Not registered** |

The `shim/` module contains no Scala sources; it only contributes build tasks that compile the C++ Shim layer and produce native or WASM binary artifacts.

---

## 2. Mill version and launcher

The pinned Mill version lives in `.mill-version`:

```
1.1.6
```

`mill-launcher.sh` is a portable shell launcher that reads `.mill-version`, determines the correct artifact suffix (native binary for Linux amd64/aarch64 or macOS arm64/amd64, falling back to JVM binary if glibc < 2.39), and downloads the Mill binary from Maven Central to `~/.cache/mill/download/` on first use. The launcher's `DEFAULT_MILL_VERSION` is `1.1.5-21-53d1ba` — a non-release development snapshot — but this is overridden by `.mill-version` whenever that file exists (`mill-launcher.sh:5,38-46`).

For local development, always invoke via `./mill-launcher.sh` (which respects `.mill-version`) rather than a system-installed `mill` binary.

---

## 3. Mill meta-build (`mill-build/build.mill`)

`mill-build/build.mill` is the meta-build file Mill uses to compile `build.mill` itself. It is trivial:

```scala
import mill.*, scalalib.*
import mill.meta.MillBuildRootModule

object `package` extends MillBuildRootModule
```

No custom meta-build logic exists. Any changes to Mill plugin imports in `build.mill` are automatically picked up here.

---

## 4. Root `build.mill` — module and task reference

### 4.1 Global settings

```scala
val scalaVersion       = "3.8.3"
val scalaJSVersion     = "1.21.0"
val zioVersion         = "2.1.25"      // unused by active modules
val catsEffectVersion  = "3.5.0"       // unused by active modules
val munitVersion       = "1.2.0"
```

(`build.mill:4-8`)

### 4.2 Base traits

**`LuauCrossPlatformModule`** (`build.mill:10-20`) extends `ScalaModule` with `ScalafmtModule`. Applies shared `scalacOptions`: `-encoding UTF-8`, `-deprecation`, `-feature`, `-unchecked`, `-Xkind-projector:underscores`, `-source:future`. All Scala modules (JVM and JS) extend this trait.

**`LuauCrossPlatformJSModule`** (`build.mill:22-25`) extends `LuauCrossPlatformModule` with `ScalaJSModule`. Sets `scalaJSVersion` and forces `ModuleKind.CommonJSModule` for all Scala.js modules.

### 4.3 `core` module

```
object core extends Module {
  object jvm extends LuauCrossPlatformModule    // core.jvm
  object js  extends LuauCrossPlatformJSModule  // core.js
}
```

(`build.mill:27-40`)

`core.jvm` and `core.js` declare no external Maven dependencies and no module dependencies beyond themselves. Both have `test` sub-modules that pull in MUnit (`org.scalameta::munit::1.2.0`).

`core.jvm` contains the shared abstractions used by both backends:
- `Binding[H]` — the full backend surface (lifecycle, compile/load, resume, push/read, table ops, Ref/Scope, codec, globals)
- `Ref[H]`, `Scope[H]` — ownership types
- `NativeFn[H]`, `NativeFnResult` — the tri-state upcall ADT
- `Resume`, `Cancel` — async primitive opaque types
- `ResumeResult`, `LuaError`, `LuaValue`, `LuaType` — result and value types
- `LuauEncoder[A]`, `LuauDecoder[A]`, `Sink[H]` — codec typeclass hierarchy
- `FakeBinding` / `FakeState` — in-memory fake for unit testing

`core.js` shares its sources with `core.jvm` via a filesystem symlink: `core/js/src -> ../jvm/src`. Mill sees all 14 core source files from both modules. The JS-side core abstraction is therefore built from the same Scala sources, compiled by Scala.js rather than the JVM backend.

### 4.4 `panama` module

```scala
object panama extends LuauCrossPlatformModule {
  override def moduleDeps = super.moduleDeps ++ Seq(core.jvm)
  object test extends ScalaTests with TestModule.Munit {
    def forkArgs = Seq("--enable-native-access=ALL-UNNAMED", "--enable-preview")
  }
}
```

(`build.mill:42-51`)

The `panama` module implements `Binding[MemorySegment]` using Java 21 Panama FFM (Foreign Function & Memory API). It depends on `core.jvm`. Tests require JVM flags to enable native access and preview features.

Key sources:
- `panama/src/luau/panama/LxHandles.scala` — eagerly resolves all `lx_*` Panama method handles at class load time. Loads `libluau-shim` via the system property `luau.shim.lib` (if set) or `System.loadLibrary("luau-shim")`.
- `panama/src/luau/panama/LxConstants.scala` — Scala mirror of `lx.h` numeric constants (correct Luau type codes: `LX_TSTRING=6`, `LX_TTABLE=7`, etc.).
- `panama/src/luau/panama/PanamaState.scala` — `Binding[MemorySegment]` implementation.
- `panama/src/luau/panama/NativeFnDispatcher.scala` — manages Native function registration and dispatch; allocates one shared Panama upcall stub for all Native functions.
- `panama/src/luau/panama/SuspendRegistry.scala` — thread-safe store for pending `Suspend` registrations, bridging the `lx_get_suspend_token` value to a `NativeFnResult.Suspend` callback.
- `panama/src/generated/LuauShimBindings.scala` — **stale generated file**, not imported by any active code. Contains outdated signatures (e.g., `lx_newstate()` takes no args). Ignore it.

### 4.5 `wasm` module

```scala
object wasm extends LuauCrossPlatformJSModule {
  override def moduleDeps = super.moduleDeps ++ Seq(core.js)
  override def jsEnvConfig = Task {
    val wasmDir  = shim.wasmBuildNative()
    val wasmPath = wasmDir.path / "luau-shim.wasm"
    JsEnvConfig.NodeJs().copy(env = Map("LUAU_WASM_PATH" -> wasmPath.toString))
  }
  object test extends ScalaJSTests with TestModule.Munit { ... }
}
```

(`build.mill:53-63`)

The `wasm` module implements `Binding[Int]` (WASM linear-memory pointer as handle type) using Scala.js. It depends on `core.js`. The `jsEnvConfig` task calls `shim.wasmBuildNative()` to produce the WASM binary, then sets `LUAU_WASM_PATH` in the Node.js environment so `LuauShimFactory` can locate it at test runtime.

Key sources:
- `wasm/src/luau/wasm/WasmModule.scala` — `WasmModuleExports` `@js.native` trait declaring all `_lx_*` exports, `HEAPU8`/`HEAP32` getters, `_malloc`/`_free`, `addFunction`/`removeFunction`, `dynCall_iiiiii`.
- `wasm/src/luau/wasm/LuauShimFactory.scala` — loads the WASM binary directly via `WebAssembly.Module` + `WebAssembly.Instance` (not Emscripten). Provides WASI stub imports (all no-ops). Defines `HEAPU8`/`HEAP32` as fresh-view JS property getters to handle memory-growth buffer detachment. `addFunction()` grows the indirect function table with `tbl.grow(1)` and wraps the JS function via a minimal handcrafted wrapper WASM module (`cachedWrapMod`).
- `wasm/src/luau/wasm/WasmBackend.scala` — entry point: `load()` calls `LuauShimFactory`, sets `WasmModule`, calls `Trampoline.reset()` then `Trampoline.install()`.
- `wasm/src/luau/wasm/Trampoline.scala` — global singleton managing the JS-side upcall function pointer and Native function registry. `reset()` clears all state between WASM instance loads (critical for test isolation).
- `wasm/src/luau/wasm/WasmBinding.scala`, `WasmMarshal.scala`, `WasmSink.scala` — `Binding[Int]` implementation and memory marshaling utilities.

### 4.6 `shim` module (tasks only)

The `shim` object (`build.mill:65-181`) is a plain `Module` with no Scala sources. It defines path helpers, source-tracking inputs, and three build tasks.

#### Path helpers and source tracking

| Task/val | Type | Description |
|---|---|---|
| `shimSrcDir` | `Task[os.Path]` | `os.pwd / "shim"` |
| `shimIncDir` | `Task[os.Path]` | `os.pwd / "shim" / "include"` |
| `shimSource` | `Task.Source` | Tracks `shim/` directory |
| `luauIncVm`, `luauIncCommon`, `luauIncCompiler`, `luauIncAst`, `luauIncBytecode` | `Task[os.Path]` | Luau per-component include directories |
| `luauVmDir` … `luauCommonDir` | `Task[os.Path]` | Luau per-component source directories |
| `luauSubmoduleHead` | `Task.Input[String]` | Reads `shim/luau/.git/HEAD`; forces rebuild when the submodule updates |
| `luauSources` | `Task[Seq[os.Path]]` | Collects all `*.cpp` files under Luau's VM/Compiler/Ast/Bytecode/Common `src/` directories |
| `allIncludeDirs` | `Task[Seq[String]]` | Flat list of all six include directories for `-I` flags |
| `nativePlatform` | `Task[(String, Seq[String])]` | Detects macOS (`.dylib` / `-dynamiclib`) vs Linux (`.so` / `-shared -fPIC`) |

(`build.mill:67-108`)

#### `shim.nativeBuild` — JVM-side native shared library

```scala
def nativeBuild: Task[PathRef] = Task {
  val (ext, lflags) = nativePlatform()
  val outLib = dest / s"libluau-shim.$ext"
  os.proc(Seq("clang++") ++
    Seq("-std=c++17", "-O2") ++ includeArgs ++ lflags ++
    Seq("-o", outLib.toString, srcFile.toString) ++ luauSrcs
  ).call(cwd = dest, stdout = os.Inherit, stderr = os.Inherit)
  PathRef(outLib)
}
```

(`build.mill:110-124`)

Compiles `shim/src/lx.cpp` plus all Luau C++ source files into a single shared library (`libluau-shim.so` on Linux, `libluau-shim.dylib` on macOS) using system `clang++`. No EH sysroot is needed here; the host platform's native C++ runtime handles exceptions. The task invalidates when `luauSubmoduleHead()` changes (via `nativePlatform`'s implicit `_` binding).

**Compiler flags (build.mill:118-122):**
- `-std=c++17`
- `-O2`
- One `-I <dir>` per include directory
- Platform link flags (`-shared -fPIC` / `-dynamiclib`)

No `-fPIC` is added separately for macOS — `-dynamiclib` implies it.

#### `shim.wasmBuild` — Emscripten build (vestigial)

```scala
def wasmBuild: Task[PathRef] = Task {
  ...
  os.proc(Seq("emcc") ++ Seq("-std=c++17", "-O2") ++ includeArgs ++
    Seq("-s", "WASM=1",
        "-s", s"EXPORTED_FUNCTIONS=$exportedFunctions",
        "-s", s"EXPORTED_RUNTIME_METHODS=$exportedRuntimeMethods",
        "-s", "MODULARIZE=1", "-s", "EXPORT_NAME='LuauShim'",
        "-s", "ALLOW_MEMORY_GROWTH=1", "-s", "ALLOW_TABLE_GROWTH=1",
        "-s", "STACK_SIZE=1048576", "-s", "ENVIRONMENT='node,web,worker'",
        "--no-entry", "-o", outJs.toString, srcFile.toString
    ) ++ luauSrcs
  ).call(...)
  PathRef(dest / "luau-shim.wasm")
}
```

(`build.mill:126-166`)

This task compiles the Shim using Emscripten (`emcc`) and produces a WASM file alongside a JavaScript wrapper. It uses `MODULARIZE=1` and `EXPORT_NAME='LuauShim'`, which generates an Emscripten module factory pattern.

**This task is vestigial and produces an incompatible artifact.** `LuauShimFactory` (the WASM backend's loader) instantiates raw `WebAssembly.Module` + `WebAssembly.Instance` directly, without Emscripten's module wrapper. The Emscripten-produced `.js` file is not read or imported by any active code path. The task remains in `build.mill` only for historical reference; CI calls it, but the artifact it produces cannot be consumed by `wasm.test`.

Additionally, `wasmBuild` lists `dynCall_iii` in `EXPORTED_RUNTIME_METHODS` (`build.mill:149`) while `WasmModuleExports` only declares `dynCall_iiiiii` — the two paths have diverged in expected runtime method signatures.

#### `shim.wasmBuildNative` — native clang/WASI build (the active path)

```scala
def wasmBuildNative = Task {
  val projectRoot = os.Path(sys.env("PWD"))
  val script = projectRoot / "shim" / "build-wasm.sh"
  os.proc("/usr/bin/bash", script.toString, Task.dest.toString).call(
    cwd = projectRoot,
    stdout = os.Inherit,
    stderr = os.Inherit
  )
  val wasmFile = Task.dest / "luau-shim.wasm"
  if !os.exists(wasmFile) then sys.error("WASM not produced at $wasmFile")
  PathRef(Task.dest)
}
```

(`build.mill:168-180`)

This task delegates to `shim/build-wasm.sh`, passing `Task.dest` as the output directory. It returns a `PathRef` to the dest directory (not the file itself), which `wasm.jsEnvConfig` reads to locate `luau-shim.wasm` at `wasmDir.path / "luau-shim.wasm"` (`build.mill:57`).

**Known fragility:** `sys.env("PWD")` (`build.mill:169`) resolves the project root from the shell environment variable `PWD`, not from Mill's computed `os.pwd`. These differ when Mill is invoked from a subdirectory, when `PWD` is a symlink that resolves differently from the real path, or in certain CI environments. The safer alternative would be `os.pwd.toString`.

---

## 5. WASM build pipeline in detail

### 5.1 EH sysroot setup (`shim/build-eh-sysroot.sh`)

The released wasi-sdk ships a `-fno-exceptions` sysroot: `__cxa_throw`, `libunwind`, and `libc++abi` are absent. Luau uses C++ exceptions internally for its error model (errors in the VM are `throw`-based), so native C++ exception support is required in the WASM binary. `build-eh-sysroot.sh` rebuilds the sysroot from source with exceptions enabled.

**What it does:**

1. Clones `wasi-sdk-31` (LLVM 22.1.0 sources) shallowly from `https://github.com/WebAssembly/wasi-sdk.git` using git tag `wasi-sdk-31` (`build-eh-sysroot.sh:18,24`).
2. Updates submodules `src/wasi-libc`, `src/config`, `src/llvm-project` with depth 1 (`build-eh-sysroot.sh:27`).
3. Runs CMake with `-DWASI_SDK_EXCEPTIONS=ON` and `-DWASI_SDK_TARGETS=wasm32-wasi` using the **system** clang (not re-building LLVM itself) (`build-eh-sysroot.sh:30-36`).
4. Installs sysroot to `$PREFIX` (default `~/wasi-eh/install`).
5. Merges the system clang resource directory headers (`$SYS_RD/include`) with the new wasm builtins (`$PREFIX/clang-resource-dir/lib`) into `$HOME/wasi-eh/resource-dir` via symlinks (`build-eh-sysroot.sh:42-46`).

**Prerequisites:** system clang/clang++ (LLVM ≥ 21 for new-EH proposal support), cmake, ninja, git.

**Outputs consumed by `build-wasm.sh`:**
- `$HOME/wasi-eh/install/share/wasi-sysroot` → `WASI_SYSROOT`
- `$HOME/wasi-eh/resource-dir` → `WASI_RESOURCE_DIR`

This is a **one-time setup** step that takes several minutes. It does not need to be repeated unless the LLVM major version of the system toolchain changes.

### 5.2 `shim/build-wasm.sh` — WASM compilation and linking

This script compiles `luau-shim.wasm` using system clang targeting `wasm32-wasi` with native C++ exception handling (the WebAssembly EH proposal, new encoding).

**Environment variables:**

| Variable | Default | Purpose |
|---|---|---|
| `WASI_CLANG` | `/usr/bin/clang++` | C++ compiler |
| `WASI_SYSROOT` | `$HOME/wasi-eh/install/share/wasi-sysroot` | EH-enabled WASI sysroot |
| `WASI_RESOURCE_DIR` | `$HOME/wasi-eh/resource-dir` | Merged clang resource dir |
| `DEST` (positional arg `$1`) | `$(pwd)/out-wasm` | Output directory for object files and `.wasm` |

(`build-wasm.sh:8-11`)

**Compilation flags (`CFLAGS`):**

```
--target=wasm32-wasi
--sysroot=$SYSROOT
-resource-dir $RESOURCE_DIR
-std=c++17
-O2
-fwasm-exceptions
-mllvm -wasm-use-legacy-eh=false
-fno-rtti
-D_LUAU_HAS_VECTOR_SIZE=0
-D_WASI_EMULATED_PROCESS_CLOCKS
-I <six include dirs>
-c
```

(`build-wasm.sh:33-46`)

The pair `-fwasm-exceptions -mllvm -wasm-use-legacy-eh=false` selects the new WebAssembly EH proposal encoding (not legacy SjLj). Every translation unit — all Luau directories (VM, Compiler, Ast, Bytecode, Common) and `lx.cpp` — is compiled with these identical flags. Mixing legacy-SjLj and new-EH objects in the same link is undefined behavior for unwinding (`build-wasm.sh:112-114`).

**Build steps:**

1. Compile all Luau `*.cpp` files from five source directories into `$DEST/*.cpp.o` object files.
2. Compile `shim/src/lx.cpp` into `$DEST/lx.cpp.o`.
3. Assemble `shim/src/cpp_exception_tag.s` into `$DEST/cpp_exception_tag.o` — this provides the canonical `__cpp_exception` WASM tag definition.
4. Link all objects into `$DEST/luau-shim.wasm`.
5. Copy `luau-shim.wasm` to `shim/luau-shim.wasm` (committed location for manual distribution).

**Link flags (`WASM_LDFLAGS`):**

```
--target=wasm32-wasi
--sysroot / -resource-dir / -std=c++17 / -O2 (same as CFLAGS)
-fwasm-exceptions -mllvm -wasm-use-legacy-eh=false -fno-rtti
-mexec-model=reactor
-lc++abi
-lunwind
-lwasi-emulated-process-clocks
-Wl,--export=<each lx_* symbol>   (34 symbols, build-wasm.sh:61-101)
-Wl,--export=lx_push_integer      (also exported, build-wasm.sh:100)
-Wl,--export=lx_to_integer        (also exported, build-wasm.sh:101)
-Wl,--export=malloc
-Wl,--export=free
-Wl,--export-table
-Wl,--growable-table
-Wl,-z,stack-size=1048576
-Wl,--max-memory=33554432         (32 MiB)
```

(`build-wasm.sh:48-108`)

**Key linker decisions:**

- **`-mexec-model=reactor`**: selects the WASI reactor model. The reactor exports `_initialize()` for C++ static constructors and does not tear down global state after each call (unlike the command model). `LuauShimFactory` calls `_initialize()` exactly once per instance (`LuauShimFactory.scala:67`). Calling it again would re-run static constructors and corrupt the embedded Runtime.

- **`-Wl,--growable-table` + `-Wl,--export-table`**: the indirect function table must be growable so `addFunction()` in `LuauShimFactory` can append the JS-side upcall function pointer via `tbl.grow(1)` (`LuauShimFactory.scala:88-93`). Without `--growable-table`, `tbl.grow()` throws a `RangeError`.

- **`-Wl,-z,stack-size=1048576`**: sets the WASM shadow stack to 1 MiB, required for Luau's recursive VM operations.

- **`-Wl,--max-memory=33554432`**: caps WASM linear memory at 32 MiB. Memory growth is allowed up to this ceiling.

### 5.3 The `__cpp_exception` tag (`shim/src/cpp_exception_tag.s`)

```asm
    .tagtype    __cpp_exception i32
    .globl      __cpp_exception
__cpp_exception:
```

(`cpp_exception_tag.s:6-8`)

LLVM 22 / wasm-ld does not synthesize the C++ exception tag that `-fwasm-exceptions`, `libc++abi`, and `libunwind` all reference. This assembly file provides the single canonical definition. Without it the link either fails (undefined symbol) or C++ exceptions silently abort. The `.tagtype` directive is a wasm-ld extension specific to LLVM; if the toolchain changes to a non-LLVM linker or a different LLVM major version, this file must be reviewed.

### 5.4 Duplication between `shim.wasmBuild` and `shim/build-wasm.sh`

The two WASM build paths share the same source files but differ in every meaningful detail:

| Dimension | `shim.wasmBuild` (Mill task) | `shim/build-wasm.sh` (native) |
|---|---|---|
| Compiler | `emcc` (Emscripten) | `clang++` (system LLVM) |
| Target | Emscripten-wasm | `wasm32-wasi` |
| Exception model | None / SjLj (Emscripten default) | New-EH (`-fwasm-exceptions`) |
| Output format | WASM + JS module wrapper (`MODULARIZE=1`) | Bare WASM (reactor) |
| Consumption | Nothing — incompatible with `LuauShimFactory` | `wasm.jsEnvConfig` → tests |
| `cpp_exception_tag.s` | Not included | Compiled and linked |
| `_initialize()` export | No | Yes (reactor model) |
| Symbol naming | Emscripten-prefixed (`_lx_*` via EXPORTED_FUNCTIONS) | Direct (`lx_*`) |

`shim.wasmBuild` cannot produce artifacts that the current WASM backend can load. It exists in `build.mill` and is called in CI (`.github/workflows/ci.yml:44`), but its output is discarded. `shim/build-wasm.sh` is the authoritative WASM build.

---

## 6. Luau submodule

`shim/luau` is the upstream Roblox Luau C++ runtime, declared as a shallow git submodule in `.gitmodules`:

```
[submodule "shim/luau"]
    path = shim/luau
    url = https://github.com/luau-lang/luau
    shallow = true
```

The `luauSubmoduleHead` task in `build.mill` reads `shim/luau/.git/HEAD` as a `Task.Input` (`build.mill:81-85`). Any change to the submodule HEAD invalidates all downstream build tasks that depend on Luau sources. This ensures Mill rebuilds the Shim when the submodule is updated.

In CI, `actions/checkout@v4` uses `submodules: recursive` and `fetch-depth: 0` to restore the submodule history fully (`ci.yml:14-15`).

---

## 7. Scala compiler version and formatting

All modules compile against Scala 3.8.3 (`build.mill:4`). Code formatting is governed by `.scalafmt.conf`, which pins scalafmt 3.8.3 with the `scala3` dialect.

To check formatting:
```
./mill-launcher.sh __.checkFormatting
```

To reformat in place:
```
./mill-launcher.sh __.reformat
```

---

## 8. CI workflow (`.github/workflows/ci.yml`)

CI runs a single job `build-and-test` on `ubuntu-22.04` triggered on every push to `main` and every pull request.

### 8.1 Tool setup

| Step | Tool | Version |
|---|---|---|
| Java | Temurin | 21 |
| Node.js | — | 20 |
| C++ compiler | clang++-17 via apt | 17 |
| Emscripten SDK | emsdk | 3.1.50 |
| Mill | Downloaded from GitHub releases | **0.12.3** |

The Mill version downloaded in CI (`0.12.3`) **differs from the version pinned in `.mill-version` (`1.1.6`)**. This is a major version mismatch: Mill 0.12.x uses different task scheduling, caching semantics, and plugin APIs than Mill 1.x. The cache key (`ci.yml:36`) hashes `.mill-version` and `build.mill`, but this does not help if the binary downloaded on line 38 ignores `.mill-version`. The CI is frozen at 0.12.3; whether this is intentional or a stale workflow is an open question.

### 8.2 CI steps

```yaml
- name: Compile all Scala modules
  run: ./mill __.compile

- name: Build native shim
  run: ./mill shim.nativeBuild

- name: Build WASM shim
  run: source /tmp/emsdk/emsdk_env.sh && ./mill shim.wasmBuild

- name: Copy WASM to test resources
  run: ./mill shim.copyWasmToResources       # ← DOES NOT EXIST

- name: Run Panama smoke tests
  run: ./mill panama.test

- name: Run Scala.js tests
  run: ./mill wasm.test

- name: Run all other tests
  run: ./mill __.test
```

(`ci.yml:39-52`)

**`shim.copyWasmToResources` is a CI-breaking bug.** This Mill task does not exist in `build.mill`. The shell script `shim/copy-wasm-test-resources.sh` performs the equivalent operation (scatter `luau-shim.wasm` into all `fastLinkJSTest.dest/` directories Mill creates), but it is not wrapped as a Mill task. CI will fail at this step every run. The fix is either to add a `copyWasmToResources` task to `build.mill` that calls the shell script, or to replace the Mill invocation in CI with a direct shell call.

### 8.3 CI tool-setup notes

Clang-17 is installed via apt and registered as `/usr/bin/clang++` via `update-alternatives`. Emscripten 3.1.50 is installed from its own GitHub repo and activated via `emsdk_env.sh` for the `shim.wasmBuild` step only. The native WASM build (`shim/build-wasm.sh`) is **not** called in CI at all — only the vestigial Emscripten build is. This means the Scala.js WASM tests (`wasm.test`) in CI cannot succeed as described, because `wasm.jsEnvConfig` calls `shim.wasmBuildNative()` which runs `build-wasm.sh`, which requires the EH sysroot that CI never sets up.

---

## 9. Known fragilities

### 9.1 `out/` not in `.gitignore`

`.gitignore` contains:
```
*.wasm
out-wasm/
wasm/test/resources/luau-shim.*
```

It does **not** include `out/`. Mill's build cache — dozens of `.json`, `.class`, `.tasty`, `.zinc`, and binary files — is tracked in git. Every `./mill` invocation modifies files under `out/`, resulting in a permanently dirty working tree. The git status shown at the top of this session lists over 50 modified `out/` paths. Adding `out/` to `.gitignore` would eliminate this noise.

### 9.2 `sys.env("PWD")` in `wasmBuildNative`

`build.mill:169` resolves the project root as:
```scala
val projectRoot = os.Path(sys.env("PWD"))
```

`sys.env("PWD")` is the shell's working directory at the moment Mill is invoked, not Mill's own computed `os.pwd`. In environments where `PWD` is a symlink path that differs from the canonical path, or when Mill is launched from a subdirectory, this will produce a wrong project root and the script call will fail with a "file not found" error. The correct expression is `os.pwd`.

### 9.3 Mill version mismatch between local and CI

Local development uses Mill 1.1.6 (`.mill-version`). CI downloads Mill 0.12.3 (`ci.yml:38`). Mill 0.12.x may fail to parse `build.mill` constructs introduced in 1.x (e.g., `mvn"..."` dependency syntax, `Task.Input`, or module scoping differences). The CI should be updated to download the version specified in `.mill-version`, e.g.:

```yaml
- name: Download Mill
  run: |
    MILL_VERSION=$(cat .mill-version)
    curl -L "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/${MILL_VERSION}/mill-dist-${MILL_VERSION}.exe" \
      -o ./mill && chmod +x ./mill
```

### 9.4 `shim.copyWasmToResources` does not exist

Described above in §8.2. CI fails at this step unconditionally.

### 9.5 `shim.wasmBuild` produces incompatible artifacts

Described above in §5.4. CI calls `shim.wasmBuild` but nothing consumes its output. The active test pipeline uses `shim.wasmBuildNative` exclusively.

### 9.6 `mill-launcher.sh` `DEFAULT_MILL_VERSION` is a dev snapshot

`mill-launcher.sh:5` sets `DEFAULT_MILL_VERSION="1.1.5-21-53d1ba"`. This is overridden by `.mill-version` in normal use, but if `.mill-version` is accidentally deleted or the launcher is used outside the repo, it downloads a non-release development snapshot that may be unavailable or unstable.

### 9.7 Unregistered modules

`scheduler/jvm/`, `stdlib/jvm/`, `zio/`, and `ce/` contain source files but have no entries in `build.mill`. They are invisible to all builds, tests, and CI. Changes to `core.jvm`'s public API (e.g., `Binding[H]`, `NativeFn[H]`) will silently break these modules without any compilation failure to signal the breakage.

---

## 10. Contributor quick-start commands

### Prerequisites

- Java 21 (Temurin recommended)
- Node.js 20
- clang++ (LLVM ≥ 21; LLVM 22 recommended for WASM)
- cmake, ninja (for EH sysroot one-time setup)

### One-time WASM EH sysroot setup

```bash
# Clone + build the EH-enabled WASI sysroot (takes ~10 minutes)
./shim/build-eh-sysroot.sh
# Outputs: ~/wasi-eh/install/share/wasi-sysroot  and  ~/wasi-eh/resource-dir
```

This only needs to be done once per machine (or when your system clang major version changes).

### Compile all Scala modules

```bash
./mill-launcher.sh __.compile
```

### Build the JVM native shim (required for `panama.test`)

```bash
./mill-launcher.sh shim.nativeBuild
# Produces: out/shim/nativeBuild.dest/libluau-shim.so (Linux)
#        or out/shim/nativeBuild.dest/libluau-shim.dylib (macOS)
```

### Build the WASM binary (required for `wasm.test`)

```bash
./shim/build-wasm.sh
# Produces: out-wasm/luau-shim.wasm  and  shim/luau-shim.wasm
```

Or via Mill (which also sets the output path for `wasm.jsEnvConfig`):

```bash
./mill-launcher.sh shim.wasmBuildNative
# Produces: out/shim/wasmBuildNative.dest/luau-shim.wasm
```

### Run Panama (JVM) tests

The `panama.test` task requires the native shim library on `java.library.path`. Pass the path via the system property:

```bash
LIB=$(./mill-launcher.sh show shim.nativeBuild | tr -d '"' | xargs dirname)
./mill-launcher.sh panama.test \
  --jvmArgs "-Dluau.shim.lib=$LIB/libluau-shim.so"
```

Or build and run in one command (Mill resolves the dependency automatically if `nativeBuild` is already cached):

```bash
./mill-launcher.sh panama.test
```

(Note: `PanamaState` loads the library via `System.loadLibrary("luau-shim")`, which requires `java.library.path` to include the directory where `libluau-shim.so` lives. Setting `-Dluau.shim.lib=<absolute path>` bypasses the library path search.)

### Run WASM (Scala.js) tests

```bash
# Full automated pipeline:
./shim/run-wasm-tests.sh

# Or manually:
./shim/build-wasm.sh                          # build WASM
./mill-launcher.sh wasm.test.fastLinkJSTest   # link Scala.js test bundle
./shim/copy-wasm-test-resources.sh            # scatter .wasm into test dirs
./mill-launcher.sh wasm.test.testLocal        # run tests under Node.js
```

### Run all tests

```bash
./mill-launcher.sh __.test
```

### Check and reformat code

```bash
./mill-launcher.sh __.checkFormatting   # check only
./mill-launcher.sh __.reformat          # reformat in place
```

---

## 11. Export symbol table

The following table lists every symbol exported from `luau-shim.wasm` (as specified in `shim/build-wasm.sh`). The `lx.h` column indicates whether the symbol is declared in the public C header.

| Symbol | In `lx.h` | Notes |
|---|---|---|
| `lx_newstate` | Yes | Creates Isolate, registers upcall |
| `lx_close` | Yes | Frees Isolate and all memory |
| `lx_main_thread` | Yes | Returns main thread handle |
| `lx_new_thread` | Yes | Creates coroutine; leaves thread on main stack |
| `lx_thread_status` | Yes | 0=ok/running, 1=suspended, 2=dead, 3=normal |
| `lx_compile_and_load` | Yes | Compile Luau source to bytecode, load onto main stack |
| `lx_resume` | Yes | **Only Luau execution entry point** |
| `lx_push_nil` | Yes | |
| `lx_push_boolean` | Yes | |
| `lx_push_number` | Yes | |
| `lx_push_lstring` | Yes | |
| `lx_push_ref` | Yes | |
| `lx_push_copy` | Yes | |
| `lx_pop` | Yes | |
| `lx_stack_top` | Yes | |
| `lx_type` | Yes | Returns Luau type code |
| `lx_to_number` | Yes | |
| `lx_to_boolean` | Yes | |
| `lx_to_lstring` | Yes | No coercion; copy-safe |
| `lx_rawlen` | Yes | |
| `lx_newtable` | Yes | |
| `lx_rawget` | Yes | |
| `lx_rawset` | Yes | |
| `lx_rawgeti` | Yes | |
| `lx_rawseti` | Yes | |
| `lx_setarray` | Yes | Does NOT pop values |
| `lx_ref` | Yes | Does NOT pop pinned value |
| `lx_unref` | Yes | Must call on Driver thread |
| `lx_register_native` | Yes | Installs trampoline closure |
| `lx_set_suspend_token` | Yes | Per-Isolate, not per-coroutine |
| `lx_get_suspend_token` | Yes | |
| `lx_set_global` | **No** | WASM bootstrap only; absent from `lx.h` |
| `lx_get_global` | **No** | WASM bootstrap only; absent from `lx.h` |
| `lx_openlibs` | Yes | Bitmask-selected standard libraries |
| `lx_sandbox` | Yes | Freezes global table |
| `lx_open_libs` | Yes | Legacy alias for `lx_openlibs(LX_LIB_STANDARD)` |
| `lx_gc_step` | Yes | |
| `lx_gc_collect` | Yes | |
| `lx_copy_error` | Yes | |
| `lx_push_integer` | Yes | Exported in WASM but absent from `WasmModuleExports` |
| `lx_to_integer` | Yes | Exported in WASM but absent from `WasmModuleExports` |
| `malloc` | — | Required by WASM memory marshaling |
| `free` | — | Required by WASM memory marshaling |

Note: `lx_push_integer` and `lx_to_integer` are exported from the WASM binary (`build-wasm.sh:100-101`) and declared in `lx.h` (`lx.h:153,210`), but are **not** declared in `WasmModuleExports` (`WasmModule.scala`). There is no way to push or read Luau integers from the Scala.js backend; all integer values are coerced to `Double` via `lx_push_number`/`lx_to_number`.

`lx_set_global` and `lx_get_global` are defined in `lx.cpp:332-341` and exported from the WASM binary (`build-wasm.sh:92-93`) but are **not** declared in `lx.h`. They are visible to the WASM backend's `WasmModuleExports` (`WasmModule.scala:58-59`) but invisible to any C or C++ consumer that includes only `lx.h`.
