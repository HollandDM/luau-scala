package luau.api

import language.experimental.captureChecking

import scala.concurrent.duration.FiniteDuration
import luau.core.{Binding, LuauLib}
import luau.scheduler.ErrorPolicy

object Tasks:

  def withTasks[H, A](
    binding:     Binding[H],
    libs:        Set[LuauLib] = LuauLib.Standard,
    deadline:    Option[FiniteDuration] = None,
    errorPolicy: ErrorPolicy = ErrorPolicy.failFast,
  )(setup: TaskWorld[H] => Unit)(finish: LuaState[H] => A): TaskResult[A] =
    binding.reserveStateSlot()
    val result = TaskResultPlatform.cell[A]()
    new Driver[H, A](binding, libs, deadline, errorPolicy, setup, finish, result).start()
    result
