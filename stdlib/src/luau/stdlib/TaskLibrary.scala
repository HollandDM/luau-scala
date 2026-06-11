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
    registerSpawnLike(binding, state, "spawn")(
      scheduler.spawnImmediate, scheduler.spawnImmediateAdopted)
    binding.rawSet(state, -3)

    binding.pushString(state, "defer")
    registerSpawnLike(binding, state, "defer")(
      scheduler.deferTask, scheduler.deferAdopted)
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

  /** Pin every argument from `fromPos` to `nargs` into registry Refs (the
    * scheduler consumes them when arming the task thread).
    */
  private def collectArgs[H](
    binding: Binding[H], s: H, fromPos: Int, nargs: Int,
  ): List[Ref[H]] =
    (fromPos to nargs).map { i =>
      binding.pushCopy(s, i)
      binding.ref(s)
    }.toList

  /** Pin the function at `fnPos` and every argument after it. */
  private def collectFnAndArgs[H](
    binding: Binding[H], s: H, fnPos: Int, nargs: Int,
  ): (Ref[H], List[Ref[H]]) =
    binding.pushCopy(s, fnPos)
    val fnRef = binding.ref(s)
    (fnRef, collectArgs(binding, s, fnPos + 1, nargs))

  /** Extract the coroutine VALUE at `pos` as a raw handle plus a registry
    * pin of the object (the pin becomes the adopted task's threadRef).
    */
  private def adoptThreadAt[H](
    binding: Binding[H], s: H, pos: Int,
  ): Option[(H, Ref[H])] =
    binding.toThreadAt(s, pos).map { thread =>
      binding.pushCopy(s, pos)
      (thread, binding.ref(s))
    }

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

  /** task.spawn / task.defer: identical surface, different scheduling. The
    * first argument is a function (fresh task thread) or a coroutine thread
    * (adopted as-is, Roblox parity).
    */
  private def registerSpawnLike[H](
    binding: Binding[H], state: H, name: String,
  )(
    run:        (Ref[H], List[Ref[H]]) => TaskHandle[H],
    runAdopted: (H, Ref[H], List[Ref[H]]) => TaskHandle[H],
  ): Unit =
    val fn: NativeFn[H] = (s, nargs) =>
      if nargs < 1 then Return(0)
      else
        binding.typeAt(s, 1) match
          case LuaType.Function =>
            val (fnRef, extraRefs) = collectFnAndArgs(binding, s, 1, nargs)
            returnThread(binding, s, run(fnRef, extraRefs))
          case LuaType.Thread =>
            adoptThreadAt(binding, s, 1) match
              case Some((thread, threadRef)) =>
                val extraRefs = collectArgs(binding, s, 2, nargs)
                returnThread(binding, s, runAdopted(thread, threadRef, extraRefs))
              case None =>
                binding.pushString(s, s"task.$name: could not extract thread argument")
                Fail
          case _ =>
            binding.pushString(s, s"task.$name: expected function or thread as first argument")
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
          case LuaType.Thread =>
            adoptThreadAt(binding, s, 2) match
              case Some((thread, threadRef)) =>
                val extraRefs = collectArgs(binding, s, 3, nargs)
                returnThread(binding, s,
                  scheduler.delayAdopted(thread, threadRef, extraRefs, seconds))
              case None =>
                binding.pushString(s, "task.delay: could not extract thread argument")
                Fail
          case _ =>
            binding.pushString(s, "task.delay: expected function or thread as second argument")
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
        binding.toThreadAt(s, 1) match
          case Some(thread) => scheduler.cancelThreadHandle(thread); Return(0)
          case None         => Return(0) // non-thread: silent no-op (Roblox parity)
    binding.registerNativeFn(state, fn)
