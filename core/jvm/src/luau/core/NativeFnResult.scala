package luau.core

enum NativeFnResult:
  case Return(nResults: Int)
  case Fail(value: LuaValue)
  case Suspend(register: Resume => Cancel)
