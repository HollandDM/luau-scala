package luau.panama

import java.lang.foreign.{Arena, MemorySegment}
import java.util.concurrent.atomic.AtomicReference
import luau.core.SuspendRegistry

/** Process-level singleton: arena, dispatcher, upcall stub, and the single
  * live-state slot (§0). Owns NO VM. Lazy on first use; never closed (Q2) —
  * per-VM purge in VmData is the leak firewall.
  */
object PanamaRuntime:

  final class VmData(val state: MemorySegment):
    val suspendRegistry = new SuspendRegistry
    val fnIds = scala.collection.mutable.Set.empty[Int]

  private enum Slot:
    case Free
    case Reserved
    case Live(data: VmData)

  private val slot = new AtomicReference[Slot](Slot.Free)

  lazy val arena: Arena = Arena.ofShared()
  lazy val dispatcher: NativeFnDispatcher = new NativeFnDispatcher
  lazy val upcallStub: MemorySegment = dispatcher.allocateUpcallStub(arena)

  private val illegal =
    "one live state per runtime (plan 10 §0): close the current state first"

  def reserve(): Unit =
    if !slot.compareAndSet(Slot.Free, Slot.Reserved) then
      throw new IllegalStateException(illegal)

  def release(): Unit = slot.set(Slot.Free)

  /** newState: Free or Reserved → Live; Live → throw. */
  def mount(state: MemorySegment): VmData =
    val data = new VmData(state)
    slot.get match
      case Slot.Live(_) => throw new IllegalStateException(illegal)
      case prev =>
        if !slot.compareAndSet(prev, Slot.Live(data)) then
          throw new IllegalStateException(illegal)
        data

  /** closeState: purge fnIds + drop in-flight suspends, then Free. */
  def unmount(state: MemorySegment): Unit =
    slot.get match
      case Slot.Live(d) if d.state.address() == state.address() =>
        d.fnIds.foreach(dispatcher.unregister)
        d.suspendRegistry.clear()
        slot.set(Slot.Free)
      case _ => ()

  def liveData: Option[VmData] = slot.get match
    case Slot.Live(d) => Some(d)
    case _            => None

  // ---- Test hooks (acceptance §2.4) -------------------------------------
  @volatile private[panama] var statesOpened: Long = 0L
  private[panama] def countOpen(): Unit = statesOpened += 1
  private[panama] def registeredFnCount: Int = dispatcher.registeredCount
  private[panama] def inFlightSuspendCount: Int =
    liveData.map(_.suspendRegistry.size).getOrElse(0)
