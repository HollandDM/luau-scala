package luau.panama

import language.experimental.captureChecking

import java.lang.foreign.MemorySegment
import luau.api.{Luau, LuaState}
import luau.core.LuauLib

/** JVM entry point: a fresh native Luau VM per `withState` call. */
object PanamaLuau:

  def withState[A](
    libs: Set[LuauLib] = LuauLib.Standard
  )(f: LuaState[MemorySegment]^ => A): A =
    PanamaState.use(ps => Luau.withState(ps, libs)(f))
