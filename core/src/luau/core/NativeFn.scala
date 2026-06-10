package luau.core

type NativeFn[H] = (state: H, nargs: Int) => NativeFnResult
