package luau.wasm

import scala.scalajs.js
import luau.core.*

object LxStatus:
  val Ok: Int    = 0
  val Yield: Int = 1
  val Err: Int   = 2
  val MemErr: Int = 3

object LxReturn:
  val Return: Int  = 0
  val Fail: Int    = 1
  val Suspend: Int = 2

object Trampoline:
  type NativeFn = (Int, Int) => NativeFnResult

  private var nextId: Int = 1
  private val table = scala.collection.mutable.HashMap.empty[Int, NativeFn]

  private var fnPtr: Int = -1

  private var pendingSuspend: Option[Resume => Cancel] = None

  def consumePendingSuspend(): Option[Resume => Cancel] =
    val r = pendingSuspend
    pendingSuspend = None
    r

  def install(): Int =
    if fnPtr == -1 then
      val upcall: js.Function5[Int, Int, Int, Int, Int, Int] =
        (state: Int, thread: Int, fnId: Int, nArgs: Int, nResultsPtr: Int) =>
          dispatch(state, thread, fnId, nArgs, nResultsPtr)
      fnPtr = WasmModule.module.addFunction(upcall, "iiiiii")
    fnPtr

  def uninstall(): Unit =
    if fnPtr != -1 then
      WasmModule.module.removeFunction(fnPtr)
      fnPtr = -1

  def register(fn: NativeFn): Int =
    val id = nextId
    nextId += 1
    table(id) = fn
    id

  def unregister(fnId: Int): Unit =
    table.remove(fnId)

  private def dispatch(state: Int, thread: Int, fnId: Int, nArgs: Int, nResultsPtr: Int): Int =
    table.get(fnId) match
      case None =>
        WasmMarshal.withString(s"luau-scala: unknown fnId $fnId in trampoline") { (ptr, len) =>
          WasmModule.module._lx_push_lstring(state, thread, ptr, len)
        }
        LxReturn.Fail
      case Some(fn) =>
        try fn(state, nArgs) match
          case NativeFnResult.Return(n) =>
            writeNResults(nResultsPtr, n)
            LxReturn.Return
          case NativeFnResult.Fail(_) =>
            LxReturn.Fail
          case NativeFnResult.Suspend(reg) =>
            pendingSuspend = Some(reg)
            LxReturn.Suspend
        catch
          case t: Throwable =>
            val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
            WasmMarshal.withString(s"luau-scala: native fn threw: $msg") { (ptr, len) =>
              WasmModule.module._lx_push_lstring(state, thread, ptr, len)
            }
            LxReturn.Fail

  private def writeNResults(ptr: Int, n: Int): Unit =
    val heap = WasmModule.module.HEAPU8
    heap(ptr)     = (n & 0xff).toShort
    heap(ptr + 1) = ((n >> 8) & 0xff).toShort
    heap(ptr + 2) = ((n >> 16) & 0xff).toShort
    heap(ptr + 3) = ((n >> 24) & 0xff).toShort
