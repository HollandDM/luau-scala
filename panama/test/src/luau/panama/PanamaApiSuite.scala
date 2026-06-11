package luau.panama

import java.lang.foreign.MemorySegment
import luau.api.ApiSuite
import luau.core.Binding

class PanamaApiSuite extends ApiSuite[MemorySegment]:

  override def withBinding[A](f: Binding[MemorySegment] => A): A =
    f(PanamaBinding.instance)
