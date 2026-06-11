package luau.api

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Try}
import luau.core.LuaError

private[api] final class JvmTaskResultCell[A] extends TaskResultCell[A]:
  private val latch = new CountDownLatch(1)
  @volatile private var result: Option[Try[A]] = None
  private var callbacks: List[Try[A] => Unit] = Nil

  def poll: Option[Try[A]] = result

  def onComplete(f: Try[A] => Unit): Unit =
    val now = synchronized {
      result match
        case some @ Some(_) => some
        case None           => callbacks ::= f; None
    }
    now.foreach(f)

  private[api] def complete(r: Try[A]): Unit =
    val cbs = synchronized {
      if result.isDefined then Nil
      else
        result = Some(r)
        val c = callbacks.reverse
        callbacks = Nil
        c
    }
    cbs.foreach(cb => try cb(r) catch case _: Throwable => ())
    latch.countDown()

  private[api] def awaitImpl(timeout: FiniteDuration): Try[A] =
    if latch.await(timeout.toMillis, TimeUnit.MILLISECONDS) then result.get
    else Failure(LuaError.runtime(s"TaskResult.await timed out after $timeout"))

private[api] object TaskResultPlatform:
  def cell[A](): TaskResultCell[A] = new JvmTaskResultCell[A]
