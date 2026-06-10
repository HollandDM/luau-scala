package luau.stdlib

import luau.core.*
import luau.core.NativeFnResult.{Return, Fail, Suspend}
import luau.scheduler.{Scheduler, Task, TaskHandle}

object TaskLibrary:

  def install[H](
    binding:   Binding[H],
    state:     H,
    scheduler: Scheduler[H],
  ): Unit =
    binding.newTable(state)

    binding.pushString(state, "spawn")
    registerSpawnFn(binding, state, scheduler)
    binding.rawSet(state, -3)

    binding.pushString(state, "defer")
    registerDeferFn(binding, state, scheduler)
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

  private def registerSpawnFn[H](
    binding: Binding[H], state: H, scheduler: Scheduler[H]
  ): Unit =
    val fn: NativeFn[H] = (s, nargs) =>
      if nargs < 1 then Return(0)
      else
        binding.typeAt(s, 1) match
          case LuaType.Function =>
            binding.pushCopy(s, 1)
            val fnRef = binding.ref(s)
            val extraRefs = (2 to nargs).flatMap { i =>
              binding.pushCopy(s, i)
              Some(binding.ref(s))
            }.toList
            val handle = scheduler.spawnImmediate(fnRef, extraRefs)
            handle.threadRef.push()
            Return(1)
          case _ =>
            binding.pushString(s, "task.spawn: expected function as first argument")
            Fail
    binding.registerNativeFn(state, fn)

  private def registerDeferFn[H](
    binding: Binding[H], state: H, scheduler: Scheduler[H]
  ): Unit =
    val fn: NativeFn[H] = (s, nargs) =>
      if nargs < 1 then Return(0)
      else
        binding.typeAt(s, 1) match
          case LuaType.Function =>
            binding.pushCopy(s, 1)
            val fnRef = binding.ref(s)
            val extraRefs = (2 to nargs).flatMap { i =>
              binding.pushCopy(s, i)
              Some(binding.ref(s))
            }.toList
            val handle = scheduler.deferTask(fnRef, extraRefs)
            handle.threadRef.push()
            Return(1)
          case _ =>
            binding.pushString(s, "task.defer: expected function as first argument")
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
            binding.pushCopy(s, 2)
            val fnRef = binding.ref(s)
            val extraRefs = (3 to nargs).flatMap { i =>
              binding.pushCopy(s, i)
              Some(binding.ref(s))
            }.toList
            val handle = scheduler.scheduleDelayed(fnRef, extraRefs, seconds)
            handle.threadRef.push()
            Return(1)
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
        case Some(currentTask) =>
          Suspend { resume =>
            val t0 = System.nanoTime()
            val cancel: Cancel = scheduler.scheduleTimer(seconds) {
              val elapsed = (System.nanoTime() - t0) / 1e9
              scheduler.enqueueResume(currentTask, Right(LuaValue.Number(elapsed)))
            }
            cancel
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
