package luau.panama

import munit.FunSuite
import scala.concurrent.duration.*
import scala.util.Success
import luau.api.*
import luau.core.{Cancel, LuaValue}

class WithTasksSuite extends FunSuite:

  test("WT-01 sync world: setup spawns nothing, finish reads"):
    val r = PanamaLuau.withTasks() { w => w.set("x", 21.0) } { st =>
      st.eval[Double]("return x * 2").get
    }
    assertEquals(r.await(10.seconds), Success(42.0))



  test("WT-03 task.wait round-trips through the timer seam"):
    val r = PanamaLuau.withTasks() { w =>
      w.spawn("local t = task.wait(0.05); waited = (t >= 0.04)").get
    } { st => st.get[Boolean]("waited").get }
    assertEquals(r.await(10.seconds), Success(true))

  test("WT-04 fail-fast: task error cancels the world, finish never runs"):
    var finishRan = false
    val r = PanamaLuau.withTasks() { w =>
      w.spawn("error('boom')").get
    } { _ => finishRan = true }
    assert(r.await(10.seconds).isFailure)
    assert(!finishRan)

  test("WT-05 deadline fails the result when a completion never arrives"):
    val r = PanamaLuau.withTasks(deadline = Some(200.millis)) { w =>
      w.defineAsync[Double]("never") { _ => _ => Cancel.noop }
      w.spawn("never(0)").get
    } { _ => () }
    val failure = r.await(10.seconds)
    assert(failure.isFailure)
    assert(failure.failed.get.getMessage.contains("deadline"))

  test("WT-06 second entry while live throws IllegalStateException"):
    val r = PanamaLuau.withTasks() { _ => () } { st =>
      intercept[IllegalStateException] {
        PanamaLuau.withTasks() { _ => () } { _ => () }
      }
      true
    }
    assertEquals(r.await(10.seconds), Success(true))

  test("WT-07 bare yield parks forever -> cancelled at quiescence, result completes"):
    val r = PanamaLuau.withTasks() { w =>
      w.spawn("""coroutine.yield(); unreached = "x"""").get
    } { st => st.eval[Double]("""return unreached or 0""").get }
    assertEquals(r.await(10.seconds), Success(0.0))

  test("WT-08 two sequential withTasks on one runtime"):
    val r1 = PanamaLuau.withTasks() { w => w.set("x", 1.0) } { st => st.get[Double]("x").get }
    assertEquals(r1.await(10.seconds), Success(1.0))
    val r2 = PanamaLuau.withTasks() { w => w.set("x", 2.0) } { st => st.get[Double]("x").get }
    assertEquals(r2.await(10.seconds), Success(2.0))

  test("WT-09 deadline fires cancel hook"):
    @volatile var cancelFired = false
    val r = PanamaLuau.withTasks(deadline = Some(100.millis)) { w =>
      w.defineAsync[Double]("never") { _ => _ =>
        Cancel(() => cancelFired = true)
      }
      w.spawn("never(0)").get
    } { _ => () }
    assert(r.await(10.seconds).isFailure)
    assert(cancelFired)

  // WT-10..13: the Lua-side task.* surface on a real backend — these pin the
  // nargs convention (args only, fn below them) and the threadRef ownership
  // handoff for synchronously-completing spawns.

  test("WT-10 task.spawn runs a sync-completing fn with args and returns the thread"):
    val r = PanamaLuau.withTasks() { w =>
      w.spawn(
        """local th = task.spawn(function(a, b) sum = a + b end, 20, 22)
          |spawned = type(th) == "thread"""".stripMargin).get
    } { st => (st.get[Double]("sum").get, st.get[Boolean]("spawned").get) }
    assertEquals(r.await(10.seconds), Success((42.0, true)))

  test("WT-11 task.spawn fn that waits resumes with its args intact"):
    val r = PanamaLuau.withTasks() { w =>
      w.spawn(
        """task.spawn(function(x)
          |  task.wait(0.01)
          |  resumed = x * 2
          |end, 21)""".stripMargin).get
    } { st => st.get[Double]("resumed").get }
    assertEquals(r.await(10.seconds), Success(42.0))

  test("WT-12 task.defer passes args"):
    val r = PanamaLuau.withTasks() { w =>
      w.spawn("task.defer(function(x) deferred = x * 2 end, 21)").get
    } { st => st.get[Double]("deferred").get }
    assertEquals(r.await(10.seconds), Success(42.0))

  test("WT-13 task.delay passes args through the timer"):
    val r = PanamaLuau.withTasks() { w =>
      w.spawn("task.delay(0.02, function(x) delayed = x + 1 end, 41)").get
    } { st => st.get[Double]("delayed").get }
    assertEquals(r.await(10.seconds), Success(42.0))


