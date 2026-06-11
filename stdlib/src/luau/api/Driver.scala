package luau.api

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Try}
import luau.core.{Binding, Cancel, LuaError, LuauLib}
import luau.scheduler.{ErrorPolicy, Scheduler, TaskTimer}
import luau.stdlib.StdlibOpener

private[api] final class Driver[H, A](
  binding:     Binding[H],
  libs:        Set[LuauLib],
  deadline:    Option[FiniteDuration],
  errorPolicy: ErrorPolicy,
  setup:       TaskWorld[H] => Unit,
  finish:      LuaState[H] => A,
  result:      TaskResultCell[A],
):
  private val timer = TaskTimer.create()
  private val pump  = Pump.start(() => drain())

  @volatile private var deadlineHit = false
  @volatile private var firstError: Option[LuaError] = None

  private var stateOpt: Option[H] = None
  private var scheduler: Scheduler[H] = null
  private var done = false
  private var deadlineCancel: Cancel = Cancel.noop

  def start(): Unit =
    deadline.foreach { d =>
      deadlineCancel = timer.schedule(d.toMillis / 1000.0) { () =>
        deadlineHit = true
        pump.wake()
      }
    }
    pump.wake()

  private def drain(): Unit =
    try
      if done then return
      if stateOpt.isEmpty then init()
      var more = true
      while more && !done do
        if deadlineHit then
          failWorld(LuaError.runtime("withTasks: deadline exceeded"))
        else if firstError.isDefined then failWorld(firstError.get)
        else more = scheduler.runOneReady()
      if !done && firstError.isDefined then failWorld(firstError.get)
      if !done && deadlineHit then failWorld(LuaError.runtime("withTasks: deadline exceeded"))
      if !done && scheduler.isQuiescent then
        scheduler.cancelAbandoned()
        finishWith(Try(finish(LuaState(binding, stateOpt.get))))
    catch case t: Throwable =>
      if !done then finishWith(Failure(t))

  private def init(): Unit =
    val state = binding.newState()
    stateOpt = Some(state)
    val policy: ErrorPolicy =
      if errorPolicy eq ErrorPolicy.failFast then
        (task, err) => { if firstError.isEmpty then firstError = Some(err) }
      else errorPolicy
    scheduler = Scheduler(binding, state, timer, () => pump.wake(), policy)
    StdlibOpener.open(binding, state, scheduler, libs)
    setup(TaskWorld(LuaState(binding, state), scheduler))

  private def failWorld(err: LuaError): Unit =
    if scheduler != null then scheduler.cancelAll()
    finishWith(Failure(err))

  private def finishWith(r: Try[A]): Unit =
    done = true
    deadlineCancel.cancel()
    if scheduler != null then scheduler.close()
    stateOpt match
      case Some(s) => binding.closeState(s)
      case None    => binding.releaseStateSlot()
    timer.shutdown()
    result.complete(r)
    pump.shutdown()
