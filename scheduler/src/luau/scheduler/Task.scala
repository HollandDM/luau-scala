package luau.scheduler

import luau.core.{Ref, Cancel, LuaValue}

/** A Luau thread the Scheduler owns, plus scheduling state.
  *
  * @param threadRef Registry Ref pinning the lua_newthread coroutine.
  *                  Closed when Task reaches a terminal state.
  * @param thread    Raw thread handle passed to binding.resume().
  * @param id        Unique monotonic ID for logging/debugging.
  */
final class Task[H](
  val threadRef: Ref[H],
  val thread: H,
  val id: Long,
):

  @volatile private var _state: TaskState = TaskState.Spawned

  def state: TaskState = _state

  private[scheduler] def setState(s: TaskState): Unit =
    _state = s

  private val _cancel: java.util.concurrent.atomic.AtomicReference[Cancel | Null] =
    java.util.concurrent.atomic.AtomicReference(null)

  private[scheduler] def installCancel(c: Cancel): Unit =
    _cancel.set(c)

  private[scheduler] def clearCancel(): Cancel | Null =
    _cancel.getAndSet(null)

  private[scheduler] def fireCancel(): Unit =
    val c = _cancel.getAndSet(null)
    if c != null then c.asInstanceOf[Cancel].cancel()

  private[scheduler] def releaseThread(): Unit =
    threadRef.close()

  @volatile private var _pendingCompletion = false
  def pendingCompletion: Boolean = _pendingCompletion
  private[scheduler] def setPendingCompletion(b: Boolean): Unit = _pendingCompletion = b

  /** Return values captured when the task ran to completion; `None` for any
    * non-Complete state. Written before the Complete state transition, so a
    * reader that observes Complete observes the values.
    */
  @volatile private var _results: Option[Seq[LuaValue]] = None
  def results: Option[Seq[LuaValue]] = _results
  private[scheduler] def setResults(vs: Seq[LuaValue]): Unit = _results = Some(vs)
