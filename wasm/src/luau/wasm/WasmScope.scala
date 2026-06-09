package luau.wasm

import luau.core.Scope

final class WasmScope(binding: WasmBinding, L: Int) extends Scope[Int](binding, L)
