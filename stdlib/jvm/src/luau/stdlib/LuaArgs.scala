package luau.stdlib

import luau.core.*

final class LuaArgs[H](
  val binding: Binding[H],
  val state: H,
  val nargs: Int,
):

  def readNumber(pos: Int): Option[Double] =
    binding.toNumber(state, pos)

  def readFunction(pos: Int): Either[LuaError, Ref[H]] =
    binding.typeAt(state, pos) match
      case LuaType.Function =>
        binding.pushCopy(state, pos)
        Right(binding.ref(state))
      case _ =>
        Left(LuaError.runtime("expected function, got " + binding.typeAt(state, pos)))

  def readThread(pos: Int): Option[Ref[H]] =
    binding.typeAt(state, pos) match
      case LuaType.Thread =>
        binding.pushCopy(state, pos)
        Some(binding.ref(state))
      case _ => None

  def pushRefValue(thread: H, ref: Ref[H]): Unit =
    binding.pushRef(thread, ref.registryKey)
