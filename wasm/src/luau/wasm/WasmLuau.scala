package luau.wasm

import language.experimental.captureChecking

import scala.concurrent.duration.FiniteDuration
import luau.api.{LuaState, TaskResult, Tasks, TaskWorld}
import luau.core.LuauLib

object WasmLuau:

  def withTasks[A](
    libs:     Set[LuauLib] = LuauLib.Standard,
    deadline: Option[FiniteDuration] = None,
  )(setup: TaskWorld[Int]^ => Unit)(finish: LuaState[Int]^ => A): TaskResult[A] =
    Tasks.withTasks(WasmBackend.createBinding(), libs, deadline)(setup)(finish)
