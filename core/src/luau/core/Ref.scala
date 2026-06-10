package luau.core

final class Ref[H] private[core] (
  private[core] val state:    H,
  private[core] val registry: RefKey,
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

  /** The registry key for this Ref, used to push the value onto a stack. */
  def registryKey: RefKey = registry

object Ref:
  private[luau] def apply[H](
    state:    H,
    registry: RefKey,
    binding:  Binding[H],
    origin:   String,
  ): Ref[H] =
    new Ref[H](state, registry, binding, origin)

  def genOrigin(): String =
    val stack = Thread.currentThread().getStackTrace
    stack.lift(3) match
      case Some(e) => s"${e.getFileName}:${e.getLineNumber}"
      case None    => "unknown"
