package adr.app;

import adr.domain.Actor;
import adr.domain.Approval;
import adr.domain.Labels;
import adr.domain.Operation;
import adr.domain.Plan;
import adr.stores.Findings;
import adr.stores.Seed;
import adr.workflow.Workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The state snapshot for first paint and reconnect, taken under the session lock for a consistent view. */
public final class Snapshot {
  private Snapshot() {}

  public static Map<String, Object> of(Session s) {
    synchronized (s.lock()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("session_epoch", s.epoch());
      m.put("last_seq", s.timeline().lastSeq());
      m.put("busy", s.busy());
      m.put("can_reset", !s.busy());
      m.put("active_finding_id", s.activeFindingId() == null ? null : s.activeFindingId().toString());
      List<Map<String, Object>> findings = new ArrayList<>();
      for (Findings.Entry e : s.stores().findings().all()) findings.add(finding(s, e));
      m.put("findings", findings);
      m.put("counters", s.counters().snapshot());
      m.put("chaos", Map.of("armed", s.chaos().armedNow().stream().map(Enum::name).toList()));
      m.put("kill_switch", s.killSwitch().get());
      m.put("burst_running", s.burstRunning().get());
      m.put("personas", List.of(Workflow.actorMap(Actor.SAM), Workflow.actorMap(Actor.DANA)));
      m.put("labels", labels());
      m.put("context", context(s));
      return m;
    }
  }

  public static Map<String, Object> labels() {
    Map<String, Object> l = new LinkedHashMap<>();
    l.put("banner", Labels.BANNER);
    l.put("agent_mode", Labels.AGENT_MODE);
    l.put("target_mode", Labels.TARGET_MODE);
    l.put("persistence", Labels.PERSISTENCE);
    l.put("identity_mode", Labels.IDENTITY_MODE);
    l.put("persona_tag", Labels.PERSONA_TAG);
    l.put("captured", "authored");
    return l;
  }

  static Map<String, Object> context(Session s) {
    Seed seed = s.stores().seed();
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("asset", seed.asset);
    c.put("telemetry", Map.of("label", seed.telemetry.label(), "window_days", seed.telemetry.windowDays(),
        "rows", s.stores().telemetry().allRows()));
    c.put("jobs", Map.of("label", seed.jobRuns.label(), "rows", s.stores().telemetry().allJobs()));
    c.put("audit", Map.of("label", seed.audit.label(), "rows", s.stores().audit().all()));
    List<Map<String, Object>> docs = new ArrayList<>();
    for (Seed.CorpusDoc d : s.stores().corpus().all()) {
      docs.add(Map.of("id", d.id(), "kind", d.kind(), "title", d.title(), "disposition", d.disposition() == null ? "" : d.disposition()));
    }
    c.put("corpus", Map.of("label", seed.corpus.label(), "documents", docs));
    return c;
  }

  public static Map<String, Object> finding(Session s, Findings.Entry e) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("id", e.finding.id().toString());
    f.put("parent_finding_id", e.finding.parentFindingId() == null ? null : e.finding.parentFindingId().toString());
    f.put("state", e.state().name());
    f.put("stage", e.state().stage());
    f.put("finding_type", e.finding.findingType());
    f.put("asset_id", e.finding.assetId());
    f.put("subject_role", e.finding.subjectRole());
    f.put("owner_role", e.finding.ownerRole());
    f.put("title", e.finding.title());
    f.put("description", e.finding.description());
    f.put("criticality", e.finding.criticality());
    f.put("sealed_fingerprint", e.sealedFingerprint);
    f.put("attention_reason", e.attentionReason);
    f.put("requester", e.requester == null ? null : Workflow.actorMap(e.requester));
    List<Map<String, Object>> plans = new ArrayList<>();
    for (Plan p : s.stores().plans().forFinding(e.finding.id())) plans.add(plan(p));
    f.put("plans", plans);
    Approval a = s.stores().approvals().latest(e.finding.id());
    f.put("approval", a == null ? null : approval(a));
    Operation op = e.currentOperationId == null ? null : s.stores().operations().get(e.currentOperationId);
    f.put("operation", op == null ? null : operation(op));
    f.put("verification", e.verification);
    f.put("drift", e.drift);
    f.put("children", s.stores().findings().childrenOf(e.finding.id()).stream().map(c -> c.finding.id().toString()).toList());
    return f;
  }

  public static Map<String, Object> plan(Plan p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("version", p.version());
    m.put("hash", p.hash());
    m.put("finding_id", p.exec().findingId().toString());
    m.put("target_role", p.exec().targetRole());
    m.put("owner_role", p.exec().ownerRole());
    m.put("grants", p.exec().grants());
    m.put("scenarios", p.exec().scenarios());
    m.put("expected_before", p.exec().expectedBefore());
    m.put("added_grants", p.addedGrants());
    m.put("added_scenarios", p.addedScenarios());
    m.put("policy_version", p.policyVersion());
    m.put("created_at", p.createdAt());
    return m;
  }

  public static Map<String, Object> approval(Approval a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", a.id().toString());
    m.put("finding_id", a.findingId().toString());
    m.put("plan_hash", a.planHash());
    m.put("status", a.status().name());
    m.put("requester", Workflow.actorMap(a.requester()));
    m.put("approver", a.approver() == null ? null : Workflow.actorMap(a.approver()));
    m.put("comment", a.comment());
    m.put("requested_at", a.requestedAt().toString());
    m.put("expires_at", a.expiresAt().toString());
    m.put("decided_at", a.decidedAt() == null ? null : a.decidedAt().toString());
    m.put("identity_mode", Labels.IDENTITY_MODE);
    return m;
  }

  public static Map<String, Object> operation(Operation op) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", op.id().toString());
    m.put("finding_id", op.findingId().toString());
    m.put("plan_hash", op.planHash());
    m.put("state", op.state().state().name());
    m.put("attempt", op.state().attempt());
    m.put("retryable", op.state().retryable());
    m.put("lease_owner", op.state().leaseOwner());
    m.put("deliveries", op.deliveries());
    m.put("history", op.history());
    m.put("reconciliations", op.reconciliations().get());
    m.put("preflight_passed", op.preflightPassed());
    m.put("last_error", op.lastError());
    m.put("created_at", op.createdAt().toString());
    return m;
  }
}
