package adr.target;

import adr.broker.PlanHash;
import adr.domain.GrantSpec;
import adr.domain.PlanExec;
import adr.domain.Require;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An honest model of the slice of PostgreSQL this finding touches. One immutable PgState in an
 * AtomicReference plus one ReentrantLock standing in for the advisory lock. No SQL is parsed, there is no
 * MVCC or network, and grantor rules are simplified; the banner says so.
 */
public final class SimulatedPgTarget implements TargetDatabase {
  public static final Set<String> ALLOWED_PRIVILEGES = Set.of("USAGE", "SELECT", "INSERT", "UPDATE", "DELETE", "EXECUTE");
  public static final int MAX_GRANTS = 12;

  private static final EnumMap<Caller, Set<String>> CAPABILITIES = new EnumMap<>(Caller.class);

  static {
    CAPABILITIES.put(Caller.agent_analysis, Set.of("snapshot", "fingerprint", "describe"));
    CAPABILITIES.put(Caller.agent_remediator, Set.of("preflight", "apply", "operationStatus"));
    CAPABILITIES.put(Caller.agent_verifier, Set.of("snapshot", "fingerprint", "operationStatus", "attempt"));
    CAPABILITIES.put(Caller.agent_supervisor, Set.of("snapshot", "fingerprint"));
    CAPABILITIES.put(Caller.orders_app, Set.of("attempt"));
    CAPABILITIES.put(Caller.dba_oncall, Set.of("grantMembership"));
  }

  private final AtomicReference<PgState> state;
  private final ReentrantLock lock = new ReentrantLock();
  private final String appRole;
  private final String ownerRole;
  private final long lockTimeoutMillis;

  public SimulatedPgTarget(PgState initial, String appRole, String ownerRole) {
    this(initial, appRole, ownerRole, 3000);
  }

  public SimulatedPgTarget(PgState initial, String appRole, String ownerRole, long lockTimeoutMillis) {
    this.state = new AtomicReference<>(initial);
    this.appRole = appRole;
    this.ownerRole = ownerRole;
    this.lockTimeoutMillis = lockTimeoutMillis;
  }

  public PgState state() { return state.get(); }
  public String appRole() { return appRole; }
  public String ownerRole() { return ownerRole; }

  private static void allow(Caller as, String method) {
    if (as == null) throw new IllegalArgumentException("caller is required");
    if (!CAPABILITIES.getOrDefault(as, Set.of()).contains(method))
      throw new TargetException(TargetException.Code.NOT_ALLOWED, as + " may not call " + method);
  }

  @Override public Map<String, Object> snapshot(Caller as, String role) {
    allow(as, "snapshot");
    Require.matches(role, Require.ROLE_NAME, "role");
    return Fingerprint.snapshot(state.get(), role);
  }

  @Override public String fingerprint(Caller as, String role) {
    allow(as, "fingerprint");
    Require.matches(role, Require.ROLE_NAME, "role");
    return Fingerprint.of(state.get(), role);
  }

  @Override public CatalogObject describe(Caller as, String schema, String name, String kind) {
    allow(as, "describe");
    Require.nonBlank(schema, "schema");
    Require.nonBlank(name, "name");
    Require.nonBlank(kind, "kind");
    String key = kind.equals("schema") ? schema : schema + "." + name;
    PgState.DbObject o = state.get().objects().get(key);
    if (o == null || !o.kind().equals(kind))
      throw new TargetException(TargetException.Code.NOT_FOUND, kind + " " + key + " does not exist");
    return new CatalogObject(o.kind(), schema, name, o.key(), o.owner(), o.publicExecute(), o.requires());
  }

  @Override public Preflight preflight(Caller as, PlanExec exec) {
    allow(as, "preflight");
    Require.nonNull(exec, "exec");
    PgState s = state.get();
    List<Check> checks = new ArrayList<>(validate(exec, s));
    String live = Fingerprint.of(s, exec.targetRole());
    boolean precondition = live.equals(exec.expectedBefore());
    checks.add(new Check("expected_before", precondition, precondition
        ? "live fingerprint equals the plan's expected_before"
        : "live fingerprint differs from the plan's expected_before: the plan is stale"));
    boolean membership = s.membership(exec.ownerRole(), exec.targetRole()) != null;
    checks.add(new Check("membership_present", membership, membership
        ? exec.targetRole() + " is currently a member of " + exec.ownerRole()
        : exec.targetRole() + " is not a member of " + exec.ownerRole()));
    boolean all = checks.stream().allMatch(Check::passed);
    return new Preflight(all, checks, live);
  }

  /** The validation list is copied from the production procedure on purpose. */
  private List<Check> validate(PlanExec exec, PgState s) {
    List<Check> checks = new ArrayList<>();
    boolean target = exec.targetRole().equals(appRole);
    checks.add(new Check("target_role", target, target ? "target is the configured application role" : "target " + exec.targetRole() + " is not " + appRole));
    boolean owner = exec.ownerRole().equals(ownerRole);
    checks.add(new Check("owner_role", owner, owner ? "owner is " + ownerRole : "owner " + exec.ownerRole() + " is not " + ownerRole));
    boolean size = exec.grants().size() <= MAX_GRANTS;
    checks.add(new Check("max_grants", size, exec.grants().size() + " grants, limit " + MAX_GRANTS));
    List<String> bad = new ArrayList<>();
    Set<String> schemasNeedingUsage = new HashSet<>();
    Set<String> usageGranted = new HashSet<>();
    for (GrantSpec g : exec.grants()) {
      if (!ALLOWED_PRIVILEGES.contains(g.privilege())) bad.add(g.privilege() + " is not in the closed privilege list");
      PgState.DbObject o = s.objects().get(g.object());
      if (o == null) { bad.add(g.object() + " does not exist"); continue; }
      if (!o.kind().equals(g.kind())) bad.add(g.object() + " is a " + o.kind() + ", not a " + g.kind());
      if (!o.owner().equals(ownerRole)) bad.add(g.object() + " is owned by " + o.owner() + ", not " + ownerRole);
      if (o.kind().equals("schema")) { if (g.privilege().equals("USAGE")) usageGranted.add(o.key()); }
      else schemasNeedingUsage.add(o.schema());
    }
    checks.add(new Check("objects_and_privileges", bad.isEmpty(), bad.isEmpty() ? "every object exists, is owned by " + ownerRole + " and uses an allowed privilege" : String.join("; ", bad)));
    schemasNeedingUsage.removeAll(usageGranted);
    checks.add(new Check("schema_usage_closure", schemasNeedingUsage.isEmpty(), schemasNeedingUsage.isEmpty()
        ? "USAGE is granted on every schema the plan touches" : "missing USAGE on schema " + schemasNeedingUsage));
    return checks;
  }

  @Override public ApplyResult apply(Caller as, UUID operationId, PlanExec exec, CommitHook hook) {
    allow(as, "apply");
    Require.nonNull(operationId, "operation_id");
    Require.nonNull(exec, "exec");
    Require.nonNull(hook, "hook");
    if (!tryLock()) throw new TargetException(TargetException.Code.LOCK_TIMEOUT, "target lock busy after " + lockTimeoutMillis + "ms");
    try {
      PgState s = state.get();
      PgState.LedgerRow existing = s.ledger().get(operationId);
      if (existing != null) return new ApplyResult(true, existing, Fingerprint.of(s, exec.targetRole()));
      String planHash = PlanHash.of(exec);
      if (s.appliedPlanHashes().contains(planHash))
        throw new TargetException(TargetException.Code.PLAN_ALREADY_APPLIED, "plan " + planHash.substring(0, 8) + " was applied under another operation");
      List<Check> failed = validate(exec, s).stream().filter(c -> !c.passed()).toList();
      if (!failed.isEmpty())
        throw new TargetException(TargetException.Code.VALIDATION_FAILED, failed.stream().map(c -> c.name() + ": " + c.detail()).reduce((a, b) -> a + "; " + b).orElse(""));
      String before = Fingerprint.of(s, exec.targetRole());
      if (!before.equals(exec.expectedBefore()))
        throw new TargetException(TargetException.Code.PRECONDITION_FAILED, "live fingerprint differs from expected_before");
      PgState changed = s.plusGrants(exec.targetRole(), exec.grants()).minusMembership(exec.ownerRole(), exec.targetRole());
      String after = Fingerprint.of(changed, exec.targetRole());
      PgState.LedgerRow row = new PgState.LedgerRow(operationId, planHash, before, after, exec.grants(),
          exec.ownerRole() + " -> " + exec.targetRole(), Instant.now().toString());
      PgState draft = changed.plusLedger(row);
      hook.beforeCommit();     // chaos may throw AbortedBeforeCommit: the draft is discarded
      state.set(draft);        // the commit: one reference swap, change and marker together
      hook.afterCommit();      // chaos may throw ResponseLost: the caller sees a timeout
      return new ApplyResult(false, row, after);
    } finally {
      lock.unlock();
    }
  }

  @Override public StatusResult operationStatus(Caller as, UUID operationId) {
    allow(as, "operationStatus");
    Require.nonNull(operationId, "operation_id");
    if (!tryLock()) return StatusResult.stillInFlight();
    try {
      PgState s = state.get();
      PgState.LedgerRow row = s.ledger().get(operationId);
      return new StatusResult(false, row != null, Fingerprint.of(s, appRole), row);
    } finally {
      lock.unlock();
    }
  }

  @Override public void grantMembership(Caller as, String role, String member) {
    allow(as, "grantMembership");
    Require.matches(role, Require.ROLE_NAME, "role");
    Require.matches(member, Require.ROLE_NAME, "member");
    if (!tryLock()) throw new TargetException(TargetException.Code.LOCK_TIMEOUT, "target lock busy");
    try {
      PgState s = state.get();
      PgState.Membership held = s.membership(role, as.name());
      boolean mayGrant = (held != null && held.adminOption()) || s.effectiveRoles(as.name()).contains(role) && role.equals(as.name());
      if (!mayGrant) throw new TargetException(TargetException.Code.NOT_ALLOWED, as + " holds no admin option on " + role);
      if (!s.roles().containsKey(member)) throw new TargetException(TargetException.Code.NOT_FOUND, "role " + member + " does not exist");
      state.set(s.plusMembership(new PgState.Membership(role, member, as.name(), false)));
    } finally {
      lock.unlock();
    }
  }

  @Override public Privileges.ProbeResult attempt(Caller as, String role, Privileges.SqlAction action) {
    allow(as, "attempt");
    Require.matches(role, Require.ROLE_NAME, "role");
    Require.nonNull(action, "action");
    if (as == Caller.agent_verifier && !role.equals(appRole))
      throw new TargetException(TargetException.Code.NOT_ALLOWED, "the verifier may probe only as " + appRole);
    if (as == Caller.orders_app && !role.equals(as.name()))
      throw new TargetException(TargetException.Code.NOT_ALLOWED, as + " may act only as itself");
    return Privileges.attempt(state.get(), role, action);
  }

  private boolean tryLock() {
    try {
      return lock.tryLock(lockTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
