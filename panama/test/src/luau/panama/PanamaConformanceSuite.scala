package luau.panama

import java.lang.foreign.MemorySegment
import java.nio.file.{Files, Paths}
import luau.core.Binding
import luau.core.conformance.ConformanceSuiteBase

class PanamaConformanceSuite extends ConformanceSuiteBase[MemorySegment]:

  private lazy val conformanceDir: String =
    sys.env.getOrElse(
      "LUAU_CONFORMANCE_DIR",
      fail("LUAU_CONFORMANCE_DIR not set (wired via panama.test.forkEnv in build.mill)"),
    )

  override def readTestFile(name: String): IArray[Byte] =
    IArray.unsafeFromArray(Files.readAllBytes(Paths.get(conformanceDir, name)))

  override def withBinding[A](f: Binding[MemorySegment] => A): A =
    PanamaState.use(f)

  override def conformanceSetup(b: Binding[MemorySegment], state: MemorySegment): Unit =
    LxHandles.lx_conformance_setup.invokeExact(state, 1): Unit
