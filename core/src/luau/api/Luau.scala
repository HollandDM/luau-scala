package luau.api

import language.experimental.captureChecking

import luau.core.{Binding, LuauLib}

object Luau:

  /** Loan a fresh VM: open `libs`, sandbox, run `f`, close.
    *
    * The `LuaState[H]^` is a capability — it lives only inside `f`; letting
    * it (or anything capturing it) escape the block is a compile error, so
    * no facade call can ever reach a closed VM.
    *
    * States are always sandboxed: stdlib globals are frozen, and the main
    * thread gets a writable proxy environment (luaL_sandboxthread), so
    * `setGlobal` / `defineGlobal` and script-defined globals keep working.
    */
  def withState[H, A](
    binding: Binding[H],
    libs:    Set[LuauLib] = LuauLib.Standard,
  )(f: LuaState[H]^ => A): A =
    val state = binding.newState()
    try
      binding.openLibs(state, libs)
      binding.sandbox(state)
      f(LuaState(binding, state))
    finally binding.closeState(state)
