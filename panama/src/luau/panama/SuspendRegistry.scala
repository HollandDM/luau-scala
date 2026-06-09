package luau.panama

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import luau.core.*
import luau.core.codec.*

final class SuspendRegistry:
  private val seq = new AtomicLong(1L)
  private val table = new ConcurrentHashMap[Long, NativeFnResult.Suspend]()

  def allocToken(suspend: NativeFnResult.Suspend): Long =
    val tok = seq.getAndIncrement()
    table.put(tok, suspend)
    tok

  def consume(token: Long): Option[NativeFnResult.Suspend] =
    Option(table.remove(token))
