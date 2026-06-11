package luau.stdlib

import luau.core.*
import luau.core.NativeFnResult.{Return, Fail, Suspend}
import luau.scheduler.{Scheduler, TaskHandle}

object TaskLibrary:

  def install[H](
    binding:   Binding[H],
    state:     H,
    scheduler: Scheduler[H],
  ): Unit =
    binding.newTable(state)

    binding.pushString(state, "spawn")
    registerSpawnLike(binding, state, "spawn")(scheduler.spawnImmediate)
    binding.rawSet(state, -3)

    binding.pushString(state, "defer")
    registerSpawnLike(binding, state, "defer")(scheduler.deferTask)
    binding.rawSet(state, -3)

    binding.pushString(state, "delay")
    registerDelayFn(binding, state, scheduler)
    binding.rawSet(state, -3)

    binding.pushString(state, "wait")
    registerWaitFn(binding, state, scheduler)
    binding.rawSet(state, -3)

    binding.pushString(state, "cancel")
    registerCancelFn(binding, state, scheduler)
    binding.rawSet(state, -3)

    binding.setGlobal(state, "task")

  /** Pin the function at `fnPos` and every argument after it into registry
    * Refs (the scheduler consumes them when building the task thread).
    */
  private def collectFnAndArgs[H](
    binding: Binding[H], s: H, fnPos: Int, nargs: Int,
  ): (Ref[H], List[Ref[H]]) =
    binding.pushCopy(s, fnPos)
    val fnRef = binding.ref(s)
    val extraRefs = ((fnPos + 1) to nargs).map { i =>
      binding.pushCopy(s, i)
      binding.ref(s)
    }.toList
    (fnRef, extraRefs)

  /** Push the task's thread object onto the CALLING thread `s` as the Lua
    * return value — Ref.push() would land it on the main state's stack,
    * where the dispatcher never reads results. spawnImmediate may have
    * already run the task to a terminal state — the scheduler leaves the
    * threadRef open for exactly this push, and we own closing it (see
    * Scheduler.spawnImmediate).
    */
  private def returnThread[H](binding: Binding[H], s: H, handle: TaskHandle[H]): NativeFnResult =
    binding.pushRef(s, handle.threadRef.registryKey)
    if handle.isDone then handle.threadRef.close()
    Return(1)

  /** task.spawn / task.defer: identical surface, different scheduling. */
  private def registerSpawnLike[H](
    binding: Binding[H], state: H, name: String,
  )(run: (Ref[H], List[Ref[H]]) => TaskHandle[H]): Unit =
    val fn: NativeFn[H] = (s, nargs) =>
      if nargs < 1 then Return(0)
      else
        binding.typeAt(s, 1) match
          case LuaType.Function =>
            val (fnRef, extraRefs) = collectFnAndArgs(binding, s, 1, nargs)
            returnThread(binding, s, run(fnRef, extraRefs))
          case _ =>
            binding.pushString(s, s"task.$name: expected function as first argument")
            Fail
    binding.registerNativeFn(state, fn)

  private def registerDelayFn[H](
    binding: Binding[H], state: H, scheduler: Scheduler[H]
  ): Unit =
    val fn: NativeFn[H] = (s, nargs) =>
      if nargs < 2 then Return(0)
      else
        val seconds = binding.toNumber(s, 1).getOrElse(0.0)
        binding.typeAt(s, 2) match
          case LuaType.Function =>
            val (fnRef, extraRefs) = collectFnAndArgs(binding, s, 2, nargs)
            returnThread(binding, s, scheduler.scheduleDelayed(fnRef, extraRefs, seconds))
          case _ =>
            binding.pushString(s, "task.delay: expected function as second argument")
            Fail
    binding.registerNativeFn(state, fn)

  private def registerWaitFn[H](
    binding: Binding[H], state: H, scheduler: Scheduler[H]
  ): Unit =
    val fn: NativeFn[H] = (s, nargs) =>
      val seconds = if nargs >= 1 then binding.toNumber(s, 1).getOrElse(0.0) else 0.0

      scheduler.currentTask match
        case None =>
          binding.pushString(s,
            "task.wait called from a coroutine not owned by the Scheduler; " +
            "behavior is undefined (see ADR-0004)")
          Fail
        case Some(_) =>
          Suspend { resume =>
            val t0 = System.nanoTime()
            scheduler.scheduleTimer(seconds) {
              val elapsed = (System.nanoTime() - t0) / 1e9
              resume.succeed(LuaValue.Number(elapsed))
            }
          }
    binding.registerNativeFn(state, fn)

  private def registerCancelFn[H](
    binding: Binding[H], state: H, scheduler: Scheduler[H]
  ): Unit =
    val fn: NativeFn[H] = (s, nargs) =>
      if nargs < 1 then Return(0)
      else
        binding.typeAt(s, 1) match
          case LuaType.Thread =>
            binding.pushCopy(s, 1)
            val ref = binding.ref(s)
            scheduler.cancelThread(ref)
            ref.close()
            Return(0)
          case _ => Return(0)
    binding.registerNativeFn(state, fn)
