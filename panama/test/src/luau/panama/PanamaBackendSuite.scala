package luau.panama

import java.lang.foreign.MemorySegment
import luau.core.{Binding, SharedBackendSuite}

class PanamaBackendSuite extends SharedBackendSuite[MemorySegment]:

  override def withBinding[A](f: Binding[MemorySegment] => A): A =
    f(PanamaBinding.instance)
