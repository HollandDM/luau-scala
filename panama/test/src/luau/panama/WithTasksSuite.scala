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


