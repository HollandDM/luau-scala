package luau.stdlib

import luau.core.Binding
import luau.scheduler.Scheduler

object StdlibMask:
  val Base:      Int = 1 << 0
  val Math:      Int = 1 << 1
  val String:    Int = 1 << 2
  val Table:     Int = 1 << 3
  val Bit32:     Int = 1 << 4
  val Utf8:      Int = 1 << 5
  val Os:        Int = 1 << 6
  val Coroutine: Int = 1 << 7
  val Vector:    Int = 1 << 8
  val Buffer:    Int = 1 << 9
  val Debug:     Int = 1 << 10
  val Standard:  Int = Base | Math | String | Table | Bit32 | Utf8 | Os | Coroutine | Vector | Buffer

object StdlibOpener:
  def open[H](
    binding:   Binding[H],
    state:     H,
    scheduler: Scheduler[H],
    mask:      Int = StdlibMask.Standard,
  ): Unit =
    binding.openLibs(state, mask)
    TaskLibrary.install(binding, state, scheduler)
    binding.sandbox(state)
