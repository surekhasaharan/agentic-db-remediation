package adr.workflow;

import adr.app.Session;
import adr.domain.GrantSpec;
import adr.domain.Operation;
import adr.domain.PlanExec;
import adr.domain.VerificationRecord;
import adr.domain.VerificationRecord.Check;
import adr.stores.Findings;
import adr.target.AppSimulator;
import adr.target.Caller;
import adr.target.PgState;
import adr.target.Privileges;
import adr.target.TargetDatabase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic code computes the facts before the verifier agent sees them: six assertions, two negative
 * probes and the plan's health scenarios, all evaluated against the simulated privilege engine.
 */
public final class Verification {
  private Verification() {}

  public static VerificationRecord compute(Session s, Findings.Entry entry, Operation op, PlanExec plan) {
    String role = plan.targetRole();
    String owner = plan.ownerRole();
    PgState state = s.target().state();
    List<Check> a = new ArrayList<>();

    boolean s1 = !state.effectiveRoles(role).contains(owner);
    a.add(new Check("S1_not_member_of_owner", s1, s1 ? role + " is no longer a member of " + owner : role + " still reaches " + owner));

    Set<String> memberships = new HashSet<>();
    for (PgState.Membership m : state.memberships()) if (m.member().equals(role)) memberships.add(m.role());
    a.add(new Check("S2_no_unplanned_memberships", memberships.isEmpty(), memberships.isEmpty() ? "no memberships remain" : "unplanned memberships: " + memberships));

    Set<String> live = new HashSet<>();
    for (PgState.Grant g : state.grants()) if (g.grantee().equals(role)) live.add(g.privilege() + " " + g.object());
    Set<String> planned = new HashSet<>();
    for (GrantSpec g : plan.grants()) planned.add(g.privilege() + " " + g.object());
    boolean s3 = live.equals(planned);
    a.add(new Check("S3_direct_grants_equal_plan", s3, s3 ? live.size() + " direct grants, exactly the plan" : "live " + live + " vs plan " + planned));

    List<String> ownerOnly = new ArrayList<>();
    for (PgState.DbObject o : state.objects().values()) {
      if (o.kind().equals("schema") && s.target().attempt(Caller.agent_verifier, role, new Privileges.SqlAction("CREATE", o.key())).ok()) ownerOnly.add("CREATE in " + o.key());
      if (o.kind().equals("table") && s.target().attempt(Caller.agent_verifier, role, new Privileges.SqlAction("TRUNCATE", o.key())).ok()) ownerOnly.add("TRUNCATE " + o.key());
    }
    a.add(new Check("S4_owner_capabilities_gone", ownerOnly.isEmpty(), ownerOnly.isEmpty() ? "CREATE in schema and TRUNCATE on every table return 42501" : "still allowed: " + ownerOnly));

    PgState.Role r = state.roles().get(role);
    boolean s5 = r != null && r.attributes().isEmpty();
    a.add(new Check("S5_attributes_unprivileged", s5, s5 ? "no role attributes" : "attributes: " + (r == null ? "role missing" : r.attributes())));

    TargetDatabase.StatusResult st = s.target().operationStatus(Caller.agent_verifier, op.id());
    String liveFp = s.target().fingerprint(Caller.agent_verifier, role);
    boolean s6 = st.found() && st.row().after().equals(liveFp);
    a.add(new Check("S6_fingerprint_equals_ledger_after", s6, s6 ? "live fingerprint equals the ledger's after-state" : "live fingerprint differs from the ledger's after-state"));

    List<Check> p = new ArrayList<>();
    Privileges.ProbeResult n1 = s.target().attempt(Caller.agent_verifier, role, new Privileges.SqlAction("CREATE", plan.grants().get(0).schema()));
    p.add(new Check("N1_create_table_denied", !n1.ok(), !n1.ok() ? "CREATE TABLE orders.__probe -> " + n1.sqlstate() : "CREATE TABLE was allowed"));
    Privileges.ProbeResult n2 = s.target().attempt(Caller.agent_verifier, role, new Privileges.SqlAction("DROP", "orders.ledger_archive"));
    p.add(new Check("N2_drop_table_denied", !n2.ok(), !n2.ok() ? "DROP TABLE orders.ledger_archive -> " + n2.sqlstate() : "DROP TABLE was allowed"));

    List<Check> h = new ArrayList<>();
    for (String name : plan.scenarios()) {
      AppSimulator.ScenarioResult sr = s.appSimulator().run(s.target(), role, name);
      h.add(new Check(name, sr.passed(), sr.passed() ? "passed " + sr.runs() + " of " + sr.runs() + " runs" : String.join("; ", sr.failures())));
    }
    VerificationRecord rec = VerificationRecord.of(op.id(), a, p, h, Instant.now().toString());
    entry.verification = rec;
    return rec;
  }
}
