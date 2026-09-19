package adr.workflow;

import adr.agents.ModelClient;
import adr.app.Commands;
import adr.app.Session;
import adr.app.SessionRegistry;
import adr.broker.Policy;
import adr.domain.FindingState;
import adr.domain.Json;
import adr.domain.TimelineEvent;
import adr.stores.Seed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The guided flow, run headless in a throwaway session, four times: no faults, duplicate plus dropped
 * response, abort before commit, and drift. Asserts zero recording misses and the expected state sequence.
 * Runs at start-up and as a test, so a miss normally fails the build first.
 */
public final class GoldenFlow {
  public record Result(String config, boolean ok, List<String> states, int misses, String detail) {}

  public static final List<String> BASE = List.of("ANALYSING", "PLAN_READY", "AWAITING_APPROVAL", "REMEDIATING", "VERIFYING", "CLOSED");
  public static final List<String> WITH_DRIFT = List.of("ANALYSING", "PLAN_READY", "AWAITING_APPROVAL", "REMEDIATING", "VERIFYING", "CLOSED", "DRIFT_DETECTED", "REOPENED");
  public static final List<String> CONFIGS = List.of("no_faults", "duplicate_and_drop", "abort_before_commit", "drift");

  private GoldenFlow() {}

  public static List<Result> run(Policy policy, ModelClient model, Seed seed) {
    List<Result> out = new ArrayList<>();
    for (String config : CONFIGS) out.add(run(config, policy, model, seed));
    return out;
  }

  public static Result run(String config, Policy policy, ModelClient model, Seed seed) {
    Workflow workflow = new Workflow(Runnable::run, policy, model);
    SessionRegistry registry = new SessionRegistry((id, epoch, reg) -> Session.fromSeed(id, epoch, reg, seed));
    Commands commands = new Commands(registry, workflow);
    DriftPoller poller = new DriftPoller(registry::live, workflow);
    Session s = registry.mint();
    UUID f = s.activeFindingId();
    try {
      send(commands, s, "start_analysis", "requester", Map.of("finding_id", f.toString()));
      String hash = s.stores().plans().latest(f).hash();
      send(commands, s, "request_approval", "requester", Map.of("finding_id", f.toString(), "plan_hash", hash));
      switch (config) {
        case "duplicate_and_drop" -> {
          send(commands, s, "arm_chaos", "dba", Map.of("switch", "duplicate_delivery"));
          send(commands, s, "arm_chaos", "dba", Map.of("switch", "drop_response"));
        }
        case "abort_before_commit" -> send(commands, s, "arm_chaos", "dba", Map.of("switch", "abort_before_commit"));
        default -> { }
      }
      send(commands, s, "approve", "dba", Map.of("finding_id", f.toString(), "plan_hash", hash));
      if (config.equals("drift")) {
        send(commands, s, "trigger_drift", "dba", Map.of("finding_id", f.toString()));
        poller.tick(s);
      }
    } catch (RuntimeException e) {
      return new Result(config, false, states(s, f), misses(s), "exception: " + e);
    }
    List<String> states = states(s, f);
    int misses = misses(s);
    List<String> expected = config.equals("drift") ? WITH_DRIFT : BASE;
    boolean childOpen = !config.equals("drift") || s.stores().findings().childrenOf(f).stream().anyMatch(c -> c.state() == FindingState.OPEN);
    boolean ok = misses == 0 && states.equals(expected) && childOpen && s.counters().mutations.get() == 1;
    String detail = ok ? "ok" : "states " + states + ", misses " + misses + ", mutations " + s.counters().mutations.get()
        + (childOpen ? "" : ", no child in OPEN");
    return new Result(config, ok, states, misses, detail);
  }

  static void send(Commands commands, Session s, String type, String persona, Map<String, Object> args) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    body.put("persona", persona);
    body.put("args", args);
    Commands.Result r = commands.handle(s, Json.write(Json.SNAKE, body), UUID.randomUUID().toString());
    if (r.status() >= 300) throw new IllegalStateException(type + " refused: " + r.body());
  }

  static List<String> states(Session s, UUID f) {
    List<String> out = new ArrayList<>();
    for (TimelineEvent e : s.timeline().all()) {
      if (e.kind().equals("finding.state_changed") && f.equals(e.findingId())) out.add(String.valueOf(e.payload().get("to")));
    }
    return out;
  }

  static int misses(Session s) {
    int n = 0;
    for (TimelineEvent e : s.timeline().all()) {
      if (e.kind().equals("agent.stopped") && "RECORDING_MISS".equals(e.payload().get("code"))) n++;
    }
    return n;
  }
}
