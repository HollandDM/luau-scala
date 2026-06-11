package luau.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Token → Suspend table backing Binding.takePendingSuspend. Tokens start at
  * 1 (0 = "none" in the shim's per-thread slot) and are process-unique.
  */
final class SuspendRegistry:
  private val seq = new AtomicLong(1L)
  private val table = new ConcurrentHashMap[Long, NativeFnResult.Suspend]()

  def allocToken(suspend: NativeFnResult.Suspend): Long =
    val tok = seq.getAndIncrement()
    table.put(tok, suspend)
    tok

  def consume(token: Long): Option[NativeFnResult.Suspend] =
    Option(table.remove(token))

  /** Drop all in-flight entries — closeState's purge on backends that keep
    * one registry per runtime (wasm).
    */
  def clear(): Unit = table.clear()

  def size: Int = table.size
