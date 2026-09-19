package adr.app;

import adr.chaos.ChaosSwitches;
import adr.domain.Actor;
import adr.domain.ActorType;
import adr.domain.FindingState;
import adr.domain.InvalidInput;
import adr.domain.Json;
import adr.domain.Labels;
import adr.domain.Require;
import adr.domain.TimelineEvent;
import adr.workflow.Workflow;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Command admission. Every value is validated by explicit code before anything else looks at it; commands
 * are checked against the finding's state table; admission is serialised per session under one lock.
 */
public final class Commands {
  public enum Type { start_analysis, request_approval, approve, reject, trigger_drift, arm_chaos, start_burst, kill_switch, reset }
  public enum Persona { requester, dba }

  public static final int MAX_BODY = 8 * 1024;

  public record Request(String type, String persona, Map<String, Object> args) {
    public Request {
      Require.oneOf(type, Type.class, "type");
      Require.oneOf(persona, Persona.class, "persona");
      if (args == null) args = Map.of();
    }
  }

  public record Result(int status, Map<String, Object> body) {
    public static Result accepted() { return new Result(202, Map.of("accepted", true)); }
    public static Result ok(Map<String, Object> body) { return new Result(200, body); }
    public static Result invalid(InvalidInput e) {
      return new Result(400, Map.of("error", Map.of("code", "INVALID_INPUT", "field", e.field(), "problem", e.problem())));
    }
    public static Result refused(int status, String code, String message) {
      return new Result(status, Map.of("error", Map.of("code", code, "message", message)));
    }
    public String code() {
      Object err = body.get("error");
      return err instanceof Map<?, ?> m ? String.valueOf(m.get("code")) : null;
    }
  }

  public record FindingArgs(String findingId) {
    public FindingArgs { Require.matches(findingId, Require.UUID_REF, "args.finding_id"); }
    public UUID id() { return UUID.fromString(findingId); }
  }

  public record PlanArgs(String findingId, String planHash) {
    public PlanArgs {
      Require.matches(findingId, Require.UUID_REF, "args.finding_id");
      Require.matches(planHash, Require.HEX64, "args.plan_hash");
    }
    public UUID id() { return UUID.fromString(findingId); }
  }

  public record DecisionArgs(String findingId, String planHash, String comment) {
    public DecisionArgs {
      Require.matches(findingId, Require.UUID_REF, "args.finding_id");
      Require.matches(planHash, Require.HEX64, "args.plan_hash");
      Require.maxLen(comment, 500, "args.comment");
    }
    public UUID id() { return UUID.fromString(findingId); }
  }

  public record ChaosArgs(@JsonProperty("switch") String chaosSwitch) {
    public ChaosArgs { Require.oneOf(chaosSwitch, ChaosSwitches.Switch.class, "args.switch"); }
    public ChaosSwitches.Switch value() { return ChaosSwitches.Switch.valueOf(chaosSwitch); }
  }

  public record BurstArgs(Integer count) {
    public BurstArgs { Require.range(count, 1, 5000, "args.count"); }
  }

  public record KillArgs(Boolean on) {
    public KillArgs { Require.nonNull(on, "args.on"); }
  }

  public record ResetArgs() {}

  public static final EnumMap<Type, Set<FindingState>> LEGAL_IN = new EnumMap<>(Type.class);

  static {
    LEGAL_IN.put(Type.start_analysis, Set.of(FindingState.OPEN));
    LEGAL_IN.put(Type.request_approval, Set.of(FindingState.PLAN_READY));
    LEGAL_IN.put(Type.approve, Set.of(FindingState.AWAITING_APPROVAL));
    LEGAL_IN.put(Type.reject, Set.of(FindingState.AWAITING_APPROVAL));
    LEGAL_IN.put(Type.trigger_drift, Set.of(FindingState.CLOSED));
  }

  public static boolean isLifecycle(Type t) { return LEGAL_IN.containsKey(t); }

  private final SessionRegistry registry;
  private final Workflow workflow;

  public Commands(SessionRegistry registry, Workflow workflow) {
    this.registry = registry;
    this.workflow = workflow;
  }

  public Result handle(Session s, String body, String idempotencyKey) {
    try {
      if (body == null || body.isBlank()) throw new InvalidInput("$", "missing body");
      if (body.length() > MAX_BODY) throw new InvalidInput("$", "larger than 8 KB");
      String key = Require.maxLen(Require.nonBlank(idempotencyKey, "Idempotency-Key"), 64, "Idempotency-Key");
      Request req = Json.read(Json.SNAKE, body, Request.class);
      Actor actor = Actor.forPersona(req.persona());
      synchronized (s.lock()) {
        Result cached = s.idempotency().get(key);
        if (cached != null) return cached;
        Result r = admit(s, req, actor);
        s.idempotency().put(key, r);
        return r;
      }
    } catch (InvalidInput e) {
      gate(s, null, "command.input", "REFUSED", "Invalid input: " + e.field() + " " + e.problem(),
          Map.of("field", e.field(), "problem", e.problem()));
      return Result.invalid(e);
    }
  }

  private Result admit(Session s, Request req, Actor actor) {
    Type t = Type.valueOf(req.type());
    if (t == Type.reset) {
      parse(req, ResetArgs.class);
      if (s.busy()) return refuse(s, null, 409, "BUSY", "Available when the current stage finishes");
      Session fresh = registry.replace(s);
      s.timeline().closeWith(TimelineEvent.of(null, ActorType.system, "session_registry", "session", "session.reset",
          "Session reset. Reloading onto the new timeline.", Map.of("new_epoch", fresh.epoch()), Labels.PLATFORM));
      return Result.ok(Map.of("reset", true, "epoch", fresh.epoch()));
    }
    if (s.timeline().atCap()) return refuse(s, null, 409, "SESSION_FULL", "Session full, reset to continue");

    return switch (t) {
      case start_analysis -> {
        FindingArgs a = parse(req, FindingArgs.class);
        Result r = checkFinding(s, t, a.id());
        if (r != null) yield r;
        if (!"requester".equals(actor.persona()))
          yield refuse(s, a.id(), 409, "PERSONA_NOT_ALLOWED", "Only the requester persona can start analysis");
        yield outcome(s, a.id(), workflow.startAnalysis(s, a.id(), actor));
      }
      case request_approval -> {
        PlanArgs a = parse(req, PlanArgs.class);
        Result r = checkFinding(s, t, a.id());
        if (r != null) yield r;
        yield outcome(s, a.id(), workflow.requestApproval(s, a.id(), a.planHash(), actor));
      }
      case approve -> {
        DecisionArgs a = parse(req, DecisionArgs.class);
        Result r = checkFinding(s, t, a.id());
        if (r != null) yield r;
        yield outcome(s, a.id(), workflow.approve(s, a.id(), a.planHash(), actor, a.comment()));
      }
      case reject -> {
        DecisionArgs a = parse(req, DecisionArgs.class);
        Result r = checkFinding(s, t, a.id());
        if (r != null) yield r;
        yield outcome(s, a.id(), workflow.reject(s, a.id(), a.planHash(), actor, a.comment()));
      }
      case trigger_drift -> {
        FindingArgs a = parse(req, FindingArgs.class);
        Result r = checkFinding(s, t, a.id());
        if (r != null) yield r;
        yield outcome(s, a.id(), workflow.triggerDrift(s, a.id(), actor));
      }
      case arm_chaos -> {
        ChaosArgs a = parse(req, ChaosArgs.class);
        yield outcome(s, null, workflow.armChaos(s, a.value(), actor));
      }
      case start_burst -> {
        BurstArgs a = parse(req, BurstArgs.class);
        if (s.burstRunning().get()) yield refuse(s, null, 409, "BURST_RUNNING", "A burst is already running");
        yield outcome(s, null, workflow.startBurst(s, a.count(), actor));
      }
      case kill_switch -> {
        KillArgs a = parse(req, KillArgs.class);
        yield outcome(s, null, workflow.killSwitch(s, a.on(), actor));
      }
      case reset -> throw new IllegalStateException("handled above");
    };
  }

  private Result outcome(Session s, UUID findingId, Workflow.Refusal refusal) {
    if (refusal == null) return Result.accepted();
    if (refusal.recorded()) return Result.refused(refusal.status(), refusal.code(), refusal.message()); // the service already gated it
    return refuse(s, findingId, refusal.status(), refusal.code(), refusal.message());
  }

  private Result checkFinding(Session s, Type t, UUID id) {
    if (!s.stores().findings().contains(id)) throw new InvalidInput("args.finding_id", "not a finding in this session");
    FindingState state = s.stores().findings().stateOf(id);
    if (!LEGAL_IN.get(t).contains(state)) {
      return refuse(s, id, 409, "ILLEGAL_STATE", t + " is not allowed while the finding is " + state);
    }
    return null;
  }

  private Result refuse(Session s, UUID findingId, int status, String code, String message) {
    gate(s, findingId, "command." + code.toLowerCase(), "REFUSED", message, Map.of("code", code));
    return Result.refused(status, code, message);
  }

  private void gate(Session s, UUID findingId, String rule, String result, String summary, Map<String, Object> extra) {
    Map<String, Object> payload = new LinkedHashMap<>(extra);
    payload.put("rule", rule);
    payload.put("result", result);
    String stage = findingId == null ? "session" : s.stores().findings().stateOf(findingId).stage();
    s.recordRefusal(TimelineEvent.of(findingId, ActorType.gate, "command_admission", stage, "gate.checked",
        rule + ": " + result + ". " + summary, payload, Labels.PLATFORM));
  }

  private static <T> T parse(Request req, Class<T> type) {
    return Json.convert(Json.SNAKE, req.args(), type);
  }
}
