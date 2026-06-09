package luau.core

enum ResumeResult:
  case Returned(nresults: Int)
  case Yielded(nresults: Int)
  case Error(error: LuaError)
