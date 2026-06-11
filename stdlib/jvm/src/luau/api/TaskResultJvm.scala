package luau.api

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

extension [A](r: TaskResult[A])
  def await(timeout: FiniteDuration): Try[A] = r match
    case c: JvmTaskResultCell[A] => c.awaitImpl(timeout)
