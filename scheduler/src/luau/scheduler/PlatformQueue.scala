package luau.scheduler

import scala.collection.mutable.ArrayDeque

/** Cross-platform concurrent queue for ReadyTask items.
  * synchronized guards for thread-safe enqueue from off-Driver completions.
  * On JS, synchronized is a no-op (single-threaded).
  */
final class PlatformQueue[A]:
  private val q = ArrayDeque[A]()

  def enqueue(item: A): Unit = synchronized { q.addOne(item) }

  def dequeueOption(): Option[A] = synchronized {
    if q.isEmpty then None else Some(q.removeHead())
  }

  def isEmpty: Boolean = synchronized { q.isEmpty }
