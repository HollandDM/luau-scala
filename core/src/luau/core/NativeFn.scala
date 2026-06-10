package luau.core

// Capture checking makes `=>` here mean an impure function (it may capture
// capabilities). Without it, capture-checked callers (luau.api) could not
// pass closures over host state as NativeFn.
import language.experimental.captureChecking

type NativeFn[H] = (state: H, nargs: Int) => NativeFnResult
