package adr.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The event envelope. There is deliberately no "durable" field: an event with a seq is in the
 * session timeline and replayed on reconnect; an event without one (gauges) is transient.
 */
public record TimelineEvent(
    Long seq,
    String ts,
    UUID findingId,
    ActorType actorType,
    String actor,
    String stage,
    String kind,
    String summary,
    Map<String, Object> payload,
    String sourceLabel,
    String agentMode,
    String targetMode) {

  public static TimelineEvent of(UUID findingId, ActorType actorType, String actor, String stage,
                                 String kind, String summary, Map<String, Object> payload, String sourceLabel) {
    return new TimelineEvent(null, Instant.now().toString(), findingId, actorType, actor, stage, kind, summary,
        payload == null ? Map.of() : payload, sourceLabel, Labels.AGENT_MODE, Labels.TARGET_MODE);
  }

  public TimelineEvent withSeq(long s) {
    return new TimelineEvent(s, ts, findingId, actorType, actor, stage, kind, summary, payload, sourceLabel, agentMode, targetMode);
  }

  @com.fasterxml.jackson.annotation.JsonIgnore
  public boolean isTerminal() {
    return "session.reset".equals(kind) || "session.expired".equals(kind);
  }
}
