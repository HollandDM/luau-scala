package luau.wasm

import language.experimental.captureChecking

import luau.api.{Luau, LuaState}
import luau.core.LuauLib

/** JS entry point: a fresh wasm-backed Luau VM per `withState` call. */
object WasmLuau:

  def withState[A](
    libs: Set[LuauLib] = LuauLib.Standard
  )(f: LuaState[Int]^ => A): A =
    WasmBackend.load()
    Luau.withState(WasmBackend.createBinding(), libs)(f)
