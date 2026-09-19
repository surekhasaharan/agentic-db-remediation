package adr.broker;

import adr.domain.AgentId;
import adr.domain.DriftObservation;
import adr.domain.Labels;
import adr.domain.OpState;
import adr.domain.Operation;
import adr.domain.OperationState;
import adr.domain.Plan;
import adr.domain.PlanExec;
import adr.domain.VerificationRecord;
import adr.stores.Corpus;
import adr.stores.Findings;
import adr.stores.Seed;
import adr.target.Caller;
import adr.target.Fingerprint;
import adr.target.PgState;
import adr.target.TargetDatabase;
import adr.target.TargetException;
import adr.workflow.ExecutionGuard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The catalogue: the production one minus rollback and minus reopen_finding, which is a workflow move. */
public final class Tools {
  private Tools() {}

  private static final Map<String, ToolSpec> SPECS = new LinkedHashMap<>();

  private static void add(ToolSpec s) { SPECS.put(s.name(), s); }

  static {
    Set<AgentId> A = Set.of(AgentId.analysis), R = Set.of(AgentId.remediation), V = Set.of(AgentId.verification), S = Set.of(AgentId.supervision);
    add(new ToolSpec("read_role_graph", A, Caller.agent_analysis, ToolSpec.Kind.READ, Args.RoleArgs.class, Set.of(), Set.of(), Tools::readRoleGraph));
    add(new ToolSpec("read_grants", A, Caller.agent_analysis, ToolSpec.Kind.READ, Args.RoleArgs.class, Set.of(), Set.of(), Tools::readGrants));
    add(new ToolSpec("read_catalog_object", A, Caller.agent_analysis, ToolSpec.Kind.READ, Args.ReadCatalogObjectArgs.class, Set.of(), Set.of(ErrorCode.NOT_FOUND), Tools::readCatalogObject));
    add(new ToolSpec("read_query_telemetry", A, Caller.agent_analysis, ToolSpec.Kind.READ, Args.ReadQueryTelemetryArgs.class, Set.of(), Set.of(), Tools::readQueryTelemetry));
    add(new ToolSpec("read_job_runs", A, Caller.agent_analysis, ToolSpec.Kind.READ, Args.RoleArgs.class, Set.of(), Set.of(), Tools::readJobRuns));
    add(new ToolSpec("search_outcomes", A, Caller.agent_analysis, ToolSpec.Kind.READ, Args.SearchOutcomesArgs.class, Set.of(), Set.of(), Tools::searchOutcomes));
    add(new ToolSpec("preflight_check", R, Caller.agent_remediator, ToolSpec.Kind.READ, Args.OperationRefArgs.class, Set.of(), Set.of(ErrorCode.UNKNOWN_REF), Tools::preflightCheck));
    add(new ToolSpec("apply_right_size_role", R, Caller.agent_remediator, ToolSpec.Kind.WRITE, Args.OperationRefArgs.class, Set.of(),
        Set.of(ErrorCode.TIMEOUT, ErrorCode.RECONCILE_REQUIRED, ErrorCode.ABORTED_BEFORE_COMMIT, ErrorCode.LOCK_TIMEOUT,
            ErrorCode.PRECONDITION_FAILED, ErrorCode.PLAN_ALREADY_APPLIED, ErrorCode.VALIDATION_FAILED, ErrorCode.STATE_CONFLICT,
            ErrorCode.LEASE_HELD, ErrorCode.POLICY_DENIED, ErrorCode.UNKNOWN_REF), Tools::applyRightSizeRole));
    add(new ToolSpec("get_operation_status", R, Caller.agent_remediator, ToolSpec.Kind.READ, Args.OperationRefArgs.class,
        Set.of("applied", "not_applied", "in_flight"), Set.of(ErrorCode.STATE_CONFLICT, ErrorCode.UNKNOWN_REF), Tools::getOperationStatus));
    add(new ToolSpec("assert_privileges", V, Caller.agent_verifier, ToolSpec.Kind.READ, Args.FindingRefArgs.class, Set.of(), Set.of(ErrorCode.STATE_CONFLICT, ErrorCode.UNKNOWN_REF), Tools::assertPrivileges));
    add(new ToolSpec("run_negative_probe", V, Caller.agent_verifier, ToolSpec.Kind.PROBE, Args.RoleArgs.class, Set.of(), Set.of(ErrorCode.STATE_CONFLICT), Tools::runNegativeProbe));
    add(new ToolSpec("run_health_scenarios", V, Caller.agent_verifier, ToolSpec.Kind.PROBE, Args.HealthScenariosArgs.class, Set.of(), Set.of(ErrorCode.STATE_CONFLICT), Tools::runHealthScenarios));
    add(new ToolSpec("read_control_fingerprint", S, Caller.agent_supervisor, ToolSpec.Kind.READ, Args.FindingRefArgs.class, Set.of(), Set.of(ErrorCode.UNKNOWN_REF), Tools::readControlFingerprint));
    add(new ToolSpec("read_membership_grantor", S, Caller.agent_supervisor, ToolSpec.Kind.READ, Args.MembershipArgs.class, Set.of(), Set.of(), Tools::readMembershipGrantor));
    add(new ToolSpec("read_role_change_log", S, Caller.agent_supervisor, ToolSpec.Kind.READ, Args.RoleArgs.class, Set.of(), Set.of(), Tools::readRoleChangeLog));
  }

  public static ToolSpec get(String name) { return SPECS.get(name); }
  public static List<ToolSpec> all() { return List.copyOf(SPECS.values()); }
  public static List<String> namesFor(AgentId agent) { return SPECS.values().stream().filter(s -> s.allows(agent)).map(ToolSpec::name).toList(); }

  // ---- analysis ----

  static Handled readRoleGraph(RunContext run, Object a) {
    Args.RoleArgs args = (Args.RoleArgs) a;
    return Handled.of(run.session.target().snapshot(Caller.agent_analysis, args.role()), Labels.SIMULATED_PG);
  }

  static Handled readGrants(RunContext run, Object a) {
    Args.RoleArgs args = (Args.RoleArgs) a;
    Map<String, Object> snap = run.session.target().snapshot(Caller.agent_analysis, args.role());
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("role", args.role());
    d.put("direct_grants", snap.get("grants"));
    d.put("memberships", snap.get("memberships"));
    d.put("effective_roles", snap.get("effective_roles"));
    return Handled.of(d, Labels.SIMULATED_PG);
  }

  static Handled readCatalogObject(RunContext run, Object a) {
    Args.ReadCatalogObjectArgs args = (Args.ReadCatalogObjectArgs) a;
    try {
      TargetDatabase.CatalogObject o = run.session.target().describe(Caller.agent_analysis, args.schema(), args.name(), args.kind());
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("kind", o.kind());
      d.put("schema", o.schema());
      d.put("name", o.name());
      d.put("key", o.key());
      d.put("owner", o.owner());
      d.put("public_execute", o.publicExecute());
      d.put("requires", o.requires());
      d.put("exists", true);
      return Handled.of(d, Labels.SIMULATED_PG);
    } catch (TargetException e) {
      if (e.code() == TargetException.Code.NOT_FOUND) throw new ToolException(ToolError.of(ErrorCode.NOT_FOUND, e.getMessage()));
      throw e;
    }
  }

  static Handled readQueryTelemetry(RunContext run, Object a) {
    Args.ReadQueryTelemetryArgs args = (Args.ReadQueryTelemetryArgs) a;
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Seed.TelemetryRow r : run.session.stores().telemetry().rowsFor(args.role(), args.days())) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("object", r.object());
      m.put("command", r.command());
      m.put("calls", r.calls());
      m.put("last_seen_days_ago", r.lastSeenDaysAgo());
      rows.add(m);
    }
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("role", args.role());
    d.put("window_days", args.days());
    d.put("rows", rows);
    return Handled.of(d, Labels.TELEMETRY);
  }

  static Handled readJobRuns(RunContext run, Object a) {
    Args.RoleArgs args = (Args.RoleArgs) a;
    List<Map<String, Object>> jobs = new ArrayList<>();
    for (Seed.JobRun j : run.session.stores().telemetry().jobsFor(args.role())) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", j.name());
      m.put("schedule", j.schedule());
      m.put("calls", j.calls());
      m.put("last_run_days_ago", j.lastRunDaysAgo());
      m.put("last_status", j.lastStatus());
      jobs.add(m);
    }
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("role", args.role());
    d.put("telemetry_window_days", run.session.stores().telemetry().windowDays());
    d.put("jobs", jobs);
    return Handled.of(d, Labels.TELEMETRY);
  }

  static Handled searchOutcomes(RunContext run, Object a) {
    Args.SearchOutcomesArgs args = (Args.SearchOutcomesArgs) a;
    Findings.Entry f = run.session.stores().findings().get(run.findingId);
    List<Map<String, Object>> hits = new ArrayList<>();
    for (Corpus.Hit h : run.session.stores().corpus().search(args.terms(), f.finding.findingType(), null, 5)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("doc_id", h.doc().id());
      m.put("title", h.doc().title());
      m.put("kind", h.doc().kind());
      m.put("disposition", h.doc().disposition());
      m.put("score", h.score());
      hits.add(m);
    }
    return Handled.of(Map.of("terms", args.terms(), "hits", hits), Labels.CORPUS);
  }

  // ---- remediation ----

  static Operation operation(RunContext run, Args.OperationRefArgs args) {
    Operation op = run.session.stores().operations().get(args.id());
    if (op == null || !op.findingId().equals(run.findingId))
      throw new ToolException(ToolError.of(ErrorCode.UNKNOWN_REF, "operation " + args.operationRef() + " is not in this session"));
    return op;
  }

  static PlanExec exec(RunContext run, Operation op) {
    Plan p = run.session.stores().plans().byHash(op.planHash());
    if (p == null) throw new ToolException(ToolError.of(ErrorCode.UNKNOWN_REF, "no plan for hash " + op.planHash()));
    return p.exec();
  }

  static Handled preflightCheck(RunContext run, Object a) {
    Operation op = operation(run, (Args.OperationRefArgs) a);
    PlanExec exec = exec(run, op);
    TargetDatabase.Preflight p = run.session.target().preflight(Caller.agent_remediator, exec);
    op.preflightPassed(p.passed());
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("operation_id", op.id().toString());
    d.put("plan_hash", op.planHash());
    d.put("passed", p.passed());
    d.put("checks", p.checks());
    d.put("live_fingerprint", p.liveFingerprint());
    return Handled.of(d, Labels.SIMULATED_PG);
  }

  /** Takes a reference only. The broker loads the approved plan by hash; nothing model-authored reaches the write. */
  static Handled applyRightSizeRole(RunContext run, Object a) {
    Operation op = operation(run, (Args.OperationRefArgs) a);
    return ExecutionGuard.execute(run, op, exec(run, op));
  }

  static Handled getOperationStatus(RunContext run, Object a) {
    Operation op = operation(run, (Args.OperationRefArgs) a);
    PlanExec exec = exec(run, op);
    TargetDatabase.StatusResult st = run.session.target().operationStatus(Caller.agent_remediator, op.id());
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("operation_id", op.id().toString());
    d.put("in_flight", st.inFlight());
    d.put("found", st.found());
    d.put("fingerprint", st.fingerprint());
    d.put("ledger_row", st.row());
    String outcome;
    OpState cur = op.state();
    if (st.inFlight()) {
      outcome = "in_flight";
      inconclusive(run, op);
    } else if (st.found()) {
      outcome = "applied";
      if (cur.state() == OperationState.OUTCOME_UNKNOWN) {
        ExecutionGuard.applied(run.session, run, op, false, st.row(), st.fingerprint());
        d.put("reconciled", true);
      }
    } else if (cur.state() == OperationState.OUTCOME_UNKNOWN && exec.expectedBefore().equals(st.fingerprint())) {
      outcome = "not_applied";
      op.compareAndSet(cur, cur.failedNotApplied(true));
      ExecutionGuard.stateEvent(run.session, run, op, OperationState.FAILED_NOT_APPLIED,
          "Reconciled: no ledger row and the before-state is intact, so nothing committed. Retryable.");
    } else if (cur.state() == OperationState.OUTCOME_UNKNOWN) {
      outcome = "in_flight";
      inconclusive(run, op);
    } else {
      outcome = cur.state() == OperationState.APPLIED ? "applied" : "not_applied";
    }
    d.put("operation_state", op.state().state().name());
    return Handled.of(d, Labels.SIMULATED_PG, outcome);
  }

  private static void inconclusive(RunContext run, Operation op) {
    int n = op.reconciliations().incrementAndGet();
    if (n >= Operation.MAX_RECONCILIATIONS) {
      OpState cur = op.state();
      if (cur.state() == OperationState.OUTCOME_UNKNOWN) {
        op.compareAndSet(cur, cur.needsHuman());
        ExecutionGuard.stateEvent(run.session, run, op, OperationState.NEEDS_HUMAN, "Three inconclusive reconciliations. A human must look.");
      }
    }
  }

  // ---- verification: results are computed and stored before the verifier's turn ----

  static VerificationRecord stored(RunContext run) {
    Findings.Entry e = run.session.stores().findings().get(run.findingId);
    if (e.verification == null) throw new ToolException(ToolError.of(ErrorCode.STATE_CONFLICT, "verification results have not been computed"));
    return e.verification;
  }

  static Handled assertPrivileges(RunContext run, Object a) {
    Args.FindingRefArgs args = (Args.FindingRefArgs) a;
    if (!args.id().equals(run.findingId)) throw new ToolException(ToolError.of(ErrorCode.UNKNOWN_REF, "finding is not this run's finding"));
    VerificationRecord v = stored(run);
    return Handled.of(Map.of("operation_id", v.operationId().toString(), "checks", v.assertions(),
        "all_passed", v.assertions().stream().allMatch(VerificationRecord.Check::passed)), Labels.SIMULATED_PG);
  }

  static Handled runNegativeProbe(RunContext run, Object a) {
    Args.RoleArgs args = (Args.RoleArgs) a;
    if (!args.role().equals(run.session.target().appRole()))
      throw new ToolException(ToolError.of(ErrorCode.STATE_CONFLICT, "probes run only as " + run.session.target().appRole()));
    VerificationRecord v = stored(run);
    return Handled.of(Map.of("role", args.role(), "checks", v.probes(),
        "all_passed", v.probes().stream().allMatch(VerificationRecord.Check::passed)), Labels.SIMULATED_PG);
  }

  static Handled runHealthScenarios(RunContext run, Object a) {
    Args.HealthScenariosArgs args = (Args.HealthScenariosArgs) a;
    for (String s : args.scenarios()) if (!run.session.appSimulator().knows(s)) throw new ToolException(ToolError.invalid("scenarios", "unknown scenario " + s));
    VerificationRecord v = stored(run);
    List<VerificationRecord.Check> checks = v.scenarios().stream().filter(c -> args.scenarios().contains(c.name())).toList();
    return Handled.of(Map.of("role", args.role(), "checks", checks, "runs_each", adr.target.AppSimulator.RUNS,
        "all_passed", checks.stream().allMatch(VerificationRecord.Check::passed)), Labels.APP_SIM);
  }

  // ---- supervision ----

  static Handled readControlFingerprint(RunContext run, Object a) {
    Args.FindingRefArgs args = (Args.FindingRefArgs) a;
    if (!args.id().equals(run.findingId)) throw new ToolException(ToolError.of(ErrorCode.UNKNOWN_REF, "finding is not this run's finding"));
    Findings.Entry e = run.session.stores().findings().get(run.findingId);
    String live = run.session.target().fingerprint(Caller.agent_supervisor, e.finding.subjectRole());
    Map<String, Object> snap = run.session.target().snapshot(Caller.agent_supervisor, e.finding.subjectRole());
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("role", e.finding.subjectRole());
    d.put("sealed_fingerprint", e.sealedFingerprint);
    d.put("live_fingerprint", live);
    d.put("changed", e.sealedFingerprint != null && !e.sealedFingerprint.equals(live));
    DriftObservation obs = e.drift;
    d.put("diff", obs != null ? obs.diff() : (e.sealedSnapshot == null ? Map.of() : Fingerprint.diff(e.sealedSnapshot, snap)));
    d.put("live_snapshot", snap);
    return Handled.of(d, Labels.SIMULATED_PG);
  }

  static Handled readMembershipGrantor(RunContext run, Object a) {
    Args.MembershipArgs args = (Args.MembershipArgs) a;
    PgState.Membership m = run.session.target().state().membership(args.role(), args.member());
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("role", args.role());
    d.put("member", args.member());
    d.put("present", m != null);
    d.put("grantor", m == null ? null : m.grantor());
    d.put("admin_option", m != null && m.adminOption());
    return Handled.of(d, Labels.SIMULATED_PG);
  }

  static Handled readRoleChangeLog(RunContext run, Object a) {
    Args.RoleArgs args = (Args.RoleArgs) a;
    return Handled.of(Map.of("role", args.role(), "rows", run.session.stores().audit().rowsMentioning(args.role())), Labels.AUDIT);
  }
}
