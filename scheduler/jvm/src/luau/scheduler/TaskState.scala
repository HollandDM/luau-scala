package luau.scheduler

/** Lifecycle state of a Task owned by the Scheduler.
  *
  * Transitions (single-threaded Driver):
  *   Spawned → Running
  *   Running → Parked (lx_resume returned Yielded; async op registered)
  *   Running → Complete (lx_resume returned Ok)
  *   Running → Failed (lx_resume returned Error)
  *   Parked → Queued (completion fires: Resume posts ReadyTask)
  *   Queued → Running (Driver drains queue)
  *   Parked → Cancelled (teardown)
  *   Queued → Cancelled (teardown before Driver picks it up)
  */
enum TaskState:
  case Spawned
  case Queued
  case Running
  case Parked
  case Complete
  case Failed(error: String)
  case Cancelled
