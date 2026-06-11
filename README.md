# luau-scala

Scala bindings for [Luau](https://luau.org/) — Roblox's gradually-typed, sandboxable Lua dialect — with two backends compiled from one shared core:

- **JVM** via [Panama](https://openjdk.org/projects/panama/) (`java.lang.foreign`) calling a native C shim
- **Scala.js** via a WebAssembly build of the same shim (Emscripten)

> **Status: experimental.** APIs change without notice. Capture checking is an experimental Scala 3 feature.

## Highlights

- **Typed facade (`luau.api`)** — no raw stack indices in any public signature, the Lua stack is balanced after every call, and every operation that interprets a Lua value returns `Try` (failing on reference data where a copyable value is expected).
- **No dangling handles, checked at compile time** — Lua functions/tables/coroutines are pinned inside a `useRef` scope and carry the scope in their type via [capture checking](https://docs.scala-lang.org/scala3/reference/experimental/cc.html). Letting a handle (or the `LuaState` itself) escape its block is a compile error, not a runtime crash.
- **Fault isolation by construction** — every chunk runs on a fresh Luau thread; a script that errors can never corrupt the VM for later calls.
- **Always sandboxed** — stdlib globals are frozen (`luaL_sandbox` + `luaL_sandboxthread`); host and script globals land in a writable proxy environment.
- **Codec-based marshalling** — `LuauEncoder`/`LuauDecoder` typeclasses with primitives, collections, `Option`, and case-class derivation.
- **Tested against upstream** — the shared test suite (including the portable subset of Luau's own conformance scripts) runs on both backends.

## Example

```scala
import luau.panama.PanamaLuau   // JVM; use luau.wasm.WasmLuau on Scala.js

PanamaLuau.withState() { st =>            // fresh VM, sandboxed, closed on exit
  st.set("answer", 21.0)
  st.defineGlobal("hostAdd")((a: Double, b: Double) => a + b)

  val v: scala.util.Try[Double] =
    st.eval[Double]("return hostAdd(answer, 21)")   // Success(42.0)

  st.eval[Map[String, Double]]("return { alpha = 1, beta = 2 }")
  st.eval2[Double, String]("return 1, 'two'")       // multi-result, strict:
  st.eval[Double]("return 1, 2")                    // Failure: 2 results, 1 consumed
  st.eval[Double]("return function() end")          // Failure: not copyable

  st.useRef {
    val fn = st.evalFn("return function(x) return x * 2 end").get
    fn.call[Double](21.0)                           // Success(42.0)

    st.run("config = { level = 3, onTick = function() end, 10, 20, 30 }")
    val tbl = st.getTbl("config").get               // pinned table handle
    tbl.get[Double]("level"); tbl.get[Double](3)    // fields + array elems
    tbl.getFn("onTick")                             // handles out of tables

    val co = st.coro(fn)                            // coroutine over fn
    co.resume[Double](1.0)                          // Yielded / Done steps
  }                                                 // pins released (LIFO) here
}                                                   // VM closed here
```

Escapes are compile errors:

```scala
val leaked = PanamaLuau.withState()(st => st)
// error: Capability `st` outlives its scope

st.useRef { outerVar = Some(st.evalFn("...").get) }
// error: capability `s` cannot flow into capture set {}
```

## Architecture

| Layer | Package | Role |
|---|---|---|
| Facade | `luau.api` | User surface: `Luau.withState`, `LuaState` (value plane), `useRef`/handles (identity plane) |
| Core SPI | `luau.core` | `Binding[H]` backend contract, codecs, `LuauLib`, registry refs |
| JVM backend | `luau.panama` | Panama downcalls into the native shim (`shim/src/lx.cpp`) |
| JS backend | `luau.wasm` | Same shim compiled to wasm, driven from Scala.js |
| C shim | `shim/` | Small `lx_*` C ABI over the vendored Luau VM (`shim/luau` submodule) |

`core/src` and `core/test/src` are shared sources — the `core.jvm` and `core.js` Mill modules compile the same files for each platform.

## Building

Requirements:

- JDK 21+ (tests fork with `--enable-preview` for `java.lang.foreign`)
- `clang++` (native shim)
- [Emscripten](https://emscripten.org/) `emcc` (wasm shim) and Node.js (JS tests)

```bash
git clone --recurse-submodules git@github.com:HollandDM/luau-scala.git
cd luau-scala

# full test suite, both platforms (builds native + wasm shims on demand)
./mill-launcher.sh panama.test + core.jvm.test + wasm.test + core.js.test
```

## License

The vendored [Luau](https://github.com/luau-lang/luau) submodule is MIT-licensed by Roblox Corporation.
