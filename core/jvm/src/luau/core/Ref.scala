package luau.core

final class Ref[H] private[core] (
  private[core] val state:    H,
  private[core] val registry: Int,
  private[core] val binding:  Binding[H],
  private[core] val origin:   String,
) extends AutoCloseable:

  @volatile private var closed = false

  def push(): Unit =
    require(!closed, "Ref.push() on a closed Ref")
    binding.pushRef(state, registry)

  override def close(): Unit =
    if !closed then
      closed = true
      binding.unref(state, registry)

  def isClosed: Boolean = closed
