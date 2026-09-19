package adr.chaos;

import adr.app.Session;
import adr.domain.ActorType;
import adr.domain.Labels;
import adr.domain.TimelineEvent;
import adr.target.CommitHook;
import adr.target.TargetException;

import java.util.Map;
import java.util.UUID;

/** The fault seams. Each firing consumes its one-shot switch and appends a chaos.fired event. */
public final class Chaos {
  private Chaos() {}

  public static CommitHook hook(Session s, UUID findingId, UUID operationId) {
    return new CommitHook() {
      @Override public void beforeCommit() {
        if (s.chaos().consume(ChaosSwitches.Switch.abort_before_commit)) {
          fire(s, findingId, ChaosSwitches.Switch.abort_before_commit, operationId,
              "Injected: the database reported an error before commit. The draft is discarded.");
          throw new TargetException.AbortedBeforeCommit("injected abort before commit");
        }
      }
      @Override public void afterCommit() {
        if (s.chaos().consume(ChaosSwitches.Switch.drop_response)) {
          fire(s, findingId, ChaosSwitches.Switch.drop_response, operationId,
              "Injected: the change committed but the reply was lost on the way back.");
          throw new TargetException.ResponseLost("injected response loss after commit");
        }
      }
    };
  }

  public static boolean duplicateDelivery(Session s, UUID findingId, UUID operationId) {
    if (!s.chaos().consume(ChaosSwitches.Switch.duplicate_delivery)) return false;
    fire(s, findingId, ChaosSwitches.Switch.duplicate_delivery, operationId,
        "Injected: the write tool call is delivered twice. Two threads race the lease.");
    return true;
  }

  static void fire(Session s, UUID findingId, ChaosSwitches.Switch sw, UUID operationId, String summary) {
    s.timeline().append(TimelineEvent.of(findingId, ActorType.chaos, "chaos", "remediate", "chaos.fired", summary,
        Map.of("switch", sw.name(), "operation_id", operationId == null ? "" : operationId.toString()), Labels.PLATFORM));
  }
}
