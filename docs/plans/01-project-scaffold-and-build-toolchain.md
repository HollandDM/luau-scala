# Plan 01 — Project Scaffold and Build Toolchain

> **Implementing agent**: read every file listed under §8 References before touching any source. This document is self-contained and is the sole specification you follow. Do not infer intent from filenames alone; the rationale lives in the ADRs.

---

## 1. Milestone & Goal

This plan establishes the complete project skeleton that every subsequent plan builds on. When done, the repository contains:

- A Mill cross-build (`build.mill`) defining all Scala 3 modules — `core` (cross-platform), `panama` (JVM only), `wasm` (Scala.js only), `scheduler` (cross), `stdlib` (cross), `zio` (cross), `ce` (cross) — with correct inter-module dependencies and Scala.js configuration.
- A `shim/` directory containing a pinned Luau git submodule and a trivial stub `shim.cpp` (not the real Shim; that is P02's deliverable) whose only purpose is to exercise the build pipeline end-to-end.
- A native shared-library build pipeline driven by a Mill task that shells out to `clang++` / `llvm-ar`, producing `libluau-shim.so` (Linux) / `libluau-shim.dylib` (macOS) suitable for Panama consumption.
- An Emscripten build pipeline driven by a separate Mill task shelling out to `emcc`, producing `luau-shim.wasm` + `luau-shim.js` (loader).
- A `jextract`-based (or hand-written fallback) Panama binding generation step that reads the stub shim header and emits Java/Scala source stubs into `panama/src/generated/`.
- A CI skeleton (GitHub Actions) that builds and tests both targets on Linux x86-64.
- Smoke tests confirming (a) the native library loads via `System.loadLibrary` / `SymbolLookup`, and (b) the WASM module loads via the Node.js WASM API in a Scala.js test.

No real Shim logic is written here. No Binding trait, no Codec, no Scheduler, no effect adapters. Those belong to P02 through P08.

---

## 2. Dependencies

This plan has no upstream plan dependencies. It is the root of the dependency graph.

What this plan **provides** to downstream plans (artifacts and symbols they import):

| Artifact / Symbol | Consumed by |
|---|---|
| Mill module `core` (`ScalaModule` + `ScalaJSModule` via cross) | P03, P06, P07, P08 |
| Mill module `panama` (`ScalaModule`, JVM only) | P04 |
| Mill module `wasm` (`ScalaJSModule`) | P05 |
| Mill module `shim` (C++ build tasks) | P02 (fills in real source), P04, P05 |
| `libluau-shim.so` / `libluau-shim.dylib` artifact path | P04 |
| `luau-shim.wasm` + `luau-shim.js` artifact paths | P05 |
| Generated Panama stubs in `panama/src/generated/` | P04 |
| `shim/include/luau_shim.h` (stub header with `lx_` declarations) | P02 (extends), P04 (binds) |
| `shim/luau/` (Luau submodule, pinned to tag) | P02 |

---

## 3. Design Context

### 3.1 Why a Shim compiled twice from one source (ADR-0001)

ADR-0001 mandates embedding upstream Luau as-is via a slim C++ **Shim** that exposes a narrow `lx_*` ABI. The Shim is compiled from **one** `.cpp` source to:

- A native shared library (`libluau-shim.so`/`.dylib`) consumed by the **Panama backend** (`java.lang.foreign`).
- A WASM module (`luau-shim.wasm`) consumed by the **WASM backend** (Scala.js interop).

This single-source constraint means all longjmp-safety logic, all non-raising accessor usage, and the entire exported function list live in one file. The build system must support both compilation modes without diverging the source.

### 3.2 No `lua_pcall` across the boundary (ADR-0001)

The Resume boundary rule states that **all Luau execution must enter via `lua_resume`**, never `lua_call` or `lua_pcall`. These would `longjmp` across a Panama downcall frame or a WASM↔host frame, which is undefined behavior. This constraint does not affect P01 directly (no real execution here), but the build pipeline must produce a library that P04/P05 can call safely, and the stub shim header must already reflect `lx_resume` as the only execution entry.

### 3.3 Cross-build topology (CONTEXT terms)

The project's two **Binding backends** (Panama and WASM) require different Scala compilation targets: the **Panama backend** is a JVM `ScalaModule`; the **WASM backend** is a `ScalaJSModule`. The modules `core`, `scheduler`, `stdlib`, `zio`, and `ce` are platform-agnostic and must be published as cross-built (JVM + JS). Mill's `CrossPlatformScalaModule` pattern (a `trait` mixed into both a JVM object and an `object extends ScalaJSModule`) is the idiomatic way to achieve this without duplicating source.

### 3.4 Stackless tasks and WASM (ADR-0003, ADR-0004)

ADR-0003 establishes that **Tasks** are stackless: a parked Task is pure heap data. ADR-0004 establishes single-threaded execution for the MVP. Neither has direct build implications, but they inform why the WASM target is viable: Luau coroutines never hold a C stack between resumes, so the WASM linear memory model (single-threaded, no shared-memory concurrency by default) is fully compatible.

### 3.5 Deterministic Ref lifetime (ADR-0005)

ADR-0005 bans GC finalizers for **Ref** release. No build implication for P01 except that `core` must not pull in any JVM-only finalizer machinery (no `java.lang.ref.Cleaner` import in cross-compiled source). Enforce this at module boundary: `core`'s Mill module must not depend on any JVM-only module.

### 3.6 Copy-only boundary (ADR-0006) and tri-state return (ADR-0007)

No direct build implications for P01, but the stub shim header established here must leave room for the `lx_call_result` enum that P02 will define (tri-state: `LX_RETURN`, `LX_FAIL`, `LX_SUSPEND`), and the Panama binding generation step must be re-runnable in P02 after the header is extended.

---

## 4. Task Breakdown

Tasks are ordered. Complete each in sequence; later tasks depend on the physical files produced by earlier ones.

---

### Task 1: Initialize repository and `.gitmodules`

**File**: `/home/hoangdinh/OSS/luau-scala/.gitmodules`

Pin the Luau submodule to a specific release tag. As of the research doc (`/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-official-cpp.md`, §1), the latest release at research time is **0.724**. Pin to tag `0.724` of `https://github.com/luau-lang/luau`:

```ini
[submodule "shim/luau"]
    path = shim/luau
    url = https://github.com/luau-lang/luau.git
    shallow = true
```

Run:
```
git submodule add --depth 1 --branch 0.724 \
    https://github.com/luau-lang/luau shim/luau
git submodule update --init --depth 1
```

If branch `0.724` does not resolve to a tag, use:
```
git -C shim/luau checkout 0.724
```

The submodule commit SHA must be committed in `.gitmodules` + `.git/modules` so CI can reproduce the exact checkout. CI must run `git submodule update --init --recursive --depth 1` before any build step.

**Important**: the Luau submodule directory `shim/luau/` must be present for the shim build tasks in Task 5 to succeed, even though P01 compiles only a stub `shim.cpp` that does not include Luau headers. The CMakeLists or `build.mill` shim tasks reference `shim/luau/VM/include/` as an include path for the native build (it will be needed by P02; having it present now avoids a forced re-clone).

---

### Task 2: Top-level Mill build file

**File**: `/home/hoangdinh/OSS/luau-scala/build.mill`

This is the root Mill build descriptor. Mill 0.12+ uses `build.mill` (Scala 3 syntax). The build uses `mill.scalalib.ScalaModule`, `mill.scalajslib.ScalaJSModule`, and `mill.scalalib.scalafmt.ScalafmtModule`.

#### 2.1 Version constants (top of file)

```scala
import mill._, scalalib._, scalajslib._, scalafmt._

val scalaVersion     = "3.4.2"
val scalaJSVersion   = "1.16.0"
val zioVersion       = "2.1.6"
val catsEffectVersion= "3.5.4"
val munitVersion     = "1.0.0"
```

Pin these versions. Do not use version ranges. Updating versions is a deliberate, recorded change.

#### 2.2 Cross-platform module trait

The pattern for `core`, `scheduler`, `stdlib`, `zio`, and `ce` is a shared `trait` mixed into both a `jvm` and `js` submodule object. This avoids duplicating source declarations.

```scala
/** Shared source + settings for a cross-platform module. */
trait LuauCrossPlatformModule extends ScalaModule with ScalafmtModule {
  def scalaVersion = build.scalaVersion

  /** Subclasses override to add platform-specific deps. */
  def platformDeps: T[Agg[Dep]] = T { Agg.empty[Dep] }

  def ivyDeps = T { platformDeps() }

  def scalacOptions = T { Seq(
    "-encoding", "UTF-8",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xfatal-warnings",
    "-Ykind-projector:underscores",
    "-source:future"
  )}
}

trait LuauCrossPlatformJSModule extends LuauCrossPlatformModule with ScalaJSModule {
  def scalaJSVersion = build.scalaJSVersion
}
```

#### 2.3 Module objects

Define every module as a top-level `object` (or nested under a common parent object). The layout below shows the full module tree:

```scala
/** core: backend-agnostic abstractions (Binding trait, Ref, Scope, Codec,
 *  LuaError, NativeFn return ADT, Async primitive). Cross-platform. */
object core extends Module {
  object jvm extends LuauCrossPlatformModule {
    def moduleDeps = Seq()
    def sources    = T.sources { millSourcePath / "src" }
    // test sub-module:
    object test extends ScalaTests with TestModule.Munit {
      def ivyDeps = Agg(ivy"org.scalameta::munit::${munitVersion}")
    }
  }
  object js extends LuauCrossPlatformJSModule {
    def moduleDeps = Seq()
    def sources    = T.sources { millSourcePath / os.up / "jvm" / "src" }
    object test extends ScalaJSTests with TestModule.Munit {
      def ivyDeps = Agg(ivy"org.scalameta::munit::${munitVersion}")
    }
  }
}

/** panama: JVM-only Panama FFM binding backend. Depends on core.jvm. */
object panama extends LuauCrossPlatformModule {
  def moduleDeps  = Seq(core.jvm)
  def sources     = T.sources { millSourcePath / "src" }
  // Generated sources from jextract (Task 6):
  def generatedSources = T.sources { millSourcePath / "src" / "generated" }
  object test extends ScalaTests with TestModule.Munit {
    def ivyDeps = Agg(ivy"org.scalameta::munit::${munitVersion}")
    // Pass the native library directory to the JVM at test time:
    def forkArgs = T {
      val libDir = build.shim.nativeBuild().path / os.up
      Seq(
        s"-Djava.library.path=${libDir}",
        "--enable-native-access=ALL-UNNAMED",
        "--enable-preview"
      )
    }
  }
}

/** wasm: Scala.js WASM binding backend. Depends on core.js. */
object wasm extends LuauCrossPlatformJSModule {
  def moduleDeps = Seq(core.js)
  def sources    = T.sources { millSourcePath / "src" }
  object test extends ScalaJSTests with TestModule.Munit {
    def ivyDeps = Agg(ivy"org.scalameta::munit::${munitVersion}")
    // The WASM artifact path is injected via a resource at test time (Task 7).
  }
}

/** scheduler: single-threaded task scheduler. Cross-platform. Depends on core. */
object scheduler extends Module {
  object jvm extends LuauCrossPlatformModule {
    def moduleDeps = Seq(core.jvm)
    def sources    = T.sources { millSourcePath / "src" }
    object test extends ScalaTests with TestModule.Munit {
      def ivyDeps = Agg(ivy"org.scalameta::munit::${munitVersion}")
    }
  }
  object js extends LuauCrossPlatformJSModule {
    def moduleDeps = Seq(core.js)
    def sources    = T.sources { millSourcePath / os.up / "jvm" / "src" }
    object test extends ScalaJSTests with TestModule.Munit {
      def ivyDeps = Agg(ivy"org.scalameta::munit::${munitVersion}")
    }
  }
}

/** stdlib: Luau standard library openers + task library. Cross-platform. */
object stdlib extends Module {
  object jvm extends LuauCrossPlatformModule {
    def moduleDeps = Seq(scheduler.jvm)
    def sources    = T.sources { millSourcePath / "src" }
    object test extends ScalaTests with TestModule.Munit {
      def ivyDeps = Agg(ivy"org.scalameta::munit::${munitVersion}")
    }
  }
  object js extends LuauCrossPlatformJSModule {
    def moduleDeps = Seq(scheduler.js)
    def sources    = T.sources { millSourcePath / os.up / "jvm" / "src" }
    object test extends ScalaJSTests with TestModule.Munit {
      def ivyDeps = Agg(ivy"org.scalameta::munit::${munitVersion}")
    }
  }
}

/** zio: ZIO effect adapter. JVM-only (ZIO 2 is JVM + native, not JS). */
object zio extends LuauCrossPlatformModule {
  def moduleDeps = Seq(core.jvm, scheduler.jvm)
  def ivyDeps    = T { Agg(
    ivy"dev.zio::zio::${zioVersion}",
    ivy"dev.zio::zio-streams::${zioVersion}"
  )}
  def sources    = T.sources { millSourcePath / "src" }
  object test extends ScalaTests with TestModule.Munit {
    def ivyDeps = Agg(
      ivy"org.scalameta::munit::${munitVersion}",
      ivy"dev.zio::zio-test::${zioVersion}",
      ivy"dev.zio::zio-test-sbt::${zioVersion}"
    )
  }
}

/** ce: Cats Effect 3 adapter. Cross-platform (CE3 supports JS). */
object ce extends Module {
  object jvm extends LuauCrossPlatformModule {
    def moduleDeps = Seq(core.jvm, scheduler.jvm)
    def ivyDeps    = T { Agg(ivy"org.typelevel::cats-effect::${catsEffectVersion}") }
    def sources    = T.sources { millSourcePath / "src" }
    object test extends ScalaTests with TestModule.Munit {
      def ivyDeps = Agg(
        ivy"org.scalameta::munit::${munitVersion}",
        ivy"org.typelevel::munit-cats-effect::1.0.7"
      )
    }
  }
  object js extends LuauCrossPlatformJSModule {
    def moduleDeps = Seq(core.js, scheduler.js)
    def ivyDeps    = T { Agg(ivy"org.typelevel::cats-effect::${catsEffectVersion}") }
    def sources    = T.sources { millSourcePath / os.up / "jvm" / "src" }
    object test extends ScalaJSTests with TestModule.Munit {
      def ivyDeps = Agg(
        ivy"org.scalameta::munit::${munitVersion}",
        ivy"org.typelevel::munit-cats-effect::1.0.7"
      )
    }
  }
}

/** shim: C++ build tasks — not a ScalaModule. Houses native + WASM builds. */
object shim extends Module {
  // See Tasks 5a and 5b for task definitions.
}
```

#### 2.4 Source directory layout (physical)

The Mill `sources` declarations above map to this directory tree:

```
/home/hoangdinh/OSS/luau-scala/
  build.mill
  .mill-version                    ← contains "0.12.x" (see Task 3)
  .gitmodules
  shim/
    luau/                          ← git submodule (Luau 0.724)
    include/
      luau_shim.h                  ← stub header (Task 4)
    src/
      shim.cpp                     ← stub implementation (Task 4)
    CMakeLists.txt                 ← optional; Mill tasks shell out directly
  core/
    jvm/
      src/
        luau/core/                 ← empty package objects for now
      test/
        src/
    js/                            ← no separate src; shares core/jvm/src via millSourcePath override
  panama/
    src/
      luau/panama/                 ← empty package objects for now
      generated/                   ← jextract output lands here (Task 6)
    test/
      src/
  wasm/
    src/
      luau/wasm/
    test/
      src/
  scheduler/
    jvm/
      src/
        luau/scheduler/
      test/
        src/
  stdlib/
    jvm/
      src/
        luau/stdlib/
      test/
        src/
  zio/
    src/
      luau/zio/
    test/
      src/
  ce/
    jvm/
      src/
        luau/ce/
      test/
        src/
```

For cross-platform modules (`core`, `scheduler`, `stdlib`, `ce`), the `js` submodule overrides `sources` to point at `jvm/src/` so the two share one source tree. Platform-specific code in those modules (if any) goes in `jvm/src/luau/<module>/platform/JvmPlatform.scala` vs. `js/src/luau/<module>/platform/JsPlatform.scala`, using `expect`/`actual` or `given` summoning guarded by compile-time tags.

---

### Task 3: Mill version and `.mill-version`

**File**: `/home/hoangdinh/OSS/luau-scala/.mill-version`

```
0.12.3
```

Also create `/home/hoangdinh/OSS/luau-scala/.scalafmt.conf`:

```
version = "3.8.3"
runner.dialect = scala3
```

---

### Task 4: Stub Shim header and implementation

These are **temporary stubs** — they exist only to give the build pipeline something to compile against. P02 replaces their content with the real `lx_*` ABI.

#### 4.1 Stub header

**File**: `/home/hoangdinh/OSS/luau-scala/shim/include/luau_shim.h`

```c
/*
 * luau_shim.h — PUBLIC ABI for the Luau Shim.
 *
 * THIS FILE IS A STUB. The real declarations are written in P02
 * (docs/plans/02-cpp-shim-abi.md). The functions below are placeholders
 * so that the native + WASM build pipelines can be exercised end-to-end
 * in P01 without P02 being complete.
 *
 * Rules (enforced here and in the real header):
 *   - All functions are `extern "C"` to avoid C++ name mangling.
 *   - No Luau headers are exposed; all Luau types are opaque.
 *   - lx_resume is the ONLY execution entry point (ADR-0001).
 *   - No lua_pcall, no lua_call, no lua_error from the host side.
 */

#ifndef LUAU_SHIM_H
#define LUAU_SHIM_H

#ifdef __cplusplus
extern "C" {
#endif

/* Opaque handle to a Luau state. Allocated by lx_newstate, freed by lx_close. */
typedef struct lx_State lx_State;

/* Placeholder: version query. Returns 1 (stub). */
int lx_version(void);

/*
 * STUB lifecycle functions. Real implementations in P02.
 * lx_newstate / lx_close are the outer lifetime bracket of an Isolate.
 */
lx_State* lx_newstate(void);
void      lx_close(lx_State* L);

/*
 * STUB resume-boundary entry. Real signature in P02 will carry
 * argument count, result count, and a tri-state status return.
 * For now: takes a state pointer, does nothing, returns 0 (ok).
 */
int lx_resume(lx_State* L, int nargs);

#ifdef __cplusplus
}
#endif
#endif /* LUAU_SHIM_H */
```

#### 4.2 Stub implementation

**File**: `/home/hoangdinh/OSS/luau-scala/shim/src/shim.cpp`

```cpp
/*
 * shim.cpp — STUB implementation.
 *
 * This file compiles without any Luau headers. Its only purpose is to
 * verify that the Mill native build task (Task 5a) and Emscripten task
 * (Task 5b) both produce valid shared-library / WASM artifacts.
 *
 * Replace this file with the real Shim in P02.
 */

#include "luau_shim.h"
#include <cstdlib>

/* Opaque state: just a heap-allocated int for stub purposes. */
struct lx_State {
    int sentinel;
};

extern "C" {

int lx_version(void) {
    return 1;
}

lx_State* lx_newstate(void) {
    lx_State* s = static_cast<lx_State*>(std::malloc(sizeof(lx_State)));
    if (s) s->sentinel = 0xCA11AB1E;
    return s;
}

void lx_close(lx_State* L) {
    std::free(L);
}

int lx_resume(lx_State* /* L */, int /* nargs */) {
    return 0; /* LX_OK stub */
}

} /* extern "C" */
```

---

### Task 5a: Mill task — native shared library build

Add the following inside `object shim extends Module` in `build.mill`.

The task shells out to `clang++` and does not use CMake. It includes `shim/luau/VM/include/` and `shim/luau/Common/include/` on the include path (they are present via submodule even if the stub does not use them, so the build validates the include path for P02).

```scala
object shim extends Module {

  /** Directory where the shim C++ sources live. */
  def shimSrcDir    = T { millSourcePath }
  def shimIncDir    = T { millSourcePath / "include" }
  def luauIncVm     = T { millSourcePath / "luau" / "VM" / "include" }
  def luauIncCommon = T { millSourcePath / "luau" / "Common" / "include" }

  /**
   * Detect the platform and set the shared-library extension + linker flags.
   * Returns (extension, extraLinkerFlags).
   */
  def nativePlatform: T[(String, Seq[String])] = T {
    val os = System.getProperty("os.name", "").toLowerCase
    if (os.contains("mac")) ("dylib", Seq("-dynamiclib"))
    else                    ("so",    Seq("-shared", "-fPIC"))
  }

  /**
   * Compile shim/src/shim.cpp into a native shared library using clang++.
   *
   * Output artifact: out/shim/nativeBuild.dest/libluau-shim.{so,dylib}
   *
   * Prerequisites:
   *   clang++ (LLVM >= 14) must be on PATH.
   *   On Linux: also requires that the linker can find libc++ or libstdc++.
   *
   * This task is input-tracked: it re-runs when shim.cpp, luau_shim.h,
   * or the Luau submodule HEAD changes.
   */
  def nativeBuild: T[PathRef] = T {
    val dest        = T.dest
    val (ext, lflags) = nativePlatform()
    val outLib      = dest / s"libluau-shim.$ext"

    val srcFile     = shimSrcDir() / "src" / "shim.cpp"
    val includeDirs = Seq(
      shimIncDir().toString,
      luauIncVm().toString,
      luauIncCommon().toString
    )
    val includeArgs = includeDirs.flatMap(d => Seq("-I", d))

    val compileCmd = Seq("clang++") ++
      Seq("-std=c++17", "-O2", "-fvisibility=hidden") ++
      includeArgs ++
      lflags ++
      Seq("-o", outLib.toString, srcFile.toString)

    os.proc(compileCmd).call(cwd = dest, stdout = os.Inherit, stderr = os.Inherit)

    PathRef(outLib)
  }

  /**
   * Compile shim/src/shim.cpp to WASM using Emscripten (emcc).
   *
   * Outputs (both in T.dest):
   *   luau-shim.wasm  — WebAssembly binary
   *   luau-shim.js    — Emscripten JS loader (ES module wrapper)
   *
   * Prerequisites:
   *   emsdk activated: `source ~/emsdk/emsdk_env.sh` before running Mill,
   *   OR emcc on PATH. Minimum emsdk version: 3.1.50.
   *
   * EXPORTED_FUNCTIONS list must name every lx_* symbol with leading '_'.
   * The stub exports: _lx_version, _lx_newstate, _lx_close, _lx_resume.
   * P02 extends this list; re-run this task after P02 extends the header.
   *
   * MODULARIZE=1 + EXPORT_ES6=1 produces an ES module that Scala.js
   * can import via @JSImport. Node.js >= 18 handles ES modules natively.
   */
  def wasmBuild: T[PathRef] = T {
    val dest    = T.dest
    val outWasm = dest / "luau-shim.wasm"   // emcc writes this automatically
    val outJs   = dest / "luau-shim.js"

    val srcFile = shimSrcDir() / "src" / "shim.cpp"
    val includeDirs = Seq(
      shimIncDir().toString,
      luauIncVm().toString,
      luauIncCommon().toString
    )
    val includeArgs = includeDirs.flatMap(d => Seq("-I", d))

    val exportedFunctions = Seq(
      "_lx_version",
      "_lx_newstate",
      "_lx_close",
      "_lx_resume"
    ).mkString("[", ",", "]")

    val emccCmd = Seq("emcc") ++
      Seq("-std=c++17", "-O2") ++
      includeArgs ++
      Seq(
        "-s", "WASM=1",
        "-s", s"EXPORTED_FUNCTIONS=$exportedFunctions",
        "-s", "EXPORTED_RUNTIME_METHODS=[ccall,cwrap,getValue,setValue,UTF8ToString,stringToUTF8,_malloc,_free]",
        "-s", "MODULARIZE=1",
        "-s", "EXPORT_ES6=1",
        "-s", "ENVIRONMENT=node",
        "-s", "ALLOW_MEMORY_GROWTH=1",
        "-s", "STACK_SIZE=1048576",   // 1 MB C stack per Luau thread resume
        "-o", outJs.toString,
        srcFile.toString
      )

    os.proc(emccCmd).call(cwd = dest, stdout = os.Inherit, stderr = os.Inherit)

    // emcc writes both .js and .wasm to dest; return the .wasm as primary artifact.
    PathRef(outWasm)
  }

  /** Copy WASM artifacts into wasm module's test resources so tests can load them. */
  def copyWasmToResources: T[Unit] = T {
    val wasmDest = wasmBuild().path
    val jsDest   = wasmDest / os.up / "luau-shim.js"
    val resDir   = millSourcePath / os.up / "wasm" / "test" / "resources"
    os.makeDir.all(resDir)
    os.copy.over(wasmDest, resDir / "luau-shim.wasm")
    os.copy.over(jsDest,   resDir / "luau-shim.js")
  }
}
```

**Prerequisite toolchain versions** (document in `README.md` or `docs/dev-setup.md`; do not embed in `build.mill`):

| Tool | Minimum version | Notes |
|------|----------------|-------|
| clang++ | 14.0 | C++17 required; `clang++-14` or later |
| LLVM tools | same as clang | `llvm-ar`, `lld` optional |
| emsdk | 3.1.50 | `source ~/emsdk/emsdk_env.sh` before Mill |
| emcc | ships with emsdk | Do not install separately |
| Node.js | 18 LTS | For Scala.js tests; ES module support |
| Mill | 0.12.3 | See `.mill-version` |
| JDK | 21 | Panama FFM requires JDK 21; `--enable-native-access` flag |

---

### Task 5b: Input tracking for the Luau submodule

Mill's `T { ... }` tasks track their declared inputs. The shim build tasks reference `shimSrcDir()` and `luauIncVm()` via `PathRef`, so Mill will re-run them when files under those paths change. However, git submodule updates do not automatically invalidate Mill's output cache unless the submodule HEAD changes.

Add an explicit dependency on the submodule HEAD file so Mill detects Luau upgrades:

```scala
/** Read the Luau submodule HEAD to force re-build when submodule is updated. */
def luauSubmoduleHead: T[String] = T.input {
  val headFile = millSourcePath / "luau" / ".git" / "HEAD"
  if (os.exists(headFile)) os.read(headFile).trim
  else "absent"
}
```

Reference `luauSubmoduleHead()` from `nativeBuild` and `wasmBuild` (the call appears in the task body; Mill tracks it as a dependency automatically when it appears in `T { ... }`).

---

### Task 6: jextract Panama binding generation

**Purpose**: Generate Java/Scala source stubs from `luau_shim.h` so the `panama` module has typed `MethodHandle` and `FunctionDescriptor` accessors without hand-writing Panama boilerplate.

**Tool**: `jextract` from OpenJDK (ships with JDK 21 early access and available separately at https://jdk.java.net/jextract/). If `jextract` is unavailable in CI, fall back to a hand-written stubs file (see §4.6.2).

#### 6.1 Mill task

Add inside `object panama extends LuauCrossPlatformModule`:

```scala
object panama extends LuauCrossPlatformModule {
  // ... (as defined in Task 2) ...

  /**
   * Run jextract against the stub shim header and write generated sources
   * into panama/src/generated/. This task is idempotent: re-running
   * regenerates the same files. Commit the generated files to source control
   * so CI does not require jextract to be installed for ordinary builds.
   *
   * Re-run manually after P02 extends luau_shim.h.
   */
  def generateBindings: T[Unit] = T {
    val headerFile  = millSourcePath / os.up / "shim" / "include" / "luau_shim.h"
    val outDir      = millSourcePath / "src" / "generated"
    os.makeDir.all(outDir)

    val jextractCmd = Seq(
      "jextract",
      "--output", outDir.toString,
      "--target-package", "luau.panama.generated",
      "--library", "luau-shim",
      "--header-class-name", "LuauShimH",
      headerFile.toString
    )

    os.proc(jextractCmd).call(stdout = os.Inherit, stderr = os.Inherit)
  }

  // generatedSources already declared in Task 2 above.
}
```

#### 6.2 Fallback: hand-written stubs

If `jextract` is unavailable, the implementing agent writes the following file by hand. This is the minimal stub sufficient for P01's smoke test (Task 8) and P04's Panama backend:

**File**: `/home/hoangdinh/OSS/luau-scala/panama/src/generated/LuauShimBindings.scala`

```scala
package luau.panama.generated

import java.lang.foreign.*
import java.lang.invoke.MethodHandle

/**
 * Hand-written Panama FFM stubs for the lx_* ABI declared in luau_shim.h.
 *
 * Generated by: jextract (or hand-written fallback per P01 Task 6.2).
 * Re-generate (or update by hand) after P02 extends the header.
 *
 * All method handles are looked up lazily from the provided SymbolLookup.
 * Callers obtain a Linker + SymbolLookup from SymbolLookup.libraryLookup()
 * and pass it to shimOf().
 */
object LuauShimBindings {

  private val linker: Linker = Linker.nativeLinker()

  /** All method handles for the lx_* ABI, resolved against a given lookup. */
  final class Handles(lookup: SymbolLookup) {
    private def mh(name: String, desc: FunctionDescriptor): MethodHandle =
      linker.downcallHandle(
        lookup.find(name).orElseThrow(() =>
          new UnsatisfiedLinkError(s"Symbol not found: $name")),
        desc
      )

    /** int lx_version(void) */
    val lx_version: MethodHandle = mh(
      "lx_version",
      FunctionDescriptor.of(ValueLayout.JAVA_INT)
    )

    /** lx_State* lx_newstate(void) — returns a pointer as MemorySegment */
    val lx_newstate: MethodHandle = mh(
      "lx_newstate",
      FunctionDescriptor.of(ValueLayout.ADDRESS)
    )

    /** void lx_close(lx_State* L) */
    val lx_close: MethodHandle = mh(
      "lx_close",
      FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
    )

    /** int lx_resume(lx_State* L, int nargs) */
    val lx_resume: MethodHandle = mh(
      "lx_resume",
      FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_INT
      )
    )
  }

  /**
   * Load the native library by name and return resolved Handles.
   * libraryName should be "luau-shim" (maps to libluau-shim.so / .dylib).
   * The JVM must be started with -Djava.library.path=<dir containing the .so>.
   */
  def shimOf(libraryName: String): Handles = {
    val lookup = SymbolLookup.libraryLookup(
      System.mapLibraryName(libraryName),
      Arena.global()
    )
    new Handles(lookup)
  }
}
```

**Note**: P04 will extend this with the full `lx_*` ABI and add `FunctionDescriptor` for the upcall trampoline. This stub is only for P01 smoke tests.

---

### Task 7: Scala.js WASM loader stub

**File**: `/home/hoangdinh/OSS/luau-scala/wasm/src/luau/wasm/LuauWasmLoader.scala`

This is a stub that proves the Scala.js module compiles against the WASM build artifact path. P05 replaces it with the real WASM backend.

```scala
package luau.wasm

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array
import scala.concurrent.{Future, ExecutionContext}

/**
 * Stub WASM loader. Verifies that the Scala.js module compiles and that
 * the emcc-generated ES module can be imported. Replace in P05 with the
 * real WASM binding backend.
 *
 * At runtime, luau-shim.js must be in the Node.js module resolution path.
 * The Mill copyWasmToResources task places it in wasm/test/resources/.
 */
object LuauWasmLoader {

  /**
   * Load the Emscripten module asynchronously. Returns a Future that
   * resolves when the WASM module is instantiated.
   *
   * In Node.js test runs, call this once before any lx_* call.
   * In a browser, provide the .wasm URL as the `locateFile` option.
   */
  def load()(implicit ec: ExecutionContext): Future[LuauShimModule] = {
    // Stub: import the ES module factory and instantiate it.
    // The @JSImport path will resolve against Node's module path in tests.
    val factory = LuauShimFactory.apply(js.Dynamic.literal())
    Future.successful(factory.asInstanceOf[LuauShimModule])
  }
}

/** JS type facade for the Emscripten module instance. Filled in by P05. */
@js.native
trait LuauShimModule extends js.Object {
  def _lx_version(): Int = js.native
}

/** Emscripten ES module factory function imported from luau-shim.js. */
@js.native
@JSImport("./luau-shim.js", JSImport.Default)
object LuauShimFactory extends js.Object {
  def apply(options: js.Object): js.Promise[LuauShimModule] = js.native
}
```

---

### Task 8: Empty placeholder source files (package objects)

For each Scala module that has no real code yet, create a minimal package object so Mill does not fail on empty source directories.

**Files to create** (one per module package):

```
/home/hoangdinh/OSS/luau-scala/core/jvm/src/luau/core/package.scala
/home/hoangdinh/OSS/luau-scala/scheduler/jvm/src/luau/scheduler/package.scala
/home/hoangdinh/OSS/luau-scala/stdlib/jvm/src/luau/stdlib/package.scala
/home/hoangdinh/OSS/luau-scala/zio/src/luau/zio/package.scala
/home/hoangdinh/OSS/luau-scala/ce/jvm/src/luau/ce/package.scala
```

Template (replace `<module>` with the module name):

```scala
package luau.<module>

// Placeholder. Real declarations added in their respective plans.
```

For `panama`, the `LuauShimBindings.scala` stub from Task 6.2 serves as the placeholder.

For `wasm`, the `LuauWasmLoader.scala` stub from Task 7 serves as the placeholder.

---

### Task 9: Smoke tests

Smoke tests verify infrastructure only — library loading and WASM module instantiation. They do not test Luau execution (that is P02+P04/P05).

#### 9.1 Panama smoke test (JVM)

**File**: `/home/hoangdinh/OSS/luau-scala/panama/test/src/luau/panama/NativeLibSmokeTest.scala`

```scala
package luau.panama

import munit.FunSuite
import luau.panama.generated.LuauShimBindings
import java.lang.foreign.*

/**
 * Smoke test: load the stub native library and call lx_version().
 * Verifies:
 *   1. libluau-shim.so (or .dylib) was produced by shim.nativeBuild.
 *   2. SymbolLookup finds lx_version.
 *   3. The downcall via Panama returns the stub value (1).
 *
 * Run: ./mill panama.test
 * JVM flags required (set in build.mill panama.test.forkArgs):
 *   -Djava.library.path=<out/shim/nativeBuild.dest>
 *   --enable-native-access=ALL-UNNAMED
 */
class NativeLibSmokeTest extends FunSuite {

  test("lx_version returns stub value 1") {
    val handles  = LuauShimBindings.shimOf("luau-shim")
    val version  = handles.lx_version.invokeExact().asInstanceOf[Int]
    assertEquals(version, 1)
  }

  test("lx_newstate returns non-null pointer") {
    val handles  = LuauShimBindings.shimOf("luau-shim")
    val state    = handles.lx_newstate.invokeExact().asInstanceOf[MemorySegment]
    assert(!MemorySegment.NULL.equals(state), "lx_newstate returned NULL")
    // Clean up: call lx_close.
    handles.lx_close.invokeExact(state)
  }

  test("lx_resume on fresh state returns 0 (ok)") {
    val handles  = LuauShimBindings.shimOf("luau-shim")
    val state    = handles.lx_newstate.invokeExact().asInstanceOf[MemorySegment]
    val status   = handles.lx_resume.invokeExact(state, 0).asInstanceOf[Int]
    assertEquals(status, 0)
    handles.lx_close.invokeExact(state)
  }
}
```

**How to run**:
```
./mill shim.nativeBuild          # builds libluau-shim.so first
./mill panama.test               # JVM forkArgs inject library path
```

#### 9.2 WASM smoke test (Scala.js / Node.js)

**File**: `/home/hoangdinh/OSS/luau-scala/wasm/test/src/luau/wasm/WasmModuleSmokeTest.scala`

```scala
package luau.wasm

import munit.FunSuite
import scala.concurrent.{Future, ExecutionContext}
import scala.scalajs.js

/**
 * Smoke test: load the stub WASM module and call _lx_version().
 * Verifies:
 *   1. luau-shim.wasm was produced by shim.wasmBuild.
 *   2. The Emscripten ES module loader initializes without error.
 *   3. The exported _lx_version function returns the stub value (1).
 *
 * Run:
 *   ./mill shim.wasmBuild            # builds WASM artifacts
 *   ./mill shim.copyWasmToResources  # copies to wasm/test/resources/
 *   ./mill wasm.test                 # Node.js runs the JS tests
 *
 * Node.js >= 18 required (ES module + WASM support).
 */
class WasmModuleSmokeTest extends munit.FunSuite {

  implicit val ec: ExecutionContext = ExecutionContext.global

  test("WASM module loads and lx_version returns 1".ignore) {
    // TODO (P05): Replace with real async load + assertion.
    // Marked .ignore for P01 because LuauShimFactory is a stub
    // and requires a real WASM artifact on the Node path.
    //
    // When P05 is implemented:
    //   LuauWasmLoader.load().map { mod =>
    //     assertEquals(mod._lx_version(), 1)
    //   }
    ()
  }
}
```

The WASM smoke test is intentionally marked `ignore` in P01. P05 unignores it and provides the full assertion. The compile-time test of the Scala.js module (that it compiles against the generated JS facade types) is sufficient for P01's acceptance criteria.

---

### Task 10: GitHub Actions CI skeleton

**File**: `/home/hoangdinh/OSS/luau-scala/.github/workflows/ci.yml`

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  build-and-test:
    runs-on: ubuntu-22.04

    steps:
      - name: Checkout with submodules
        uses: actions/checkout@v4
        with:
          submodules: recursive
          fetch-depth: 0

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Set up Node.js 20
        uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Install clang++ 17
        run: |
          sudo apt-get update -qq
          sudo apt-get install -y clang-17 llvm-17
          sudo update-alternatives --install /usr/bin/clang++ clang++ /usr/bin/clang++-17 100

      - name: Install emsdk
        run: |
          git clone https://github.com/emscripten-core/emsdk.git /tmp/emsdk
          /tmp/emsdk/emsdk install 3.1.50
          /tmp/emsdk/emsdk activate 3.1.50
          # Export PATH for subsequent steps via GITHUB_PATH:
          echo "/tmp/emsdk:/tmp/emsdk/upstream/emscripten" >> $GITHUB_PATH
          echo "EMSDK=/tmp/emsdk" >> $GITHUB_ENV

      - name: Cache Mill + Coursier
        uses: actions/cache@v4
        with:
          path: |
            ~/.mill
            ~/.cache/coursier
          key: mill-${{ hashFiles('.mill-version', 'build.mill') }}

      - name: Download Mill
        run: |
          curl -L https://github.com/com-lihaoyi/mill/releases/download/0.12.3/mill \
               -o ./mill && chmod +x ./mill

      - name: Compile all Scala modules
        run: |
          ./mill __.compile

      - name: Build native shim
        run: |
          ./mill shim.nativeBuild

      - name: Build WASM shim
        run: |
          source /tmp/emsdk/emsdk_env.sh
          ./mill shim.wasmBuild

      - name: Copy WASM to test resources
        run: |
          ./mill shim.copyWasmToResources

      - name: Run Panama smoke tests
        run: |
          ./mill panama.test

      - name: Run Scala.js tests (includes WASM compile check)
        run: |
          ./mill wasm.test

      - name: Run all other tests
        run: |
          ./mill __.test
```

**CI notes**:
- `ubuntu-22.04` ships `clang-14`; the step installs `clang-17` explicitly for C++17 support.
- emsdk is installed at `/tmp/emsdk`. The `source emsdk_env.sh` step must precede `emcc` invocations; the GitHub Actions step that calls `wasmBuild` sources it inline.
- `__.compile` (double-underscore wildcard) compiles all modules. Use this only in CI; locally, compile specific modules.
- `jextract` is not run in CI; generated bindings are committed to source control (Task 6.2 hand-written stubs).

---

### Task 11: `.gitignore` and project hygiene

**File**: `/home/hoangdinh/OSS/luau-scala/.gitignore`

```gitignore
# Mill build outputs
out/

# IntelliJ / Metals
.idea/
.bsp/
.metals/
.scala-build/

# Scala.js / Node
node_modules/
*.js.map

# Native build artifacts (not committed; regenerated by Mill tasks)
*.so
*.dylib
*.o
*.a

# WASM artifacts (committed only when in src/generated or test/resources)
# out/**/*.wasm intentionally excluded by out/ rule above

# macOS
.DS_Store

# Emscripten
*.bc
*.ll
```

**Files that MUST be committed** (do not gitignore):
- `panama/src/generated/LuauShimBindings.scala` (hand-written stubs, or jextract output)
- `wasm/test/resources/luau-shim.wasm` and `wasm/test/resources/luau-shim.js` — these are small test fixtures; commit them so CI can run `wasm.test` without re-running `emcc`.

Alternatively, add the WASM test resources to the `.gitignore` and ensure the CI workflow runs `shim.wasmBuild` + `shim.copyWasmToResources` before `wasm.test`. The CI skeleton in Task 10 already does this.

---

## 5. Acceptance Criteria & Tests

Every criterion below must pass before this plan is considered complete. All test invocations assume the project root as CWD.

### AC-1: All Scala modules compile

```
./mill __.compile
```

Expected: no compilation errors across `core.jvm`, `core.js`, `panama`, `wasm`, `scheduler.jvm`, `scheduler.js`, `stdlib.jvm`, `stdlib.js`, `zio`, `ce.jvm`, `ce.js`.

### AC-2: `core.js` does not reference any JVM-only API

Check: run `./mill core.js.compile` with Scala.js. If it compiles, the module is platform-safe. Any accidental use of `java.lang.*` (other than `java.lang.String`, `java.lang.Object`, standard exceptions) will cause a compile error under Scala.js.

### AC-3: Native library is produced

```
./mill shim.nativeBuild
```

Expected: file `out/shim/nativeBuild.dest/libluau-shim.so` (or `.dylib` on macOS) exists and is a valid ELF/Mach-O shared library. Verify:

```bash
file out/shim/nativeBuild.dest/libluau-shim.so
# → ELF 64-bit LSB shared object, x86-64, dynamically linked
nm -D out/shim/nativeBuild.dest/libluau-shim.so | grep -E 'lx_'
# → must show lx_version, lx_newstate, lx_close, lx_resume as T symbols
```

### AC-4: WASM module is produced

```
./mill shim.wasmBuild
```

Expected: `out/shim/wasmBuild.dest/luau-shim.wasm` is a valid WASM binary and `luau-shim.js` is an Emscripten ES module loader. Verify:

```bash
file out/shim/wasmBuild.dest/luau-shim.wasm
# → WebAssembly (wasm) binary module
wasm-objdump -x out/shim/wasmBuild.dest/luau-shim.wasm | grep -E 'lx_'
# → must show lx_version, lx_newstate, lx_close, lx_resume in export section
```

If `wasm-objdump` is not available, use: `node -e "const b=require('fs').readFileSync('out/shim/wasmBuild.dest/luau-shim.wasm'); const m=new WebAssembly.Module(b); console.log(WebAssembly.Module.exports(m))"`.

### AC-5: Panama smoke tests pass

```
./mill shim.nativeBuild && ./mill panama.test
```

Named tests that must pass:
- `NativeLibSmokeTest / lx_version returns stub value 1`
- `NativeLibSmokeTest / lx_newstate returns non-null pointer`
- `NativeLibSmokeTest / lx_resume on fresh state returns 0 (ok)`

### AC-6: Scala.js tests compile and the ignored WASM test is present

```
./mill wasm.test
```

Expected: all non-ignored tests pass (there are none at this stage). The `WasmModuleSmokeTest / WASM module loads` test must appear in the output as IGNORED (not FAILED). A FAILED result means the test scaffold itself is broken.

### AC-7: CI pipeline passes end-to-end

Push to a branch and observe the GitHub Actions run. All steps must be green. This is the definitive acceptance criterion because it validates the environment-reproducibility of the build.

### AC-8: Submodule is pinned

```
git submodule status shim/luau
```

Expected: output shows a fixed commit SHA matching tag `0.724`, not a detached HEAD pointing at `HEAD` of a branch. The SHA is committed in `.gitmodules`. Re-cloning the repository and running `git submodule update --init --recursive --depth 1` must produce the same result.

---

## 6. Risks and Gotchas

### 6.1 emcc + emsdk version sensitivity

Emscripten is notoriously version-sensitive. The `MODULARIZE=1 + EXPORT_ES6=1` flags used in Task 5a produce an ES module that Node.js >= 18 can `import()`. Older Node.js (< 18) does not support top-level `await` in ES modules, which Emscripten's loader relies on. Pin emsdk to `3.1.50` exactly in CI; do not use `latest`.

The `STACK_SIZE` setting (1 MB) is important: Luau's `lua_resume` C call depth can reach several hundred frames for deeply nested functions. The default Emscripten stack (64 KB) is too small. See `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-official-cpp.md` §4.5 (dispatch loop using computed gotos) — each coroutine frame adds to the C stack within a `lua_resume` call.

### 6.2 Panama `--enable-native-access` requirement

JDK 21 requires `--enable-native-access=ALL-UNNAMED` (or a module-specific flag) for any code that calls `java.lang.foreign.*`. Forgetting this flag produces a runtime `IllegalCallerException` at the point of the first `Linker.nativeLinker()` call, not at compile time. The Mill `forkArgs` in `panama.test` handles this (Task 2); if running via IDE, the same JVM flag must be set manually.

### 6.3 Luau submodule shallow clone depth

`--depth 1` reduces clone size but makes `git submodule update --remote` impossible without `--unshallow`. This is intentional: we pin a specific tag. If you need to upgrade Luau, explicitly change the `.gitmodules` ref and commit the new SHA. Do not use `--remote` to silently update to HEAD.

The submodule URL points to `github.com/luau-lang/luau`. As noted in `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-official-cpp.md` §1, the repository is C++17 for the Compiler and Analysis modules but C++11 for the VM/runtime. The stub `shim.cpp` uses C++17 (`-std=c++17`) which is fine for both.

### 6.4 macOS dylib install_name

On macOS, `clang++ -dynamiclib` produces a dylib with an embedded `install_name` of the absolute build path. Loading it via `SymbolLookup.libraryLookup` with a path works, but `System.loadLibrary("luau-shim")` uses `java.library.path`, which must contain the directory. For the P01 smoke test, using `SymbolLookup.libraryLookup(path, Arena.global())` with the absolute path from `shim.nativeBuild().path` is more reliable than `System.loadLibrary`.

### 6.5 Scala.js `@JSImport` path resolution in tests

The `@JSImport("./luau-shim.js", ...)` in `LuauWasmLoader.scala` resolves relative to the compiled `.js` output directory, not relative to the Scala source. In Scala.js test mode under Mill, the working directory during test execution is `out/wasm/test/` (or similar). Ensure `luau-shim.js` and `luau-shim.wasm` are copied there via `copyWasmToResources` before running `wasm.test`.

P05 will need to handle this path resolution more carefully; for P01, the test is ignored, so the path only needs to compile, not resolve at runtime.

### 6.6 `__` wildcard in Mill compile step

`./mill __.compile` (double-underscore) recurses into all modules. On large projects this can be slow. In this project it is acceptable for CI. Never use `__` for `test` in CI (it runs all tests sequentially); instead target individual test modules.

### 6.7 jextract tool availability

`jextract` is distributed as a separate download from JDK 21 (see https://jdk.java.net/jextract/). It is NOT bundled with standard JDK distributions. The CI skeleton in Task 10 does NOT install jextract — it relies on the committed hand-written stubs (Task 6.2). If you choose to use jextract for real generation, add an installation step to CI and commit the generated output so CI does not depend on the jextract version.

### 6.8 Cross-platform source sharing via `millSourcePath / os.up`

The `js` module overrides `sources` to point at `jvm/src/` using `millSourcePath / os.up / "jvm" / "src"`. This works only if the physical directory layout matches (Task 2 §2.4). If the directory is missing, Mill silently compiles zero sources — the module will compile successfully but produce no classes. Verify with `./mill core.js.allSources` that it shows the expected files.

### 6.9 WASM and shared memory (ADR-0002)

ADR-0002 notes that WASM cross-worker migration would require `SharedArrayBuffer`-backed linear memory, gated behind COOP/COEP headers. The P01 build does NOT use `SHARED_MEMORY=1`. Do not add this flag without reading ADR-0002 first; the current emsdk settings are intentionally single-threaded.

---

## 7. Out of Scope / Deferred

| What | Plan that owns it |
|------|-------------------|
| Real `lx_*` function implementations (state lifecycle, compile, resume, value push/read, trampoline) | P02 — `docs/plans/02-cpp-shim-abi.md` |
| Real EXPORTED_FUNCTIONS list for the WASM build | P02 (extend Task 5a's exported list after P02 defines all `lx_*` symbols) |
| `Binding` trait and platform handles | P03 — `docs/plans/03-core-abstractions.md` |
| Panama backend implementation (`MethodHandle` downcalls, upcall stub, Arena-as-Scope) | P04 — `docs/plans/04-panama-backend-jvm.md` |
| WASM backend implementation (`cwrap`, linear memory marshaling, `addFunction` upcall) | P05 — `docs/plans/05-wasm-backend-js.md` |
| Single-threaded Scheduler (Run queue, Task lifecycle, Driver loop) | P06 — `docs/plans/06-scheduler-and-task-model.md` |
| Luau stdlib opening + `task` library natives | P07 — `docs/plans/07-stdlib-and-task-library.md` |
| ZIO and Cats Effect effect adapters | P08 — `docs/plans/08-effect-adapters-zio-cats.md` |
| Multi-core parallelism via Isolates across worker pool | ADR-0002 (deferred MVP) |
| Native codegen (`--!native`, `@native`) | Intentionally excluded; upstream feature, no build changes needed |
| Analysis / type checker (`luau-analyze`) | Out of scope for this runtime-embedding project |
| Cross-publication to Maven / npm | Not addressed in any plan; add as a separate publishing plan if needed |
| Windows build (`cl.exe` + MSVC, or clang-cl) | Not addressed; CI targets Linux. macOS is supported via `nativePlatform` detection but not CI-tested in P01 |

---

## 8. References

The implementing agent must read these files before starting any task. They are the authoritative source for all terms, constraints, and rationale referenced in this plan.

### ADRs (all in `/home/hoangdinh/OSS/luau-scala/docs/adr/`)

| File | What it constrains in P01 |
|------|--------------------------|
| `0001-embed-upstream-luau-via-slim-cpp-shim.md` | Why there is a C++ Shim; the no-`lua_pcall` boundary rule; the dual-compilation requirement (native + WASM from one source) |
| `0002-movable-state-actor-concurrency.md` | Why WASM does NOT use `SharedArrayBuffer`; single-threaded MVP scope |
| `0003-stackless-task-model.md` | Why WASM is viable (Tasks are pure heap data); no OS-thread-per-coroutine |
| `0004-coroutine-substrate-task-on-top.md` | Single-threaded Scheduler MVP; off-Driver completions still enqueue |
| `0005-deterministic-ref-lifetime-no-finalizer.md` | Why `core` must not import JVM-only finalizer APIs |
| `0006-copy-only-data-boundary-via-codec-typeclass.md` | Why there is no userdata in the Shim; no `__gc` upcall |
| `0007-callback-based-async-and-tristate-native-return.md` | The tri-state return the real Shim will use; stub header must leave room for it |

### CONTEXT.md

`/home/hoangdinh/OSS/luau-scala/CONTEXT.md` — glossary of all terms used in this plan. If a term appears in this plan document, its definition is here. Do not invent new terms; do not use the "Avoid" synonyms.

Key terms for P01: **Runtime**, **Host**, **Shim**, **Binding backend**, **Panama backend**, **WASM backend**, **Ref**, **Scope**, **Isolate**, **Resume boundary**, **Driver**, **Run queue**.

### Research docs

| File | Relevant sections |
|------|------------------|
| `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-official-cpp.md` | §1 (repo layout — understand what lives in `luau/VM/include/`, `luau/Compiler/`, etc. before writing include paths); §4.5 (dispatch loop stack depth — informs WASM `STACK_SIZE`); §4.8 (coroutines — confirms stackless resume model) |
| `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-rust-ecosystem.md` | §4.2 (`luau-src` build approach — reference for how others compile Luau C++ via a build system; do not copy their approach, but understand include paths and which source directories are needed) |

### External references (do not fetch during implementation; consult only if needed)

- Mill documentation: https://mill-build.org/mill/0.12.3/ — especially `ScalaModule`, `ScalaJSModule`, `T.dest`, `os.proc`.
- emsdk installation: https://emscripten.org/docs/getting_started/downloads.html
- jextract: https://jdk.java.net/jextract/
- Panama FFM tutorial: https://openjdk.org/jeps/454 (JEP 454, JDK 22 finalized)
- Luau tag `0.724` commit: https://github.com/luau-lang/luau/releases/tag/0.724

---

*End of Plan 01.*
