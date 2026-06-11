package luau.api

import munit.FunSuite
import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}

/** Runs task-scheduler test scripts ported from other Luau runtimes against
  * a real backend via the withTasks entry.
  *
  * Sources (adapted copies live in stdlib/test/resources/ported/; each file
  * header documents its upstream origin and every edit):
  *   - lune/  — lune-org/lune tests/task and tests/globals
  *   - zune/  — Scythe-Technology/Zune test/standard/task.test.luau
  *
  * Protocol: the chunk's return values are not observable through spawn, so
  * every ported file ends with `__result = "OK"` as its last executed
  * top-level statement. The world drains to quiescence (delayed/deferred
  * assertions included — a failure in any of them fails the world before
  * finish runs), then finish reads the sentinel back.
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

  for name <- PortedTaskSuiteBase.files do
    test(s"ported: $name"):
      val source = readPorted(name)
      val r = withTasks { w =>
        w.spawn(source, "=" + name).get
      } { st =>
        st.get[String]("__result").get
      }
      val p = Promise[String]()
      r.onComplete(p.complete)
      p.future.map(v => assertEquals(v, "OK"))(using munitExecutionContext)

object PortedTaskSuiteBase:
  val files: Seq[String] = Seq(
    "lune/cancel.luau",
    "lune/spawn.luau",
    "lune/defer.luau",
    "lune/delay.luau",
    "lune/wait.luau",
    "lune/error.luau",
    "lune/type.luau",
    "lune/typeof.luau",
    "lune/coroutine.luau",
    "zune/task.luau",
  )
