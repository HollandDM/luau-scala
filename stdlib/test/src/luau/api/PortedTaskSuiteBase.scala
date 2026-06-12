package luau.api

import munit.FunSuite
import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}
import luau.core.{LuaError, LuaValue, NativeFnResult}
import luau.scheduler.TaskHandle

/** Runs task-scheduler test scripts ported from other Luau runtimes against
  * a real backend via the withTasks entry.
  *
  * Sources (adapted copies live in stdlib/test/resources/ported/; each file
  * header documents its upstream origin and every edit):
  *   - lune/  — lune-org/lune tests/task and tests/globals
  *   - zune/  — Scythe-Technology/Zune test/standard/task.test.luau
  *
  * Protocol: every ported file ends with `return "OK"` as its last executed
  * top-level statement. The world drains to quiescence (delayed/deferred
  * assertions included — a failure in any of them fails the world before
  * finish runs), then finish reads the chunk's return values off its
  * [[TaskHandle.results]].
  *
  * Tests are async (Future-based): on JS the world completes through the
  * event loop and blocking is impossible. Platform subclasses supply file IO
  * and the backend entry (PanamaLuau / WasmLuau).
  */
abstract class PortedTaskSuiteBase[H] extends FunSuite:

  /** Read a ported script's source (LUAU_PORTED_DIR/<name>). Platform IO. */
  protected def readPorted(name: String): String

  /** The backend's withTasks entry. */
  protected def withTasks[A](setup: TaskWorld[H] => Unit)(
    finish: LuaState[H] => A
  ): TaskResult[A]

  override def munitTimeout: Duration = 120.seconds

  /** Upstream conformance fixture: `resumeerror(co, msg)` resumes `co` with
    * `msg` raised as an error at its yield point (the pcall inside `co`
    * catches it). Mirrors the C harness's lua_resumeerror test global.
    */
  private def installResumeError(w: TaskWorld[H]): Unit =
    val binding = w.st.binding
    w.st.installNative("resumeerror", (s, nargs) =>
      binding.toThreadAt(s, 1) match
        case Some(co) =>
          val msg = binding.toStringAt(s, 2).getOrElse("")
          binding.resumeError(co, LuaError.runtime(msg))
          NativeFnResult.Return(0)
        case None =>
          binding.pushString(s, "resumeerror: expected thread as first argument")
          NativeFnResult.Fail)

  for name <- PortedTaskSuiteBase.files do
    test(s"ported: $name"):
      val source = readPorted(name)
      var handle: Option[TaskHandle[H]] = None
      val r = withTasks { w =>
        installResumeError(w)
        handle = Some(w.spawn(source, "=" + name).get)
      } { _ =>
        handle.get.results
      }
      val p = Promise[Option[Seq[LuaValue]]]()
      r.onComplete(p.complete)
      p.future.map {
        case Some(Seq(LuaValue.LuaString(bytes))) =>
          assertEquals(String(bytes.unsafeArray, "UTF-8"), "OK")
        case other =>
          fail(s"expected the chunk to return \"OK\", got $other")
      }(using munitExecutionContext)

object PortedTaskSuiteBase:
  val files: Seq[String] = Seq(
    "lune/cancel.luau",
    "lune/spawn.luau",
    "lune/defer.luau",
    "lune/delay.luau",
    "lune/wait.luau",
    "lune/error.luau",
    "lune/type.luau",
    "lune/warn.luau",
    "lune/typeof.luau",
    "lune/coroutine.luau",
    "zune/task.luau",
    "conformance/pcall.luau",
    "cobalt/bit32.luau",
    "cobalt/math.luau",
    "cobalt/coroutine.luau",
    "cobalt/vm.luau",
    "cobalt/utf8.luau",
    "cobalt/base.luau",
    "cobalt/vararg.luau",
    "cobalt/operation.luau",
    "cobalt/string.luau",
    "cobalt/table.luau",
  )
