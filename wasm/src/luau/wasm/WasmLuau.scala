package luau.wasm

import language.experimental.captureChecking

import scala.concurrent.duration.FiniteDuration
import luau.api.{Luau, LuaState, TaskResult, Tasks, TaskWorld}
import luau.core.LuauLib

object WasmLua:

  def withState[A](
    libs: Set[LuauLib] = LuauLib.Standard
  )(f: LuaState[Int]^ => A): A =
    Luau.withState(WasmBackend.createBinding(), libs)(f)

  def withTasks[A](
    libs:     Set[LuauLib] = LuauLib.Standard,
    deadline: Option[FiniteDuration] = None,
  )(setup: TaskWorld[Int]^ => Unit)(finish: LuaState[Int]^ => A): TaskResult[A] =
    Tasks.withTasks(WasmBackend.createBinding(), libs, deadline)(setup)(finish)
