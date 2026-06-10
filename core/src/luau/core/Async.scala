package luau.core

opaque type Resume = Either[LuaError, LuaValue] => Unit

object Resume:
  def apply(f: Either[LuaError, LuaValue] => Unit): Resume = f
  extension (r: Resume)
    def complete(result: Either[LuaError, LuaValue]): Unit = r(result)
    def succeed(v: LuaValue): Unit = r(Right(v))
    def fail(e: LuaError): Unit    = r(Left(e))

opaque type Cancel = () => Unit

object Cancel:
  val noop: Cancel = () => ()
  def apply(f: () => Unit): Cancel = f
  extension (c: Cancel)
    def cancel(): Unit = c()
