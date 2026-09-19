package adr;

import adr.app.Commands;
import adr.app.Session;
import adr.app.SessionRegistry;
import adr.domain.FindingState;
import adr.domain.Json;
import adr.stores.Lifecycle;
import adr.stores.Seed;
import adr.workflow.Workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a Session from the seed and drives the Workflow directly with a direct executor, so every agent run
 * completes on the calling thread. No HTTP, no browser, no mocks.
 */
public final class TestSession {
  public final SessionRegistry registry;
  public final Workflow workflow;
  public final Commands commands;
  public Session session;

  public TestSession() {
    Seed seed = Seed.load();
    this.workflow = new Workflow(Runnable::run);
    this.registry = new SessionRegistry((id, epoch, reg) -> Session.fromSeed(id, epoch, reg, seed));
    this.commands = new Commands(registry, workflow);
    this.session = registry.mint();
  }

  public UUID findingId() { return session.activeFindingId(); }

  public FindingState state() { return session.stores().findings().stateOf(findingId()); }

  public Commands.Result send(String type, String persona, Map<String, Object> args) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    body.put("persona", persona);
    body.put("args", args);
    return commands.handle(session, Json.write(Json.SNAKE, body), UUID.randomUUID().toString());
  }

  public Commands.Result sendRaw(String json) {
    return commands.handle(session, json, UUID.randomUUID().toString());
  }

  /** Forces a legal transition without running any stage, for arranging states. */
  public Lifecycle.Result force(UUID findingId, FindingState from, FindingState to) {
    return Lifecycle.transition(session, session.stores(), findingId, from, to, "test", Lifecycle.Effects.NONE);
  }

  /** Walks the legal path from OPEN to the given state, for arranging states. */
  public void driveTo(FindingState target) {
    List<FindingState> path = List.of(FindingState.OPEN, FindingState.ANALYSING, FindingState.PLAN_READY,
        FindingState.AWAITING_APPROVAL, FindingState.REMEDIATING, FindingState.VERIFYING, FindingState.CLOSED,
        FindingState.DRIFT_DETECTED, FindingState.REOPENED);
    if (target == FindingState.NEEDS_ATTENTION) {
      force(findingId(), FindingState.OPEN, FindingState.ANALYSING);
      force(findingId(), FindingState.ANALYSING, FindingState.NEEDS_ATTENTION);
      return;
    }
    for (int i = 1; i < path.size(); i++) {
      if (path.get(i - 1) == target) return;
      force(findingId(), path.get(i - 1), path.get(i));
      if (path.get(i) == target) return;
    }
  }

  public Map<String, Object> args(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }
}
