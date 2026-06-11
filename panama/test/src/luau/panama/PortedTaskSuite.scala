package luau.panama

import munit.FunSuite
import scala.concurrent.duration.*
import scala.util.Success
import luau.api.*

/** Runs task-scheduler test scripts ported from other Luau runtimes against
  * the real panama backend via the withTasks entry.
  *
  * Sources (adapted copies live in panama/test/resources/ported/; each file
  * header documents its upstream origin and every edit):
  *   - lune/  — lune-org/lune tests/task and tests/globals
  *   - zune/  — Scythe-Technology/Zune test/standard/task.test.luau
  *
  * Protocol: the chunk's return values are not observable through spawn, so
  * every ported file ends with `__result = "OK"` as its last executed
  * top-level statement. The world drains to quiescence (delayed/deferred
  * assertions included — a failure in any of them fails the world before
  * finish runs), then finish reads the sentinel back.
  */
class PortedTaskSuite extends FunSuite:

  override def munitTimeout: Duration = 120.seconds

  private def readResource(path: String): String =
    val stream = getClass.getResourceAsStream("/" + path)
    assert(stream != null, s"resource not on test classpath: $path")
    try new String(stream.readAllBytes(), "UTF-8")
    finally stream.close()

  private val ported: Seq[String] = Seq(
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

  for name <- ported do
    test(s"ported: $name"):
      val source = readResource(s"ported/$name")
      val r = PanamaLuau.withTasks() { w =>
        w.spawn(source, "=" + name).get
      } { st =>
        st.get[String]("__result").get
      }
      assertEquals(r.await(60.seconds), Success("OK"))
