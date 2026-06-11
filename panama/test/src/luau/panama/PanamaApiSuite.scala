package luau.panama

import java.lang.foreign.MemorySegment
import scala.concurrent.duration.*
import luau.api.*
import luau.core.LuauLib

class PanamaApiSuite extends ApiSuite[MemorySegment]:

  override protected def withLuau[A](libs: Set[LuauLib])(f: LuaState[MemorySegment] => A): A =
    Tasks.withTasks(PanamaBinding.instance, libs)(_ => ())(f).await(30.seconds).get
