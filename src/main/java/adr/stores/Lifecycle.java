package adr.stores;

import adr.domain.ActorType;
import adr.domain.FindingState;
import adr.domain.Labels;
import adr.domain.TimelineEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static adr.domain.FindingState.*;

/**
 * The one door through which a finding moves. Every caller, including the drift poller, the execution
 * guard's callback and the supervisor path, comes here. Effects cannot fail: every check that could refuse
 * the move runs before the lock is taken, and inside the lock there are only in-memory writes.
 */
public final class Lifecycle {
  private Lifecycle() {}

  public enum Result { OK, LOST_RACE, SESSION_GONE }

  /** In-memory side effects of a move. Returns the events to append after the state_changed event. */
  public interface Effects {
    List<TimelineEvent> run();
    Effects NONE = List::of;
  }

  private static final EnumMap<FindingState, Set<FindingState>> LEGAL = new EnumMap<>(FindingState.class);

  static {
    LEGAL.put(OPEN, Set.of(ANALYSING));
    LEGAL.put(ANALYSING, Set.of(PLAN_READY, NEEDS_ATTENTION));
    LEGAL.put(PLAN_READY, Set.of(AWAITING_APPROVAL));
    LEGAL.put(AWAITING_APPROVAL, Set.of(PLAN_READY, REMEDIATING));
    LEGAL.put(REMEDIATING, Set.of(VERIFYING, ANALYSING, NEEDS_ATTENTION));
    LEGAL.put(VERIFYING, Set.of(CLOSED, NEEDS_ATTENTION));
    LEGAL.put(CLOSED, Set.of(DRIFT_DETECTED));
    LEGAL.put(DRIFT_DETECTED, Set.of(REOPENED, CLOSED, NEEDS_ATTENTION));
    LEGAL.put(REOPENED, Set.of());
    LEGAL.put(NEEDS_ATTENTION, Set.of());
  }

  public static boolean legal(FindingState from, FindingState to) {
    return LEGAL.getOrDefault(from, Set.of()).contains(to);
  }

  public static Result transition(SessionHandle session, Stores stores, UUID findingId, FindingState from,
                                  FindingState to, String reason, Effects effects) {
    if (!legal(from, to)) throw new IllegalArgumentException("illegal transition " + from + " -> " + to);
    synchronized (session.lock()) {
      if (!session.isCurrent()) return Result.SESSION_GONE;
      if (!stores.findings().casState(findingId, from, to)) return Result.LOST_RACE;
      List<TimelineEvent> events = new ArrayList<>();
      String stage = to == NEEDS_ATTENTION ? from.stage() : to.stage();
      events.add(TimelineEvent.of(findingId, ActorType.system, "lifecycle", stage, "finding.state_changed",
          "Finding " + from + " → " + to + ": " + reason,
          Map.of("finding_id", findingId.toString(), "from", from.name(), "to", to.name(), "reason", reason),
          Labels.PLATFORM));
      events.addAll(effects.run());
      stores.timeline().appendAll(events);
      return Result.OK;
    }
  }
}
