package adr.target;

import adr.broker.PlanHash;
import adr.domain.GrantSpec;
import adr.domain.PlanExec;
import adr.stores.Seed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedPgTargetTest {

  static final UUID FINDING = UUID.fromString("7d1f4c1e-9a1b-4c3e-8f2a-1b2c3d4e5f60");

  static SimulatedPgTarget target() {
    Seed seed = Seed.load();
    return new SimulatedPgTarget(PgState.fromSeed(seed.pgState), "orders_app", "orders_owner");
  }

  static AppSimulator sim() { return new AppSimulator(Seed.load().scenarios); }

  static List<GrantSpec> v1() {
    return List.of(
        new GrantSpec("USAGE", "schema", "orders"),
        new GrantSpec("SELECT", "table", "orders.customers"),
        new GrantSpec("SELECT", "table", "orders.orders"),
        new GrantSpec("INSERT", "table", "orders.orders"),
        new GrantSpec("UPDATE", "table", "orders.orders"),
        new GrantSpec("SELECT", "table", "orders.order_items"),
        new GrantSpec("INSERT", "table", "orders.order_items"));
  }

  static List<GrantSpec> v2() {
    List<GrantSpec> g = new java.util.ArrayList<>(v1());
    g.add(new GrantSpec("INSERT", "table", "orders.ledger_archive"));
    g.add(new GrantSpec("EXECUTE", "function", "orders.close_period(date)"));
    return g;
  }

  static PlanExec exec(SimulatedPgTarget t, List<GrantSpec> grants, List<String> scenarios) {
    return new PlanExec(FINDING, "orders_app", "orders_owner", grants, scenarios, t.fingerprint(Caller.agent_analysis, "orders_app"));
  }

  /** The retrieval moment is not staged: Plan v1 genuinely fails month_end_close and Plan v2 passes it. */
  @Test
  void planV1FailsMonthEndAndV2Passes() {
    SimulatedPgTarget t = target();
    AppSimulator sim = sim();
    // Before the fix the app role owns everything through the membership, including destructive actions.
    assertTrue(t.attempt(Caller.orders_app, "orders_app", new Privileges.SqlAction("TRUNCATE", "orders.orders")).ok());
    assertTrue(sim.run(t, "orders_app", "month_end_close").passed());

    PlanExec v1 = exec(t, v1(), List.of("place_order", "read_order"));
    t.apply(Caller.agent_remediator, UUID.randomUUID(), v1, CommitHook.NONE);
    assertTrue(sim.run(t, "orders_app", "place_order").passed());
    assertTrue(sim.run(t, "orders_app", "read_order").passed());
    AppSimulator.ScenarioResult monthEnd = sim.run(t, "orders_app", "month_end_close");
    assertFalse(monthEnd.passed(), "v1 must fail month_end_close");
    assertTrue(monthEnd.failures().get(0).contains("42501"), monthEnd.failures().toString());
    assertTrue(monthEnd.failures().get(0).contains("close_period"), monthEnd.failures().toString());
    // Rule 4: with EXECUTE granted but not the function's own requirement, the failure is inside close_period.
    SimulatedPgTarget t3 = target();
    List<GrantSpec> executeOnly = new java.util.ArrayList<>(v1());
    executeOnly.add(new GrantSpec("EXECUTE", "function", "orders.close_period(date)"));
    t3.apply(Caller.agent_remediator, UUID.randomUUID(), exec(t3, executeOnly, List.of("place_order")), CommitHook.NONE);
    AppSimulator.ScenarioResult inside = sim.run(t3, "orders_app", "month_end_close");
    assertFalse(inside.passed());
    assertTrue(inside.failures().get(0).contains("inside orders.close_period(date)"), inside.failures().toString());
    assertTrue(inside.failures().get(0).contains("ledger_archive"), inside.failures().toString());
    // Owner-only capabilities are gone.
    assertEquals("42501", t.attempt(Caller.agent_verifier, "orders_app", new Privileges.SqlAction("TRUNCATE", "orders.orders")).sqlstate());
    assertEquals("42501", t.attempt(Caller.agent_verifier, "orders_app", new Privileges.SqlAction("CREATE", "orders")).sqlstate());
    assertEquals("42501", t.attempt(Caller.agent_verifier, "orders_app", new Privileges.SqlAction("DROP", "orders.ledger_archive")).sqlstate());
    assertFalse(t.state().effectiveRoles("orders_app").contains("orders_owner"));

    SimulatedPgTarget t2 = target();
    PlanExec v2 = exec(t2, v2(), List.of("place_order", "read_order", "month_end_close"));
    t2.apply(Caller.agent_remediator, UUID.randomUUID(), v2, CommitHook.NONE);
    for (String s : List.of("place_order", "read_order", "month_end_close"))
      assertTrue(sim.run(t2, "orders_app", s).passed(), s);
    assertNotEquals(PlanHash.of(v1), PlanHash.of(v2));
  }

  /** Layer three: the ledger under the lock. */
  @Test
  void ledgerRejectsSecondApply() {
    SimulatedPgTarget t = target();
    PlanExec v2 = exec(t, v2(), List.of("place_order"));
    UUID op = UUID.randomUUID();
    TargetDatabase.ApplyResult first = t.apply(Caller.agent_remediator, op, v2, CommitHook.NONE);
    assertFalse(first.alreadyApplied());
    TargetDatabase.ApplyResult second = t.apply(Caller.agent_remediator, op, v2, CommitHook.NONE);
    assertTrue(second.alreadyApplied());
    assertEquals(first.row(), second.row());
    assertEquals(1, t.state().ledger().size());
    // A different operation for the same plan hash is refused too.
    TargetException e = assertThrows(TargetException.class, () -> t.apply(Caller.agent_remediator, UUID.randomUUID(), v2, CommitHook.NONE));
    assertEquals(TargetException.Code.PLAN_ALREADY_APPLIED, e.code());
    assertEquals(1, t.state().ledger().size());
  }

  /** I6: the change and its marker commit or vanish together. */
  @Test
  void abortBeforeCommitLeavesNoTrace() {
    SimulatedPgTarget t = target();
    PgState before = t.state();
    PlanExec v2 = exec(t, v2(), List.of("place_order"));
    UUID op = UUID.randomUUID();
    CommitHook abort = new CommitHook() {
      @Override public void beforeCommit() { throw new TargetException.AbortedBeforeCommit("injected"); }
      @Override public void afterCommit() {}
    };
    TargetException e = assertThrows(TargetException.class, () -> t.apply(Caller.agent_remediator, op, v2, abort));
    assertEquals(TargetException.Code.ABORTED_BEFORE_COMMIT, e.code());
    assertSame(before, t.state(), "state reference unchanged");
    assertTrue(t.state().ledger().isEmpty());
    assertEquals(v2.expectedBefore(), t.fingerprint(Caller.agent_analysis, "orders_app"));
    TargetDatabase.ApplyResult retry = t.apply(Caller.agent_remediator, op, v2, CommitHook.NONE);
    assertFalse(retry.alreadyApplied());
    assertEquals(1, t.state().ledger().size());
    assertEquals(op, t.state().ledger().keySet().iterator().next());
  }

  /** I9: status takes the same lock, so it returns in_flight rather than a false "not found". */
  @Test
  void statusWaitsForInFlightWrite() throws Exception {
    Seed seed = Seed.load();
    SimulatedPgTarget t = new SimulatedPgTarget(PgState.fromSeed(seed.pgState), "orders_app", "orders_owner", 200);
    PlanExec v2 = exec(t, v2(), List.of("place_order"));
    UUID op = UUID.randomUUID();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CommitHook hold = new CommitHook() {
      @Override public void beforeCommit() {
        holding.countDown();
        try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      }
      @Override public void afterCommit() {}
    };
    AtomicReference<TargetDatabase.ApplyResult> result = new AtomicReference<>();
    Thread writer = new Thread(() -> result.set(t.apply(Caller.agent_remediator, op, v2, hold)));
    writer.start();
    holding.await();
    TargetDatabase.StatusResult during = t.operationStatus(Caller.agent_remediator, op);
    assertTrue(during.inFlight(), "status must wait, never read the in-flight write");
    assertFalse(during.found());
    release.countDown();
    writer.join(5000);
    assertNotNull(result.get());
    TargetDatabase.StatusResult after = t.operationStatus(Caller.agent_remediator, op);
    assertFalse(after.inFlight());
    assertTrue(after.found());
    assertEquals(result.get().fingerprintAfter(), after.fingerprint());
  }

  /** I1 at the target: per-agent permissions are enforced by the target as well as by the broker. */
  @Test
  void callerCapabilitiesAreEnforced() {
    SimulatedPgTarget t = target();
    PlanExec v2 = exec(t, v2(), List.of("place_order"));
    for (Caller c : List.of(Caller.agent_analysis, Caller.agent_verifier, Caller.agent_supervisor, Caller.orders_app, Caller.dba_oncall)) {
      TargetException e = assertThrows(TargetException.class, () -> t.apply(c, UUID.randomUUID(), v2, CommitHook.NONE), c.name());
      assertEquals(TargetException.Code.NOT_ALLOWED, e.code());
    }
    assertThrows(TargetException.class, () -> t.describe(Caller.agent_remediator, "orders", "orders", "table"));
    assertThrows(TargetException.class, () -> t.grantMembership(Caller.agent_supervisor, "orders_owner", "orders_app"));
    assertThrows(TargetException.class, () -> t.attempt(Caller.agent_verifier, "dba_oncall", new Privileges.SqlAction("SELECT", "orders.orders")));
    assertThrows(TargetException.class, () -> t.attempt(Caller.agent_analysis, "orders_app", new Privileges.SqlAction("SELECT", "orders.orders")));
    assertThrows(IllegalArgumentException.class, () -> t.snapshot(null, "orders_app"));
    assertThrows(adr.domain.InvalidInput.class, () -> t.fingerprint(Caller.agent_analysis, " "));
    assertTrue(t.state().ledger().isEmpty());
  }

  @Test
  void outOfBandRegrantChangesTheFingerprint() {
    SimulatedPgTarget t = target();
    PlanExec v2 = exec(t, v2(), List.of("place_order"));
    TargetDatabase.ApplyResult applied = t.apply(Caller.agent_remediator, UUID.randomUUID(), v2, CommitHook.NONE);
    String sealed = t.fingerprint(Caller.agent_supervisor, "orders_app");
    assertEquals(applied.fingerprintAfter(), sealed);
    Map<String, Object> sealedSnap = t.snapshot(Caller.agent_supervisor, "orders_app");
    t.grantMembership(Caller.dba_oncall, "orders_owner", "orders_app");
    String live = t.fingerprint(Caller.agent_supervisor, "orders_app");
    assertNotEquals(sealed, live);
    Map<String, Object> diff = Fingerprint.diff(sealedSnap, t.snapshot(Caller.agent_supervisor, "orders_app"));
    assertTrue(diff.containsKey("added_memberships"), diff.toString());
    assertEquals("dba_oncall", t.state().membership("orders_owner", "orders_app").grantor());
    assertTrue(t.attempt(Caller.orders_app, "orders_app", new Privileges.SqlAction("TRUNCATE", "orders.orders")).ok());
  }

  @Test
  void preflightReportsEveryCheck() {
    SimulatedPgTarget t = target();
    PlanExec ok = exec(t, v2(), List.of("place_order"));
    TargetDatabase.Preflight p = t.preflight(Caller.agent_remediator, ok);
    assertTrue(p.passed(), p.checks().toString());
    List<GrantSpec> bad = List.of(new GrantSpec("SELECT", "table", "orders.customers"), new GrantSpec("SELECT", "table", "orders.nope"));
    PlanExec broken = new PlanExec(FINDING, "orders_app", "orders_owner", bad, List.of("place_order"), "0".repeat(64));
    TargetDatabase.Preflight q = t.preflight(Caller.agent_remediator, broken);
    assertFalse(q.passed());
    assertTrue(q.checks().stream().anyMatch(c -> c.name().equals("objects_and_privileges") && !c.passed()));
    assertTrue(q.checks().stream().anyMatch(c -> c.name().equals("schema_usage_closure") && !c.passed()));
    assertTrue(q.checks().stream().anyMatch(c -> c.name().equals("expected_before") && !c.passed()));
    TargetException e = assertThrows(TargetException.class, () -> t.apply(Caller.agent_remediator, UUID.randomUUID(), broken, CommitHook.NONE));
    assertEquals(TargetException.Code.VALIDATION_FAILED, e.code());
    PlanExec stale = new PlanExec(FINDING, "orders_app", "orders_owner", v2(), List.of("place_order"), "0".repeat(64));
    TargetException e2 = assertThrows(TargetException.class, () -> t.apply(Caller.agent_remediator, UUID.randomUUID(), stale, CommitHook.NONE));
    assertEquals(TargetException.Code.PRECONDITION_FAILED, e2.code());
    assertTrue(t.state().ledger().isEmpty());
  }
}
