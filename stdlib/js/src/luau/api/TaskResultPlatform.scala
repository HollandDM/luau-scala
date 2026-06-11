package luau.api

import scala.util.Try

private[api] object TaskResultPlatform:
  def cell[A](): TaskResultCell[A] = new TaskResultCell[A]:
    private var result: Option[Try[A]] = None
    private var callbacks: List[Try[A] => Unit] = Nil
    def poll: Option[Try[A]] = result
    def onComplete(f: Try[A] => Unit): Unit = result match
      case Some(r) => f(r)
      case None    => callbacks ::= f
    private[api] def complete(r: Try[A]): Unit =
      if result.isEmpty then
        result = Some(r)
        val cbs = callbacks.reverse
        callbacks = Nil
        cbs.foreach(cb => try cb(r) catch case _: Throwable => ())
