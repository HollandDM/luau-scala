package luau.api

import scala.util.{Failure, Success, Try}
import luau.core.*
import luau.core.codec.*
import luau.scheduler.{Scheduler, TaskHandle}

final class TaskWorld[H] private[api] (
  private[api] val st: LuaState[H],
  private[api] val scheduler: Scheduler[H],
):

  def eval[V: LuauDecoder](source: String, chunkname: String = "=eval"): Try[V] =
    st.eval(source, chunkname)

  def eval0(source: String, chunkname: String = "=eval0"): Try[Unit] =
    st.eval0(source, chunkname)

  def eval1[A: LuauDecoder](source: String, chunkname: String = "=eval1"): Try[A] =
    st.eval1(source, chunkname)

  def eval2[A: LuauDecoder, B: LuauDecoder](
    source: String, chunkname: String = "=eval2"
  ): Try[(A, B)] =
    st.eval2(source, chunkname)

  def eval3[A: LuauDecoder, B: LuauDecoder, C: LuauDecoder](
    source: String, chunkname: String = "=eval3"
  ): Try[(A, B, C)] =
    st.eval3(source, chunkname)

  def eval4[A: LuauDecoder, B: LuauDecoder, C: LuauDecoder, D: LuauDecoder](
    source: String, chunkname: String = "=eval4"
  ): Try[(A, B, C, D)] =
    st.eval4(source, chunkname)

  def run(source: String, chunkname: String = "=run"): Try[Unit] =
    st.run(source, chunkname)

  def get[V: LuauDecoder](key: String): Try[V] = st.get(key)
  def set[T: LuauEncoder](key: String, value: T): Unit = st.set(key, value)
  def getFn(key: String)(using s: RefScope[H]): Try[LuaFn[H]] = st.getFn(key)
  def getTbl(key: String)(using s: RefScope[H]): Try[LuaTbl[H]] = st.getTbl(key)
  def defineGlobal[R: LuauEncoder](name: String)(f: () => R): Unit =
    st.defineGlobal(name)(f)
  def defineGlobal[A: LuauDecoder, R: LuauEncoder](name: String)(f: A => R): Unit =
    st.defineGlobal(name)(f)
  def defineGlobal[A: LuauDecoder, B: LuauDecoder, R: LuauEncoder](
    name: String
  )(f: (A, B) => R): Unit =
    st.defineGlobal(name)(f)
  def defineGlobal[A: LuauDecoder, B: LuauDecoder, C: LuauDecoder, R: LuauEncoder](
    name: String
  )(f: (A, B, C) => R): Unit =
    st.defineGlobal(name)(f)
  def defineGlobal[A: LuauDecoder, B: LuauDecoder, C: LuauDecoder, D: LuauDecoder, R: LuauEncoder](
    name: String
  )(f: (A, B, C, D) => R): Unit =
    st.defineGlobal(name)(f)
  def useRef[A](f: (s: RefScope[H]) ?=> A): A = st.useRef(f)
  def evalFn(source: String, chunkname: String = "=evalFn")(using s: RefScope[H]): Try[LuaFn[H]] =
    st.evalFn(source, chunkname)
  def coro(fn: LuaFn[H])(using s: RefScope[H]): LuaCoro[H] = st.coro(fn)

  def spawn(source: String, chunkname: String = "=task"): Try[TaskHandle[H]] =
    scheduler.spawnChunk(source, chunkname).fold(Failure(_), Success(_))

  def spawnFn(fn: LuaFn[H], args: LuaArg*): TaskHandle[H] =
    val thread = scheduler.binding.newThread(scheduler.state)
    val threadRef = scheduler.binding.ref(scheduler.state)
    scheduler.binding.pushRef(thread, fn.ref.registryKey)
    args.foreach(a => a.pushTo[H](scheduler.binding, thread))
    scheduler.spawnReady(threadRef, thread, args.size)

  def defineAsync[Arg: LuauDecoder](name: String)(start: Arg => (Resume => Cancel)): Unit =
    st.installNative(name, (thread, nargs) =>
      scheduler.binding.decodeAt[Arg](thread, 1) match
        case Left(e)  =>
          scheduler.binding.pushString(thread, e.message)
          NativeFnResult.Fail
        case Right(a) => NativeFnResult.Suspend(start(a)))
