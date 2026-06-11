package luau.stdlib

import luau.core.{Binding, LuaType, LuauLib, NativeFnResult}
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
    registerWarn(binding, state)
    binding.sandbox(state)

  /** Roblox/Lune `warn(...)`: render every argument, tab-separated, to
    * stderr. Arguments are read with non-raising accessors only.
    */
  private def registerWarn[H](binding: Binding[H], state: H): Unit =
    binding.registerNativeFn(state, (thread, nargs) =>
      val parts = (1 to nargs).map { i =>
        binding.typeAt(thread, i) match
          case LuaType.Nil     => "nil"
          case LuaType.Boolean => binding.toBoolean(thread, i).toString
          case LuaType.Number  =>
            val d = binding.toNumber(thread, i).getOrElse(Double.NaN)
            if d.isWhole && !d.isInfinite then d.toLong.toString else d.toString
          case LuaType.String  => binding.toStringAt(thread, i).getOrElse("")
          case t               => t.toString.toLowerCase
      }
      Console.err.println(parts.mkString("\t"))
      NativeFnResult.Return(0)
    )
    binding.setGlobal(state, "warn")
