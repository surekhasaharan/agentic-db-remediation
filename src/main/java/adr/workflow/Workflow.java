package adr.workflow;

import adr.agents.AgentRunner;
import adr.agents.ModelClient;
import adr.app.Session;
import adr.broker.Guards;
import adr.broker.PlanHash;
import adr.broker.Policy;
import adr.broker.RunContext;
import adr.broker.ToolBroker;
import adr.chaos.ChaosSwitches;
import adr.domain.Actor;
import adr.domain.ActorType;
import adr.domain.AgentId;
import adr.domain.Approval;
import adr.domain.Decision;
import adr.domain.FindingState;
import adr.domain.GrantSpec;
import adr.domain.Labels;
import adr.domain.Plan;
import adr.domain.PlanExec;
import adr.domain.TimelineEvent;
import adr.stores.Findings;
import adr.stores.Lifecycle;
import adr.target.Caller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * The deterministic orchestrator. Agents propose, guards decide, and only this class asks Lifecycle to move a
 * finding. Slow work (agent runs) happens on the runs executor, never inside a lock.
 */
public final class Workflow {
  public record Refusal(int status, String code, String message) {}

  private final Executor runs;
  private final Policy policy;
  private final ToolBroker broker;
  private final AgentRunner runner;

  public Workflow(Executor runs, Policy policy, ModelClient model) {
    this.runs = runs;
    this.policy = policy;
    this.broker = new ToolBroker(policy);
    this.runner = new AgentRunner(model, broker);
  }

  public Executor runs() { return runs; }
  public Policy policy() { return policy; }
  public ToolBroker broker() { return broker; }
  public AgentRunner runner() { return runner; }

  // ---- commands ----

  public Refusal startAnalysis(Session s, UUID findingId, Actor actor) {
    Findings.Entry entry = s.stores().findings().get(findingId);
    Lifecycle.Result r = Lifecycle.transition(s, s.stores(), findingId, FindingState.OPEN, FindingState.ANALYSING,
        "analysis requested by " + actor.displayName(), () -> {
          entry.requester = actor;
          return List.of(human(findingId, actor, "analyse", "analysis.requested",
              actor.displayName() + " (" + actor.role() + ", " + Labels.PERSONA_TAG + ") requested analysis", Map.of()));
        });
    if (r != Lifecycle.Result.OK) return new Refusal(409, "LOST_RACE", "The finding moved before the command ran");
    runs.execute(() -> runAnalysis(s, findingId));
    return null;
  }

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
        "Injected fault armed: " + sw.name().replace('_', ' ') + ". It fires once, at the next write.",
        Map.of("switch", sw.name(), "by", actor.id()), Labels.PLATFORM));
    return null;
  }

  public Refusal startBurst(Session s, int count, Actor actor) {
    return new Refusal(409, "NOT_AVAILABLE", "The burst is not built yet");
  }

  public Refusal killSwitch(Session s, boolean on, Actor actor) {
    s.killSwitch().set(on);
    s.timeline().append(human(null, actor, "session", "kill_switch.changed",
        "Kill switch " + (on ? "on: write and probe tools are blocked" : "off"), Map.of("on", on)));
    return null;
  }

  // ---- analysis: phase A, deterministic retrieval, phase B ----

  void runAnalysis(Session s, UUID findingId) {
    Findings.Entry entry = s.stores().findings().get(findingId);
    RunContext ctx = new RunContext(s, findingId, AgentId.analysis, "A");
    try {
      AgentRunner.RunResult a = runner.run(ctx);
      if (a.stopped()) { attention(s, findingId, FindingState.ANALYSING, a.stopCode(), a.trigger()); return; }
      Decision.Analysis da = (Decision.Analysis) a.decision();
      if (da.verdict() != Decision.Analysis.Verdict.fix) {
        attention(s, findingId, FindingState.ANALYSING, "DECISION_" + da.verdict().name().toUpperCase(), "accept and defer are roadmap");
        return;
      }
      Plan v1 = storePlan(s, entry, da, null);
      if (v1 == null) return;

      Retrieval.run(ctx);

      ctx.phase = "B";
      AgentRunner.RunResult b = runner.run(ctx);
      if (b.stopped()) { attention(s, findingId, FindingState.ANALYSING, b.stopCode(), b.trigger()); return; }
      Decision.Analysis db = (Decision.Analysis) b.decision();
      if (db.verdict() != Decision.Analysis.Verdict.fix) {
        attention(s, findingId, FindingState.ANALYSING, "DECISION_" + db.verdict().name().toUpperCase(), "accept and defer are roadmap");
        return;
      }
      Plan v2 = storePlan(s, entry, db, v1);
      if (v2 == null) return;

      Lifecycle.transition(s, s.stores(), findingId, FindingState.ANALYSING, FindingState.PLAN_READY,
          "plan v" + v2.version() + " " + v2.hash().substring(0, 8) + " passed guards, lint and policy", () -> {
            List<TimelineEvent> extra = new ArrayList<>();
            for (Approval ap : s.stores().approvals().forFinding(findingId)) {
              if (ap.status() == Approval.Status.REQUESTED && !ap.planHash().equals(v2.hash())) {
                ap.mark(Approval.Status.VOIDED, Instant.now());
                extra.add(TimelineEvent.of(findingId, ActorType.gate, "approval_service", "approve", "approval.voided",
                    "approval.plan_binding: VOIDED. The plan changed to " + v2.hash().substring(0, 8),
                    Map.of("rule", "approval.plan_binding", "result", "VOIDED", "approval_id", ap.id().toString()), Labels.PLATFORM));
              }
            }
            return extra;
          });
    } catch (RuntimeException e) {
      attention(s, findingId, FindingState.ANALYSING, "INTERNAL_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** Builds the plan version from the decision, runs lint and policy admission, and stores it. */
  Plan storePlan(Session s, Findings.Entry entry, Decision.Analysis d, Plan previous) {
    UUID findingId = entry.finding.id();
    Set<GrantSpec> grants = new LinkedHashSet<>();
    Set<String> scenarios = new LinkedHashSet<>();
    if (previous != null) { grants.addAll(previous.exec().grants()); scenarios.addAll(previous.exec().scenarios()); }
    for (Decision.RetainGrant g : d.retainGrants()) grants.add(g.grant());
    scenarios.addAll(d.healthScenarios());
    String expectedBefore = s.target().fingerprint(Caller.agent_analysis, entry.finding.subjectRole());
    PlanExec exec = new PlanExec(findingId, entry.finding.subjectRole(), entry.finding.ownerRole(),
        new ArrayList<>(grants), new ArrayList<>(scenarios), expectedBefore);

    Guards.Result lint = Guards.planLint(exec, s.target().state(), s.appSimulator());
    gate(s, findingId, "analyse", "guard.plan_lint", lint.ok(), lint.ok() ? "objects, ownership, schema usage, size and scenarios are valid" : String.join("; ", lint.failures()));
    if (!lint.ok()) { attention(s, findingId, FindingState.ANALYSING, "PLAN_LINT", String.join("; ", lint.failures())); return null; }

    List<String> admission = policy.admitPlan(entry.finding.findingType(), entry.finding.subjectRole(), exec,
        List.of("completeness", "justification", "scenario_coverage", "citation"));
    gate(s, findingId, "analyse", "policy.plan_admission", admission.isEmpty(),
        admission.isEmpty() ? "finding type, target, size, privileges, scenarios and required guards admitted" : String.join("; ", admission));
    if (!admission.isEmpty()) { attention(s, findingId, FindingState.ANALYSING, "POLICY_DENIED", String.join("; ", admission)); return null; }

    String hash = PlanHash.of(exec);
    List<GrantSpec> added = new ArrayList<>(exec.grants());
    List<String> addedScenarios = new ArrayList<>(exec.scenarios());
    if (previous != null) { added.removeAll(previous.exec().grants()); addedScenarios.removeAll(previous.exec().scenarios()); }
    Plan plan = new Plan(s.stores().plans().nextVersion(findingId), hash, exec, added, addedScenarios, policy.version(), Instant.now().toString());
    s.stores().plans().add(findingId, plan);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("plan", adr.app.Snapshot.plan(plan));
    p.put("previous_hash", previous == null ? null : previous.hash());
    s.timeline().append(TimelineEvent.of(findingId, ActorType.gate, "workflow", "analyse", "plan.versioned",
        "plan.version: v" + plan.version() + " " + hash.substring(0, 8) + ", " + exec.grants().size() + " grants, "
            + exec.scenarios().size() + " scenarios" + (previous == null ? "" : ", +" + added.size() + " grants, +" + addedScenarios.size() + " scenario"),
        p, Labels.PLATFORM));
    return plan;
  }

  // ---- helpers ----

  void attention(Session s, UUID findingId, FindingState from, String code, String detail) {
    Findings.Entry entry = s.stores().findings().get(findingId);
    String reason = code.equals("RECORDING_MISS") ? "DEMO_FLOW_UNAVAILABLE" : code;
    Lifecycle.transition(s, s.stores(), findingId, from, FindingState.NEEDS_ATTENTION, reason + ": " + detail, () -> {
      entry.attentionReason = reason + ": " + detail;
      return List.of(TimelineEvent.of(findingId, ActorType.system, "workflow", from.stage(), "system.notice",
          code.equals("RECORDING_MISS")
              ? "Demo flow unavailable. This prototype uses recorded agent responses and has none for this situation. Nothing further was changed. Reset to start again."
              : "The lifecycle stopped: " + reason + ". " + detail,
          Map.of("reason", reason, "detail", detail), Labels.PLATFORM));
    });
  }

  static void gate(Session s, UUID findingId, String stage, String rule, boolean ok, String summary) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("rule", rule);
    p.put("result", ok ? "PASS" : "REFUSED");
    TimelineEvent e = TimelineEvent.of(findingId, ActorType.gate, "workflow", stage, "gate.checked",
        rule + ": " + (ok ? "PASS" : "REFUSED") + ". " + summary, p, Labels.PLATFORM);
    if (ok) s.timeline().append(e); else s.recordRefusal(e);
  }

  static TimelineEvent human(UUID findingId, Actor actor, String stage, String kind, String summary, Map<String, Object> extra) {
    Map<String, Object> p = new LinkedHashMap<>(extra);
    p.put("actor", actorMap(actor));
    return TimelineEvent.of(findingId, ActorType.human, actor.id(), stage, kind, summary, p, Labels.PLATFORM);
  }

  public static Map<String, Object> actorMap(Actor a) {
    return Map.of("id", a.id(), "display_name", a.displayName(), "role", a.role(), "persona", a.persona(),
        "authenticated", a.authenticated(), "identity_mode", a.identityMode(), "tag", Labels.PERSONA_TAG);
  }
}
