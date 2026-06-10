package luau.api

/** Compile-time negatives for the facade, via munit's compileErrors (a macro
  * over scala.compiletime.testing.typeCheckErrors).
  *
  * compileErrors runs parse + typer only. That catches the missing-RefScope
  * misuse (a `using` resolution failure is a typer error) but NOT capability
  * escapes — the cc phase (cc.Setup / cc.CheckCaptures) runs after typer, so
  * escape snippets typecheck clean here and are rejected only in real
  * compilation. The last test pins that gap so a future compiler that moves
  * cc earlier (or a munit that learns about it) shows up as a test failure.
  * Recorded real-compilation rejections live in the ApiSuite doc comment.
  */
class CcCompileSpec extends munit.FunSuite:

  test("minting a handle without a RefScope does not compile"):
    val errors = compileErrors(
      """def f(st: luau.api.LuaState[Int]) = st.evalFn("return 1")"""
    )
    assert(errors.contains("No given instance"), errors)

  test("creating a coroutine without a RefScope does not compile"):
    val errors = compileErrors(
      """def f(st: luau.api.LuaState[Int], fn: luau.api.LuaFn[Int]) = st.coro(fn)"""
    )
    assert(errors.contains("No given instance"), errors)

  test("handle escape is NOT a typer error — cc rejects it after typer"):
    val errors = compileErrors(
      """def leak[H](st: luau.api.LuaState[H]): Option[luau.api.LuaFn[H]] =
  var captured: Option[luau.api.LuaFn[H]] = None
  st.useRef:
    captured = Some(st.evalFn("return 1").get)
  captured"""
    )
    assertEquals(errors, "")
