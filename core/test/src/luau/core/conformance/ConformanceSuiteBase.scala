package luau.core.conformance

import munit.FunSuite
import luau.core.*
import scala.concurrent.duration.Duration

/** Runs the portable subset of the upstream Luau conformance scripts against
  * a binding backend, replicating the upstream runner's protocol
  * (shim/luau/tests/Conformance.test.cpp):
  *
  *   openLibs(all) → prelude chunk → lx_conformance_setup →
  *   compileAndLoad("=" + name) → resume until Returned → top of stack == "OK"
  *
  * Platform subclasses supply file IO, the binding, and the conformance-setup
  * call; everything else is shared.
  */
abstract class ConformanceSuiteBase[H] extends FunSuite:

  /** Read a conformance script's bytes. Platform-specific file IO. */
  def readTestFile(name: String): IArray[Byte]

  /** Run f against a fresh Binding (fresh runtime per test). */
  def withBinding[A](f: Binding[H] => A): A

  /** Invoke the Shim's lx_conformance_setup on this state. */
  def conformanceSetup(b: Binding[H], state: H): Unit

  /** Files expected to pass on this platform. Override to skip per-platform. */
  def files: Seq[String] = ConformanceManifest.portable

  override def munitTimeout: Duration = Duration(180, "s")

  private def utf8(bytes: IArray[Byte]): String =
    new String(IArray.genericWrapArray(bytes).toArray, "UTF-8")

  private def runChunk(b: Binding[H], state: H, source: IArray[Byte], chunkname: String): ResumeResult =
    b.compileAndLoad(state, source, chunkname)
      .fold(e => fail(s"$chunkname failed to compile: ${e.message}"), identity)
    var result = b.resume(state, 0)
    var resumes = 0
    // Some scripts yield to the harness at top level (e.g. ndebug_upvalues);
    // the upstream runner resumes until completion. Guard against livelock.
    while result.isInstanceOf[ResumeResult.Yielded] && resumes < 10_000 do
      result = b.resume(state, 0)
      resumes += 1
    result

  for name <- files do
    test(s"conformance: $name"):
      withBinding { b =>
        val state = b.newState()
        try
          b.openLibs(state, ConformanceManifest.fullLibsMask)

          val preludeBytes = IArray.unsafeFromArray(
            ConformanceManifest.luaPrelude.getBytes("UTF-8"))
          runChunk(b, state, preludeBytes, "=prelude") match
            case ResumeResult.Returned(_) => ()
            case other => fail(s"prelude did not complete: $other")

          conformanceSetup(b, state)

          runChunk(b, state, readTestFile(name), "=" + name) match
            case ResumeResult.Returned(n) =>
              assert(n >= 1, s"expected a return value, got $n results")
              val top = b.toBytes(state, -1).map(utf8)
              assertEquals(top, Some("OK"), s"script returned ${top.orNull} instead of OK")
            case ResumeResult.Error(e) =>
              fail(s"runtime error: ${e.message}")
            case other =>
              fail(s"unexpected resume result: $other")
        finally b.closeState(state)
      }
