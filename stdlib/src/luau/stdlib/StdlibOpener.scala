package luau.stdlib

import luau.core.{Binding, LuauLib}
import luau.scheduler.Scheduler

object StdlibOpener:
  def open[H](
    binding:   Binding[H],
    state:     H,
    scheduler: Scheduler[H],
    libs:      Set[LuauLib] = LuauLib.Standard,
  ): Unit =
    binding.openLibs(state, libs)
    TaskLibrary.install(binding, state, scheduler)
    binding.sandbox(state)
