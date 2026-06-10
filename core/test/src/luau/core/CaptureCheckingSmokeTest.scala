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

  // ---- useRef prototype -------------------------------------------------
  // The facade's ref-scope mechanism: handles are minted only against a
  // scope capability (context parameter) and their type captures it
  // (`HandleProto^{s}`), so a handle cannot outlive the useScope block that
  // owns its registry pin.

  final class ScopeProto:
    private val pins = scala.collection.mutable.ArrayDeque.empty[Int]
    private var open = true
    def pin(key: Int): Unit = { require(open); pins.addOne(key) }
    def drain(): List[Int] =
      open = false
      List.unfold(pins)(p => if p.isEmpty then None else Some((p.removeLast(), p)))

  final class HandleProto private[CaptureCheckingSmokeTest] (val key: Int)

  def useScope[A](f: (s: ScopeProto^) ?=> A): (A, List[Int]) =
    val s = ScopeProto()
    val a = f(using s)
    (a, s.drain()) // facade unrefs each drained key here

  def mintHandle(key: Int)(using s: ScopeProto^): HandleProto^{s} =
    s.pin(key)
    HandleProto(key)

  test("useRef prototype: handles usable inside scope, pins drain LIFO") {
    val (sum, freed) = useScope { s ?=>
      val h1 = mintHandle(4)
      val h2 = mintHandle(7)
      h1.key + h2.key
    }
    assertEquals(sum, 11)
    assertEquals(freed, List(7, 4))
  }

  // Escape verified rejected manually (same compileErrors limitation):
  //
  //   var leaked: HandleProto | Null = null
  //   useScope { s ?=> leaked = mintHandle(4); 0 }
  //
  // fails compilation: the handle's capture set {s} cannot flow into the
  // capture set of the enclosing scope's `leaked`.
