package adr.broker;

import adr.domain.Decision;
import adr.domain.DriftObservation;
import adr.domain.Evidence;
import adr.domain.GrantSpec;
import adr.domain.Operation;
import adr.domain.OperationState;
import adr.domain.PlanExec;
import adr.domain.VerificationRecord;
import adr.stores.Findings;
import adr.stores.Seed;
import adr.target.AppSimulator;
import adr.target.PgState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure functions, each returning pass or a list of named failures. A guard failure on a recorded decision is
 * returned to the runner exactly as it would be to a live model.
 */
public final class Guards {
  private Guards() {}

  public static final List<String> NAMES = List.of("schema", "plan_lint", "completeness", "justification",
      "scenario_coverage", "citation", "decision_legality", "verdict", "reopen_legality");

  public record Result(String guard, boolean ok, List<String> failures) {
    static Result pass(String g) { return new Result(g, true, List.of()); }
    static Result of(String g, List<String> f) { return new Result(g, f.isEmpty(), List.copyOf(f)); }
  }

  /** The guards that apply to a decision, in order. The first failure names the corrective trigger. */
  public static List<Result> check(RunContext run, Decision d) {
    List<Result> out = new ArrayList<>();
    Function<String, Evidence> lookup = id -> run.session.stores().evidence().get(id);
    switch (d) {
      case Decision.Analysis a -> {
        out.add(completeness(a, run.mandatoryDocs));
        out.add(justification(a.retainGrants(), lookup));
        out.add(scenarioCoverage(a, run.session.stores().corpus()::get));
        out.add(citation(allEvidence(a), run.producedEvidence));
      }
      case Decision.Remediation r -> {
        Operation op = run.operationId == null ? null : run.session.stores().operations().get(run.operationId);
        out.add(decisionLegality(r, op));
        out.add(citation(r.evidence(), run.producedEvidence));
      }
      case Decision.Verification v -> {
        Findings.Entry e = run.session.stores().findings().get(run.findingId);
        out.add(verdict(v, e.verification));
        out.add(citation(v.evidence(), run.producedEvidence));
      }
      case Decision.Supervision s -> {
        Findings.Entry e = run.session.stores().findings().get(run.findingId);
        out.add(reopenLegality(s, e.drift));
        out.add(citation(s.evidence(), run.producedEvidence));
      }
    }
    return out;
  }

  static List<String> allEvidence(Decision.Analysis a) {
    List<String> ids = new ArrayList<>(a.evidence());
    for (Decision.RetainGrant g : a.retainGrants()) ids.addAll(g.evidence());
    return ids;
  }

  /** Unknown objects, objects not owned by the owner, missing schema USAGE, over 12 grants, unknown scenarios. */
  public static Result planLint(PlanExec exec, PgState state, AppSimulator sim) {
    List<String> f = new ArrayList<>();
    Set<String> schemasNeedingUsage = new HashSet<>();
    Set<String> usage = new HashSet<>();
    for (GrantSpec g : exec.grants()) {
      PgState.DbObject o = state.objects().get(g.object());
      if (o == null) { f.add("unknown object " + g.object()); continue; }
      if (!o.owner().equals(exec.ownerRole())) f.add(g.object() + " is owned by " + o.owner() + ", not " + exec.ownerRole());
      if (!o.kind().equals(g.kind())) f.add(g.object() + " is a " + o.kind() + ", not a " + g.kind());
      if (o.kind().equals("schema")) { if (g.privilege().equals("USAGE")) usage.add(o.key()); }
      else schemasNeedingUsage.add(o.schema());
    }
    schemasNeedingUsage.removeAll(usage);
    for (String s : schemasNeedingUsage) f.add("missing USAGE on schema " + s);
    if (exec.grants().size() > 12) f.add("more than 12 grants");
    for (String s : exec.scenarios()) if (!sim.knows(s)) f.add("unknown scenario " + s);
    return Result.of("plan_lint", f);
  }

  /** Any failed or rolled-back outcome of the same finding type left unaddressed. */
  public static Result completeness(Decision.Analysis a, List<Seed.CorpusDoc> mandatory) {
    List<String> f = new ArrayList<>();
    Set<String> addressed = new HashSet<>();
    for (Decision.AddressedOutcome o : a.addressedOutcomes()) addressed.add(o.docId());
    for (Seed.CorpusDoc d : mandatory) {
      if (d.isFailedOutcome() && !addressed.contains(d.id())) f.add("outcome " + d.id() + " (" + d.disposition() + ") is not addressed");
    }
    return Result.of("completeness", f);
  }

  /**
   * Each retained grant must be supported by a cited evidence payload: a telemetry row with that object and
   * command, or an outcome's required_privileges entry. Schema USAGE is justified by any cited object in the schema.
   */
  public static Result justification(List<Decision.RetainGrant> grants, Function<String, Evidence> lookup) {
    List<String> f = new ArrayList<>();
    for (Decision.RetainGrant g : grants) {
      boolean supported = false;
      for (String id : g.evidence()) {
        Evidence e = lookup.apply(id);
        if (e == null) continue;
        if (mentions(e.data(), g.object(), g.privilege(), g.kind().equals("schema"))) { supported = true; break; }
      }
      if (!supported) f.add(g.privilege() + " on " + g.object() + " appears in no cited telemetry row or outcome");
    }
    return Result.of("justification", f);
  }

  @SuppressWarnings("unchecked")
  static boolean mentions(Object data, String object, String privilege, boolean schemaUsage) {
    if (!(data instanceof Map<?, ?> m)) return false;
    Object rows = m.get("rows");
    if (rows instanceof List<?> l) {
      for (Object r : l) {
        if (r instanceof Map<?, ?> row) {
          String obj = String.valueOf(row.get("object"));
          String cmd = String.valueOf(row.get("command"));
          if (schemaUsage ? obj.startsWith(object + ".") : obj.equals(object) && cmd.equals(privilege)) return true;
        }
      }
    }
    Object req = m.get("required_privileges");
    if (req instanceof List<?> l) {
      for (Object r : l) {
        if (r instanceof Map<?, ?> row) {
          String obj = String.valueOf(row.get("object"));
          String priv = String.valueOf(row.get("privilege"));
          if (schemaUsage ? obj.startsWith(object + ".") : obj.equals(object) && priv.equals(privilege)) return true;
        }
      }
    }
    Object doc = m.get("doc");
    return doc != null && mentions(doc, object, privilege, schemaUsage);
  }

  /** Every incorporated outcome's failed scenario must be in the plan's scenarios. */
  public static Result scenarioCoverage(Decision.Analysis a, Function<String, Seed.CorpusDoc> corpus) {
    List<String> f = new ArrayList<>();
    for (Decision.AddressedOutcome o : a.addressedOutcomes()) {
      if (!"incorporated".equals(o.disposition())) continue;
      Seed.CorpusDoc d = corpus.apply(o.docId());
      if (d == null) { f.add("unknown outcome " + o.docId()); continue; }
      if (d.failedScenario() != null && !a.healthScenarios().contains(d.failedScenario()))
        f.add("scenario " + d.failedScenario() + " from " + d.id() + " is not covered");
    }
    return Result.of("scenario_coverage", f);
  }

  /** Cited ids must have been produced in this run. */
  public static Result citation(List<String> ids, Set<String> produced) {
    List<String> f = new ArrayList<>();
    for (String id : ids) if (!produced.contains(id)) f.add("evidence " + id + " was not produced in this run");
    return Result.of("citation", f);
  }

  public static Result decisionLegality(Decision.Remediation r, Operation op) {
    List<String> f = new ArrayList<>();
    if (op == null) f.add("no operation in this run");
    else if (r.verdict() == Decision.Remediation.Verdict.applied && op.state().state() != OperationState.APPLIED)
      f.add("decision applied while the operation is " + op.state().state());
    return Result.of("decision_legality", f);
  }

  /** The verifier cannot pass what the checks failed. */
  public static Result verdict(Decision.Verification v, VerificationRecord stored) {
    List<String> f = new ArrayList<>();
    if (stored == null) f.add("no stored verification results");
    else if (v.verdict() == Decision.Verification.Verdict.pass && !stored.allPassed()) {
      for (VerificationRecord.Check c : stored.failed()) f.add("check " + c.name() + " failed: " + c.detail());
    }
    return Result.of("verdict", f);
  }

  /** Reopen requires a recorded fingerprint difference. */
  public static Result reopenLegality(Decision.Supervision s, DriftObservation drift) {
    List<String> f = new ArrayList<>();
    if (s.verdict() == Decision.Supervision.Verdict.reopen && (drift == null || !drift.hasDifference()))
      f.add("reopen without a recorded fingerprint difference");
    return Result.of("reopen_legality", f);
  }

  public static Map<String, Object> describe(List<Result> results) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (Result r : results) m.put(r.guard(), r.ok() ? "PASS" : r.failures());
    return m;
  }
}
