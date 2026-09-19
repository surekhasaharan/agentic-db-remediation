package adr.chaos;

import java.util.EnumSet;
import java.util.Set;

/** One-shot fault switches stored on the session. Each firing consumes the switch. */
public final class ChaosSwitches {
  public enum Switch { duplicate_delivery, drop_response, abort_before_commit }

  private final Set<Switch> armed = EnumSet.noneOf(Switch.class);

  public synchronized void arm(Switch s) { armed.add(s); }
  public synchronized boolean isArmed(Switch s) { return armed.contains(s); }
  /** Returns true once per arming. */
  public synchronized boolean consume(Switch s) { return armed.remove(s); }
  public synchronized Set<Switch> armedNow() { return EnumSet.copyOf(armed.isEmpty() ? EnumSet.noneOf(Switch.class) : armed); }
}
