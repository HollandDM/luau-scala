# Embed upstream Luau via a slim C++ Shim, bound by Panama (JVM) and WASM (JS)

We need one Luau that behaves identically on the JVM and in JS. Rather than reimplement the lexer/compiler/VM in Scala (rejected: multi-quarter effort, two semantics to keep in sync — see the research docs under `docs/research/`), we embed the upstream Roblox Luau engine **as-is** and reach it from Scala 3 through a slim C++ **Shim**. The Shim exposes one narrow, task-shaped ABI (`lx_*`) and is compiled from a single source to a native library (consumed via the **Panama backend**, `java.lang.foreign`) and to WASM (consumed via the **WASM backend**, Scala.js interop). Scala `core` maps 1:1 onto the Shim symbols and never touches stock `lua.h` directly.

## Considered Options

- **Reimplement Luau in Scala, cross-compiled** — rejected: enormous scope, and we'd own Luau semantics forever.
- **Stock Lua C API bound directly, no C layer** — rejected: leaks a chatty API to both backends, forces a large WASM export list, and can't safely turn a Scala-callback error into a Lua raise without `longjmp`-ing through a JVM/JS frame.
- **Coarse RPC shim (marshal args/results in C)** — rejected for now: fewer crossings but fat C and inflexible; batching can be added selectively later behind the same facade.

## Consequences

- **No protected calls across the FFI boundary.** Stock Luau raises errors with `lua_error`, which `longjmp`s up the C stack; if that crosses a Panama downcall or a WASM↔host frame it is undefined behavior. Therefore all Luau execution enters only through the **Resume boundary** (`lua_resume`), which converts errors into a status instead of unwinding. The Host touches the stack only with non-raising accessors (`lua_type`, `lua_to*x`, `lua_raw*`).
- **A Scala callback cannot raise.** It returns an error result to the Shim; the Shim calls `lua_error` itself in pure C, after control is back from the upcall. This is the sole reason the Shim is not zero-C.
- **One symbol set, one place to export, one place to audit safety.** WASM export list and longjmp-safety live in a single file.
- Upgrading Luau = rebuild the Shim against the new headers; no Scala changes if the ABI is stable.
