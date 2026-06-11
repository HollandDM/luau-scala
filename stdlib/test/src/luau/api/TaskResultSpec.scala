package luau.api

import munit.FunSuite
import scala.util.{Success, Try}

class TaskResultSpec extends FunSuite:

  test("poll is None until complete; complete is one-shot"):
    val cell = TaskResultPlatform.cell[Int]()
    assertEquals(cell.poll, None)
    cell.complete(Success(1))
    cell.complete(Success(2))
    assertEquals(cell.poll, Some(Success(1)))

  test("onComplete fires once, after completion or immediately"):
    val cell = TaskResultPlatform.cell[Int]()
    var seen = List.empty[Try[Int]]
    cell.onComplete(r => seen ::= r)
    cell.complete(Success(7))
    cell.onComplete(r => seen ::= r)
    assertEquals(seen, List(Success(7), Success(7)))
