package luau.wasm

import luau.api.{ApiSuite, LuaState, Tasks, TaskWorld}
import luau.core.LuauLib

class WasmApiSuite extends ApiSuite[Int]:

  override protected def withLuau[A](libs: Set[LuauLib])(f: LuaState[Int] => A): A =
    WasmLuau.withTasks(libs)(_ => ())(f)
      .poll
      .getOrElse(throw new AssertionError("withTasks did not complete synchronously"))
      .get
