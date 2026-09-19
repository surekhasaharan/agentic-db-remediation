package adr.workflow;

import adr.app.Session;
import adr.chaos.ChaosSwitches;
import adr.domain.Actor;
import adr.domain.ActorType;
import adr.domain.FindingState;
import adr.domain.Labels;
import adr.domain.TimelineEvent;
import adr.stores.Findings;
import adr.stores.Lifecycle;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * The deterministic orchestrator. Agents propose, guards decide, and only this class asks Lifecycle to move a
 * finding. Phase 2 builds the command entry points; later phases fill in the stages.
 */
public final class Workflow {
  public record Refusal(int status, String code, String message) {}

  private final Executor runs;

  public Workflow(Executor runs) {
    this.runs = runs;
  }

  public Executor runs() { return runs; }

  public Refusal startAnalysis(Session s, UUID findingId, Actor actor) {
    Findings.Entry entry = s.stores().findings().get(findingId);
    Lifecycle.Result r = Lifecycle.transition(s, s.stores(), findingId, FindingState.OPEN, FindingState.ANALYSING,
        "analysis requested by " + actor.displayName(), () -> {
          entry.requester = actor;
          return List.of(TimelineEvent.of(findingId, ActorType.human, actor.id(), "analyse", "analysis.requested",
              actor.displayName() + " (" + actor.role() + ", " + Labels.PERSONA_TAG + ") requested analysis",
              Map.of("actor", actorMap(actor)), Labels.PLATFORM));
        });
    if (r != Lifecycle.Result.OK) return new Refusal(409, "LOST_RACE", "The finding moved before the command ran");
    runs.execute(() -> runAnalysis(s, findingId));
    return null;
  }

  /** Filled in at phase 4. */
  void runAnalysis(Session s, UUID findingId) { }

  public Refusal requestApproval(Session s, UUID findingId, String planHash, Actor actor) {
    return new Refusal(409, "NOT_AVAILABLE", "Approval is not built yet");
  }

  public Refusal approve(Session s, UUID findingId, String planHash, Actor actor, String comment) {
    return new Refusal(409, "NOT_AVAILABLE", "Approval is not built yet");
  }

  public Refusal reject(Session s, UUID findingId, String planHash, Actor actor, String comment) {
    return new Refusal(409, "NOT_AVAILABLE", "Approval is not built yet");
  }

  public Refusal triggerDrift(Session s, UUID findingId, Actor actor) {
    return new Refusal(409, "NOT_AVAILABLE", "Drift is not built yet");
  }

  public Refusal armChaos(Session s, ChaosSwitches.Switch sw, Actor actor) {
    s.chaos().arm(sw);
    s.timeline().append(TimelineEvent.of(null, ActorType.chaos, "chaos", "session", "chaos.armed",
        "Injected fault armed: " + sw.name().replace('_', ' '), Map.of("switch", sw.name(), "by", actor.id()), Labels.PLATFORM));
    return null;
  }

  public Refusal startBurst(Session s, int count, Actor actor) {
    return new Refusal(409, "NOT_AVAILABLE", "The burst is not built yet");
  }

  public Refusal killSwitch(Session s, boolean on, Actor actor) {
    s.killSwitch().set(on);
    s.timeline().append(TimelineEvent.of(null, ActorType.human, actor.id(), "session", "kill_switch.changed",
        "Kill switch " + (on ? "on: write and probe tools are blocked" : "off"), Map.of("on", on, "by", actor.id()), Labels.PLATFORM));
    return null;
  }

  public static Map<String, Object> actorMap(Actor a) {
    return Map.of("id", a.id(), "display_name", a.displayName(), "role", a.role(), "persona", a.persona(),
        "authenticated", a.authenticated(), "identity_mode", a.identityMode());
  }
}
