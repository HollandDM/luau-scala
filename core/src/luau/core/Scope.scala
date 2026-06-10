package luau.core

import scala.collection.mutable

class Scope[H](
  private val binding: Binding[H],
  private val state:   H,
) extends AutoCloseable:

  private val owned: mutable.ArrayDeque[Ref[H]] = mutable.ArrayDeque.empty

  def captureTop(): Ref[H] =
    val r = binding.ref(state)
    owned.addOne(r)
    r

  def own(r: Ref[H]): r.type =
    owned.addOne(r)
    r

  override def close(): Unit =
    while owned.nonEmpty do
      owned.removeLast().close()
