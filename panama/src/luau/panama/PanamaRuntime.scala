package luau.panama

import java.lang.foreign.{Arena, MemorySegment}
import luau.core.{StateSlot, SuspendRegistry}

/** Process-level singleton: arena, dispatcher, upcall stub, and the single
  * live-state slot (§0). Owns NO VM. Lazy on first use; never closed (Q2) —
  * per-VM purge in VmData is the leak firewall.
  */
object PanamaRuntime:

  final class VmData(val state: MemorySegment):
    val suspendRegistry = new SuspendRegistry
    val fnIds = scala.collection.mutable.Set.empty[Int]

  private val slot = new StateSlot[VmData]

  lazy val arena: Arena = Arena.ofShared()
  lazy val dispatcher: NativeFnDispatcher = new NativeFnDispatcher
  lazy val upcallStub: MemorySegment = dispatcher.allocateUpcallStub(arena)

  def reserve(): Unit = slot.reserve()

  def release(): Unit = slot.release()

  /** newState: Free or Reserved → Live; Live → throw. */
  def mount(state: MemorySegment): VmData =
    val data = new VmData(state)
    slot.mount(data)
    data

  /** closeState: purge fnIds + drop in-flight suspends, then Free. */
  def unmount(state: MemorySegment): Unit =
    slot.unmountIf(_.state.address() == state.address()).foreach { d =>
      d.fnIds.foreach(dispatcher.unregister)
      d.suspendRegistry.clear()
    }

  def liveData: Option[VmData] = slot.live

  // ---- Test hooks (acceptance §2.4) -------------------------------------
  @volatile private[panama] var statesOpened: Long = 0L
  private[panama] def countOpen(): Unit = statesOpened += 1
  private[panama] def registeredFnCount: Int = dispatcher.registeredCount
  private[panama] def inFlightSuspendCount: Int =
    liveData.map(_.suspendRegistry.size).getOrElse(0)
