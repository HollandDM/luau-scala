package luau.panama

import java.lang.foreign.MemorySegment
import java.nio.file.{Files, Paths}
import luau.api.{LuaState, PortedTaskSuiteBase, TaskResult, TaskWorld}

class PanamaPortedTaskSuite extends PortedTaskSuiteBase[MemorySegment]:

  private lazy val portedDir: String =
    sys.env.getOrElse(
      "LUAU_PORTED_DIR",
      fail("LUAU_PORTED_DIR not set (wired via panama.test.forkEnv in build.mill)"),
    )

  override protected def readPorted(name: String): String =
    new String(Files.readAllBytes(Paths.get(portedDir, name)), "UTF-8")

  override protected def withTasks[A](setup: TaskWorld[MemorySegment] => Unit)(
    finish: LuaState[MemorySegment] => A
  ): TaskResult[A] =
    PanamaLuau.withTasks()(setup)(finish)
