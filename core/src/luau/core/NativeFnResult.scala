package luau.core

enum NativeFnResult:
  case Return(nResults: Int)
  /** The fn pushed one error value on the calling thread's stack; the
    * dispatcher raises it as a Lua error. Carries no payload — earlier
    * versions took a LuaValue that every dispatcher ignored.
    */
  case Fail
  case Suspend(register: Resume => Cancel)
