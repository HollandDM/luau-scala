package luau.core

import language.experimental.captureChecking

/** Toolchain proof for capture checking (Scala 3.8, experimental): verifies
  * the per-file `captureChecking` import compiles and runs on both platforms,
  * and that the loan pattern rejects escaping capabilities at compile time.
  * Groundwork for the facade, where Lua handles (Fn/Tbl/Coro) become
  * capabilities that must not outlive their owning state/scope.
  *
  * Note: `caps.Capability` is sealed in 3.8 — tracked types need no marker
  * parent, the `^` in the parameter type is what makes the value a capability.
  */
class CaptureCheckingSmokeTest extends munit.FunSuite:

  final class Resource:
    private var closed = false
    def close(): Unit = closed = true
    def use(): Int =
      if closed then throw IllegalStateException("use after close")
      else 42

  def withResource[T](op: Resource^ => T): T =
    val r = Resource()
    try op(r)
    finally r.close()

  test("loan pattern with a capability compiles and runs") {
    val out = withResource { r => r.use() }
    assertEquals(out, 42)
  }

  // Escape rejection cannot be asserted via munit's compileErrors: capture
  // checking runs as a compiler phase after typer, which is all compileErrors
  // executes. Verified manually — this definition:
  //
  //   def leakAsPure(r: Resource^): () -> Int = () => r.use()
  //
  // fails compilation with:
  //
  //   Found:    () ->{r} Int
  //   Required: () -> Int
  //   Note that capability `r` cannot flow into capture set {}.
