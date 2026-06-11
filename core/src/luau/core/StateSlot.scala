package luau.core

import java.util.concurrent.atomic.AtomicReference

/** The runtime's single live-state slot (plan 10 §0): one live state per
  * runtime at a time, shared mechanism for every backend.
  *
  * Transitions: Free → Reserved (facade entry, caller thread) → Live(data)
  * (newState) → Free (closeState). A direct newState without a reservation
  * mounts Free → Live. Reserving or mounting while a state is live throws
  * IllegalStateException.
  */
final class StateSlot[D]:
  private enum Slot:
    case Free
    case Reserved
    case Live(data: D)

  private val slot = new AtomicReference[Slot](Slot.Free)

  private def illegal(): Nothing =
    throw new IllegalStateException(StateSlot.IllegalMsg)

  /** Free → Reserved; anything else → throw. */
  def reserve(): Unit =
    if !slot.compareAndSet(Slot.Free, Slot.Reserved) then illegal()

  /** Unconditionally back to Free (entry failed before newState). Idempotent. */
  def release(): Unit = slot.set(Slot.Free)

  /** Free or Reserved → Live(data); Live → throw. */
  def mount(data: D): Unit =
    slot.get match
      case Slot.Live(_) => illegal()
      case prev =>
        if !slot.compareAndSet(prev, Slot.Live(data)) then illegal()

  /** Live with matching data → Free, returning the data; otherwise None. */
  def unmountIf(p: D => Boolean): Option[D] =
    slot.get match
      case l @ Slot.Live(d) if p(d) =>
        slot.compareAndSet(l, Slot.Free)
        Some(d)
      case _ => None

  def live: Option[D] = slot.get match
    case Slot.Live(d) => Some(d)
    case _            => None

object StateSlot:
  val IllegalMsg: String =
    "one live state per runtime (plan 10 §0): close the current state first"
