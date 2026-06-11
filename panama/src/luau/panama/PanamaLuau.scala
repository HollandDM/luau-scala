package luau.panama

import language.experimental.captureChecking

import java.lang.foreign.MemorySegment
import scala.concurrent.duration.FiniteDuration
import luau.api.{Luau, LuaState, TaskResult, Tasks, TaskWorld}
import luau.core.LuauLib

object PanamaLuau:

  def withState[A](
    libs: Set[LuauLib] = LuauLib.Standard
  )(f: LuaState[MemorySegment]^ => A): A =
    luau.api.Luau.withState(PanamaBinding.instance, libs)(f)

  def withTasks[A](
    libs:     Set[LuauLib] = LuauLib.Standard,
    deadline: Option[FiniteDuration] = None,
  )(setup: TaskWorld[MemorySegment]^ => Unit)(
    finish: LuaState[MemorySegment]^ => A
  ): TaskResult[A] =
    Tasks.withTasks(PanamaBinding.instance, libs, deadline)(setup)(finish)
