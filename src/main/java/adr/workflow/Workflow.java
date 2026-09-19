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
import adr.domain.OpState;
import adr.domain.Operation;
import adr.domain.OperationState;
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
  public record Refusal(int status, String code, String message, boolean recorded) {
    public Refusal(int status, String code, String message) { this(status, code, message, false); }
  }

  private final Executor runs;
  private final Policy policy;
  private final ToolBroker broker;
  private final AgentRunner runner;
  private final ApprovalService approvals;
  /** Test seam: runs before verification results are computed. Null in production. */
  public volatile java.util.function.Consumer<Session> beforeVerificationHook;

  public Workflow(Executor runs, Policy policy, ModelClient model) {
    this.runs = runs;
    this.policy = policy;
    this.broker = new ToolBroker(policy);
    this.runner = new AgentRunner(model, broker);
    this.approvals = new ApprovalService(policy);
  }

  public Executor runs() { return runs; }
  public Policy policy() { return policy; }
  public ToolBroker broker() { return broker; }
  public AgentRunner runner() { return runner; }
  public ApprovalService approvals() { return approvals; }

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
    return approvals.request(s, findingId, planHash, actor);
  }

  public Refusal approve(Session s, UUID findingId, String planHash, Actor actor, String comment) {
    ApprovalService.Outcome o = approvals.approve(s, findingId, planHash, actor, comment);
    if (o.refusal() != null) return o.refusal();
    Operation op = o.operation();
    runs.execute(() -> runRemediation(s, findingId, op));
    return null;
  }

  public Refusal reject(Session s, UUID findingId, String planHash, Actor actor, String comment) {
    return approvals.reject(s, findingId, planHash, actor, comment);
  }

  /** The out-of-band change: dba_oncall re-grants the owner role through a separate client. The poller does the rest. */
  public Refusal triggerDrift(Session s, UUID findingId, Actor actor) {
    Findings.Entry entry = s.stores().findings().get(findingId);
    adr.stores.Seed.AuditRow row = adr.target.OutOfBandClient.regrant(s.target(), s.stores().audit(), entry.finding.ownerRole(), entry.finding.subjectRole());
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("switch", "out_of_band_drift");
    p.put("actor", row.actor());
    p.put("action", row.action());
    p.put("ticket", row.ticket());
    p.put("by", actor.id());
    s.timeline().append(TimelineEvent.of(findingId, ActorType.chaos, "chaos", "supervise", "chaos.fired",
        "Injected: " + row.actor() + " ran \"" + row.action() + "\" outside the system (" + row.ticket() + "). The poller checks the fingerprint every "
            + (DriftPoller.INTERVAL_MS / 1000) + " seconds.", p, Labels.AUDIT));
    return null;
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
          "plan v" + v2.version() + " " + v2.hash().substring(0, 8) + " passed guards, lint and policy",
          () -> ApprovalService.voidOpen(s, findingId, "the plan changed to " + v2.hash().substring(0, 8)));
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

  // ---- remediation: the recorded agent applies by reference, the execution guard decides what happened ----

  void runRemediation(Session s, UUID findingId, Operation op) {
    Findings.Entry entry = s.stores().findings().get(findingId);
    RunContext ctx = new RunContext(s, findingId, AgentId.remediation, "main");
    ctx.operationId = op.id();
    try {
      AgentRunner.RunResult r = runner.run(ctx);
      OpState st = op.state();
      if (r.stopped()) {
        if (st.state() == OperationState.EXECUTING || st.state() == OperationState.OUTCOME_UNKNOWN) {
          reconcile(s, ctx, op);
          st = op.state();
        }
        boolean stale = (st.state() == OperationState.FAILED_NOT_APPLIED && !st.retryable())
            || (st.state() == OperationState.APPROVED && !op.preflightPassed());
        if (stale && !"RECORDING_MISS".equals(r.stopCode())) { backToAnalysis(s, findingId, op); return; }
        attention(s, findingId, FindingState.REMEDIATING, r.stopCode(), r.trigger() + (op.lastError() == null ? "" : ": " + op.lastError()));
        return;
      }
      Decision.Remediation d = (Decision.Remediation) r.decision();
      if (d.verdict() == Decision.Remediation.Verdict.escalate) {
        attention(s, findingId, FindingState.REMEDIATING, "AGENT_ESCALATED", d.reason());
        return;
      }
      if (op.state().state() != OperationState.APPLIED) {
        attention(s, findingId, FindingState.REMEDIATING, "OPERATION_" + op.state().state(), "the operation did not reach APPLIED");
        return;
      }
      Lifecycle.Result t = Lifecycle.transition(s, s.stores(), findingId, FindingState.REMEDIATING, FindingState.VERIFYING,
          "operation " + op.id().toString().substring(0, 8) + " is APPLIED", Lifecycle.Effects.NONE);
      if (t == Lifecycle.Result.OK) runVerification(s, findingId, op);
    } catch (RuntimeException e) {
      attention(s, findingId, FindingState.REMEDIATING, "INTERNAL_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** Deterministic reconciliation, run by the workflow itself after a safe stop, so an operation is never left unknown. */
  void reconcile(Session s, RunContext ctx, Operation op) {
    adr.domain.Plan plan = s.stores().plans().byHash(op.planHash());
    for (int i = 0; i < Operation.MAX_RECONCILIATIONS; i++) {
      adr.target.TargetDatabase.StatusResult st = s.target().operationStatus(Caller.agent_remediator, op.id());
      OpState cur = op.state();
      if (!st.inFlight() && st.found()) {
        ExecutionGuard.applied(s, ctx, op, false, st.row(), st.fingerprint());
        note(s, ctx.findingId, "remediate", "Workflow reconciled operation " + op.id().toString().substring(0, 8) + ": ledger row found, applied once.");
        return;
      }
      if (!st.inFlight() && plan != null && plan.exec().expectedBefore().equals(st.fingerprint())) {
        op.compareAndSet(cur, cur.failedNotApplied(true));
        ExecutionGuard.stateEvent(s, ctx, op, OperationState.FAILED_NOT_APPLIED, "Workflow reconciled: nothing committed, retryable.");
        return;
      }
      op.reconciliations().incrementAndGet();
    }
    OpState cur = op.state();
    op.compareAndSet(cur, cur.needsHuman());
    ExecutionGuard.stateEvent(s, ctx, op, OperationState.NEEDS_HUMAN, "Three inconclusive reconciliations. A human must look.");
  }

  void backToAnalysis(Session s, UUID findingId, Operation op) {
    Lifecycle.Result r = Lifecycle.transition(s, s.stores(), findingId, FindingState.REMEDIATING, FindingState.ANALYSING,
        "plan is stale: " + (op.lastError() == null ? "pre-flight failed" : op.lastError()),
        () -> ApprovalService.voidOpen(s, findingId, "the plan is stale and returns to analysis"));
    if (r == Lifecycle.Result.OK) runAnalysis(s, findingId);
  }

  // ---- verification: facts first, then the recorded verifier, then the verdict guard ----

  void runVerification(Session s, UUID findingId, Operation op) {
    Findings.Entry entry = s.stores().findings().get(findingId);
    try {
      adr.domain.Plan plan = s.stores().plans().byHash(op.planHash());
      java.util.function.Consumer<Session> hook = beforeVerificationHook;
      if (hook != null) hook.accept(s);
      adr.domain.VerificationRecord rec = Verification.compute(s, entry, op, plan.exec());
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("rule", "verification.checks");
      p.put("result", rec.allPassed() ? "PASS" : "REFUSED");
      p.put("record", rec);
      String summary = rec.assertions().size() + " assertions, " + rec.probes().size() + " probes, " + rec.scenarios().size() + " scenarios"
          + (rec.allPassed() ? ": all passed" : ": " + rec.failed().size() + " failed");
      s.timeline().append(TimelineEvent.of(findingId, ActorType.gate, "verification", "verify", "verification.completed",
          "verification.checks: " + (rec.allPassed() ? "PASS" : "REFUSED") + ". " + summary + ", computed before the verifier's turn", p, Labels.SIMULATED_PG));

      RunContext ctx = new RunContext(s, findingId, AgentId.verification, "main");
      ctx.operationId = op.id();
      AgentRunner.RunResult r = runner.run(ctx);
      if (r.stopped()) { attention(s, findingId, FindingState.VERIFYING, r.stopCode(), r.trigger()); return; }
      Decision.Verification d = (Decision.Verification) r.decision();
      if (d.verdict() != Decision.Verification.Verdict.pass) {
        attention(s, findingId, FindingState.VERIFYING, "VERIFICATION_" + d.verdict().name().toUpperCase(),
            "rollback is designed but not built in D0, so the lifecycle stops here: " + d.reason());
        return;
      }
      close(s, entry, op, plan);
    } catch (RuntimeException e) {
      attention(s, findingId, FindingState.VERIFYING, "INTERNAL_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  void close(Session s, Findings.Entry entry, Operation op, adr.domain.Plan plan) {
    UUID findingId = entry.finding.id();
    String live = s.target().fingerprint(Caller.agent_supervisor, entry.finding.subjectRole());
    Map<String, Object> snap = s.target().snapshot(Caller.agent_supervisor, entry.finding.subjectRole());
    Lifecycle.transition(s, s.stores(), findingId, FindingState.VERIFYING, FindingState.CLOSED,
        "verdict pass, evidence sealed", () -> {
          entry.sealedFingerprint = live;
          entry.sealedSnapshot = snap;
          String docId = "OUT-" + (9000 + s.stores().corpus().overlay().size() + 1);
          adr.stores.Seed.CorpusDoc outcome = new adr.stores.Seed.CorpusDoc(docId, "outcome",
              entry.finding.assetId() + ": owner membership right-size succeeded", entry.finding.findingType(), "orders", "success", null,
              plan.exec().grants().stream().map(g -> new adr.stores.Seed.PrivilegeRef(g.privilege(), g.kind(), g.object())).toList(),
              List.of("owner", "membership", "success"),
              "Plan v" + plan.version() + " replaced the owner membership with " + plan.exec().grants().size() + " explicit grants. Verified with "
                  + String.join(", ", plan.exec().scenarios()) + ". Written back by this session; in memory only.");
          s.stores().corpus().addOutcome(outcome);
          Map<String, Object> p = new LinkedHashMap<>();
          p.put("rule", "evidence.seal");
          p.put("result", "PASS");
          p.put("sealed_fingerprint", live);
          p.put("operation_id", op.id().toString());
          p.put("outcome_doc_id", docId);
          return List.of(TimelineEvent.of(findingId, ActorType.gate, "workflow", "supervise", "evidence.sealed",
              "evidence.seal: PASS. Fingerprint " + live.substring(0, 8) + " sealed; outcome " + docId + " written to the corpus overlay. Drift supervision starts.",
              p, Labels.PLATFORM));
        });
  }

  static void note(Session s, UUID findingId, String stage, String text) {
    s.timeline().append(TimelineEvent.of(findingId, ActorType.system, "workflow", stage, "system.notice", text, Map.of(), Labels.PLATFORM));
  }

  // ---- supervision: the poller detected drift; the recorded supervisor classifies it; a guard has the last word ----

  public void startSupervisor(Session s, UUID findingId) {
    runs.execute(() -> runSupervision(s, findingId));
  }

  void runSupervision(Session s, UUID findingId) {
    Findings.Entry entry = s.stores().findings().get(findingId);
    RunContext ctx = new RunContext(s, findingId, AgentId.supervision, "main");
    try {
      AgentRunner.RunResult r = runner.run(ctx);
      if (r.stopped()) { attention(s, findingId, FindingState.DRIFT_DETECTED, r.stopCode(), r.trigger()); return; }
      Decision.Supervision d = (Decision.Supervision) r.decision();
      switch (d.verdict()) {
        case reopen -> {
          Policy.Decision pd = policy.evaluateReopen(entry.state());
          gate(s, findingId, "supervise", pd.rule(), pd.allowed(), pd.reason());
          if (!pd.allowed()) { attention(s, findingId, FindingState.DRIFT_DETECTED, "POLICY_DENIED", pd.reason()); return; }
          reopen(s, findingId, d);
        }
        case annotate -> reseal(s, entry, s.target().fingerprint(Caller.agent_supervisor, entry.finding.subjectRole()),
            "classified as an authorised change: " + d.reason());
        case inconclusive -> attention(s, findingId, FindingState.DRIFT_DETECTED, "SUPERVISOR_INCONCLUSIVE", d.reason());
      }
    } catch (RuntimeException e) {
      attention(s, findingId, FindingState.DRIFT_DETECTED, "INTERNAL_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * Reopen, atomically. The reopen-legality guard ran before this in the runner; here one transition creates
   * the child in OPEN, moves the active pointer and appends three events with consecutive sequence numbers.
   */
  Lifecycle.Result reopen(Session s, UUID parentId, Decision.Supervision decision) {
    Findings.Entry parent = s.stores().findings().get(parentId);
    UUID childId = UUID.randomUUID();
    return Lifecycle.transition(s, s.stores(), parentId, FindingState.DRIFT_DETECTED, FindingState.REOPENED, decision.reason(), () -> {
      adr.domain.Finding child = parent.finding.child(childId);
      s.stores().findings().add(child);
      UUID from = s.activeFindingId();
      s.activeFindingId(childId);
      parent.reopenedBySeq = s.timeline().lastSeq() + 1;
      Map<String, Object> p1 = new LinkedHashMap<>();
      p1.put("finding_id", childId.toString());
      p1.put("state", "OPEN");
      p1.put("parent_finding_id", parentId.toString());
      p1.put("finding", adr.app.Snapshot.finding(s, s.stores().findings().get(childId)));
      Map<String, Object> p2 = new LinkedHashMap<>();
      p2.put("from", from == null ? null : from.toString());
      p2.put("to", childId.toString());
      return List.of(
          TimelineEvent.of(childId, ActorType.system, "lifecycle", "analyse", "finding.created",
              "Finding reopened as " + childId.toString().substring(0, 8) + " in OPEN, linked to its parent", p1, Labels.PLATFORM),
          TimelineEvent.of(childId, ActorType.system, "lifecycle", "analyse", "session.active_finding_changed",
              "The workbench now shows the reopened finding", p2, Labels.PLATFORM));
    });
  }

  /** An authorised change, or the finding's own after-state: reseal with the new fingerprint. */
  void reseal(Session s, Findings.Entry entry, String fp, String why) {
    UUID findingId = entry.finding.id();
    FindingState from = entry.state();
    if (from != FindingState.DRIFT_DETECTED && from != FindingState.CLOSED) return;
    Map<String, Object> snap = s.target().snapshot(Caller.agent_supervisor, entry.finding.subjectRole());
    Lifecycle.Effects effects = () -> {
      entry.sealedFingerprint = fp;
      entry.sealedSnapshot = snap;
      entry.drift = null;
      return List.of(TimelineEvent.of(findingId, ActorType.gate, "workflow", "supervise", "evidence.sealed",
          "evidence.seal: PASS. Resealed with fingerprint " + fp.substring(0, 8) + ": " + why,
          Map.of("rule", "evidence.seal", "result", "PASS", "sealed_fingerprint", fp), Labels.PLATFORM));
    };
    if (from == FindingState.DRIFT_DETECTED) {
      Lifecycle.transition(s, s.stores(), findingId, FindingState.DRIFT_DETECTED, FindingState.CLOSED, "resealed: " + why, effects);
    } else {
      synchronized (s.lock()) { if (s.isCurrent()) s.timeline().appendAll(effects.run()); }
    }
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
