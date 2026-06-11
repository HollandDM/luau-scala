package luau.api

import scala.util.Try

trait TaskResult[A]:
  def poll: Option[Try[A]]
  def onComplete(f: Try[A] => Unit): Unit

private[api] trait TaskResultCell[A] extends TaskResult[A]:
  private[api] def complete(r: Try[A]): Unit
