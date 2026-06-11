# luau-scala

Scala bindings for [Luau](https://luau.org/) — Roblox's gradually-typed, sandboxable Lua dialect — with two backends compiled from one shared core:

- **JVM** via [Panama](https://openjdk.org/projects/panama/) (`java.lang.foreign`) calling a native C shim
- **Scala.js** via a WebAssembly build of the same shim (wasm32-wasi, custom exception-enabled sysroot)

> **Status: experimental.** APIs change without notice. Capture checking is an experimental Scala 3 feature.

## Highlights

- **Task-based execution (`withTasks`)** — the single entry point. Phase 1 sets up a live *task world* (spawn scripts, define host functions, wire async ops); the world runs to quiescence on a scheduler; phase 2 reads the results. Returns a `TaskResult[A]` (`poll`/`onComplete` everywhere, `await(timeout)` on the JVM). Spawning hands back a `TaskHandle` with `cancel()`, `isDone`, and `results` — the task's return values once it completes.
- **Roblox `task` library built in** — `task.spawn`, `task.defer`, `task.delay`, `task.wait`, `task.cancel` are auto-installed and behave per Roblox semantics: spawn runs immediately to first yield, defer runs next tick, wait returns actual elapsed time. `spawn`/`defer`/`delay` take a function **or an existing coroutine thread** (the scheduler adopts it), and `task.cancel` resets the coroutine so `coroutine.status` reports `"dead"`. The Roblox/Lune `warn` global is installed alongside.
- **Async host functions** — `defineAsync` exposes any callback-based host operation to scripts. The calling script suspends without blocking a thread, the native stack fully unwinds, and completion (from any thread) re-enqueues the task. Cancellation is first-class: pending ops are cancelled on deadline or teardown.
- **Typed facade (`luau.api`)** — no raw stack indices in any public signature, the Lua stack is balanced after every call, and every operation that interprets a Lua value returns `Try` (failing on reference data where a copyable value is expected).
- **Exact-match multi-results** — `eval0`…`eval4`, `call0`…`call4`, `resume0`…`resume4` fail unless the chunk/function/coroutine step produces exactly the requested arity. Lua's adjust semantics (drop extras, nil-pad missing) hide contract mismatches; the host boundary refuses both directions.
- **No dangling handles, checked at compile time** — Lua functions/tables/coroutines are pinned inside a `useRef` scope and carry the scope in their type via [capture checking](https://docs.scala-lang.org/scala3/reference/experimental/cc.html). Letting a handle escape its block is a compile error, not a runtime crash.
- **Fault isolation by construction** — every chunk runs on a fresh Luau thread; a script that errors can never corrupt the VM for later calls. Task errors fail fast: the world is cancelled and the failure surfaces in the `TaskResult`.
- **Always sandboxed** — stdlib globals are frozen (`luaL_sandbox` + `luaL_sandboxthread`); `io`/`package`/`require`/`dofile`/`loadfile` and `os.execute`/`os.exit`/`os.getenv` are removed unconditionally; host and script globals land in a writable proxy environment. Library selection via `Set[LuauLib]` (default: everything but `Debug`).
- **Codec-based marshalling** — `LuauEncoder`/`LuauDecoder` typeclasses; data crosses the boundary only by copy, streamed (no intermediate tree). Primitives, `String`, byte arrays, `Option`, `Seq`/`List`/`Array`/`Vector`, `Map[String, V]`, and case-class derivation out of the box.
- **Ergonomic call sites** — `LuaArg` carries the SIP-66 `into` modifier, so `fn.call[Double](21.0, "label")` works without wrapping or language imports.
- **Tested against upstream** — the shared test suite (the portable subset of Luau's own conformance scripts — including an adapted port of upstream `pcall.luau`'s pcall/xpcall/yield semantics — plus task-library tests ported from [Lune](https://github.com/lune-org/lune) and Zune) runs on both backends.

## Example

```scala
import scala.concurrent.duration.*
import luau.panama.PanamaLuau   // JVM; use luau.wasm.WasmLuau on Scala.js

val result = PanamaLuau.withTasks() { world =>   // phase 1: live task world
  world.set("answer", 21.0)
  world.defineGlobal("hostAdd")((a: Double, b: Double) => a + b)

  world.spawn("""
    task.spawn(function(x)
      task.wait(0.05)                            -- parks the task, frees the thread
      sum = hostAdd(x, answer)
    end, 21)
  """)
} { st =>                                        // phase 2: world is quiescent
  st.eval[Double]("return sum").get              // 42.0
}

result.await(10.seconds)                         // Success(42.0)
```

The world runs until every task completes (or parks forever — those are cancelled at quiescence). A `deadline = Some(200.millis)` argument cancels all live tasks and fails the result if completions never arrive. One state is live per runtime at a time; sequential `withTasks` calls reuse the runtime, nested entry throws.

### Async host functions

A script can suspend on any host operation. The handler receives the decoded argument and a one-shot `Resume`; it returns a `Cancel` for teardown:

```scala
world.defineAsync[Double]("sleepMs") { ms => resume =>
  val timer = startTimer(ms.toLong) { () => resume.succeed(LuaValue.Number(ms)) }
  Cancel(() => timer.stop())
}
world.spawn("sleepMs(50); done = true")
```

Completion may fire on any thread — it only enqueues the task for the scheduler, never resumes inline.

### Values and handles

```scala
st.eval[Map[String, Double]]("return { alpha = 1, beta = 2 }")
st.eval2[Double, String]("return 1, 'two'")       // multi-result, strict
st.eval[Double]("return 1, 2")                    // Failure: 2 results, 1 consumed
st.eval[Double]("return function() end")          // Failure: not copyable

case class Point(x: Double, y: Double) derives LuauEncoder, LuauDecoder
st.set("origin", Point(0, 0))                     // crosses as a table, by copy

st.useRef {
  val fn = st.evalFn("return function(x) return x * 2 end").get
  fn.call[Double](21.0)                           // Success(42.0)

  st.run("config = { level = 3, onTick = function() end, 10, 20, 30 }")
  val tbl = st.getTbl("config").get               // pinned table handle
  tbl.get[Double]("level"); tbl.get[Double](3)    // fields + array elems
  tbl.getFn("onTick")                             // handles out of tables
  tbl.length; tbl.toSeq[Double]; tbl.toMap[Double]  // snapshot copies

  val co = st.coro(fn)                            // coroutine over fn
  co.resume[Double](1.0)                          // Yielded / Done steps
}                                                 // pins released (LIFO) here
```

Escapes are compile errors:

```scala
st.useRef { outerVar = Some(st.evalFn("...").get) }
// error: capability `s` cannot flow into capture set {}
```

## Architecture

| Layer | Package | Role |
|---|---|---|
| Facade | `luau.api` | User surface: `withTasks`, `TaskWorld` (setup plane), `LuaState` (value plane), `useRef`/handles (identity plane), `TaskResult` |
| Stdlib | `luau.stdlib` | Library opening + sandbox, Roblox `task.*` library |
| Scheduler | `luau.scheduler` | Task lifecycle, run queue, suspend wiring, timers |
| Core SPI | `luau.core` | `Binding[H]` backend contract, codecs, refs/scopes, async primitives |
| JVM backend | `luau.panama` | Panama downcalls into the native shim (`shim/src/lx.cpp`) |
| JS backend | `luau.wasm` | Same shim compiled to wasm, driven from Scala.js |
| C shim | `shim/` | Small `lx_*` C ABI over the vendored Luau VM (`shim/luau` submodule) |

The shim guarantees the native stack never unwinds across the FFI boundary: all Luau execution enters through a single resume entry point, errors come back as status codes, and host callbacks return a tri-state result (`Return`/`Fail`/`Suspend`) that the shim acts on in pure C. `core`, `scheduler`, and `stdlib` are shared sources — the `.jvm` and `.js` Mill modules compile the same files for each platform.

## Building

Requirements:

- JDK 21+ (tests fork with `--enable-preview` for `java.lang.foreign`)
- `clang++` (native shim)
- For the wasm shim: system clang/LLVM ≥ 21, `cmake`, `ninja` — Mill builds an exception-enabled WASI sysroot from `wasi-sdk` sources as a cached task (released wasi-sdk binaries ship `-fno-exceptions`, which Luau needs)
- Node.js (JS tests)

```bash
git clone --recurse-submodules git@github.com:HollandDM/luau-scala.git
cd luau-scala

# full test suite, both platforms (builds native + wasm shims on demand)
./mill-launcher.sh __.test
```

## License

The vendored [Luau](https://github.com/luau-lang/luau) submodule is MIT-licensed by Roblox Corporation.
