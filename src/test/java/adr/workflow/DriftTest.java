package adr.workflow;

import adr.TestSession;
import adr.agents.ModelClient;
import adr.agents.Turn;
import adr.domain.AgentId;
import adr.domain.Decision;
import adr.domain.DriftObservation;
import adr.domain.FindingState;
import adr.domain.TimelineEvent;
import adr.stores.Findings;
import adr.stores.Lifecycle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DriftTest {

  static TestSession closed(TestSession t) {
    t.startAnalysis();
    String hash = t.session.stores().plans().latest(t.findingId()).hash();
    t.send("request_approval", "requester", t.args("finding_id", t.findingId().toString(), "plan_hash", hash));
    t.send("approve", "dba", t.args("finding_id", t.findingId().toString(), "plan_hash", hash));
    assertEquals(FindingState.CLOSED, t.state());
    return t;
  }

  static List<String> transitions(TestSession t, UUID f) {
    return t.events("finding.state_changed").stream().filter(e -> f.equals(e.findingId())).map(e -> (String) e.payload().get("to")).toList();
  }

  /** I11: after an out-of-band re-grant the finding reaches DRIFT_DETECTED within one poll and a linked child opens. */
  @Test
  void driftDetectedAndReopened() {
    TestSession t = closed(new TestSession());
    UUID parent = t.findingId();
    assertTrue(t.poller.tick(t.session).isEmpty(), "no difference, no move, no agent call");
    assertEquals(0, t.events("agent.turn").stream().filter(e -> e.actor().equals("supervision")).count());
    assertEquals(202, t.send("trigger_drift", "dba", t.args("finding_id", parent.toString())).status());
    assertEquals(FindingState.CLOSED, t.stateOf(parent), "the change is out of band; only the poll detects it");
    List<Lifecycle.Result> r = t.poller.tick(t.session);
    assertEquals(List.of(Lifecycle.Result.OK), r);
    assertEquals(FindingState.REOPENED, t.stateOf(parent));
    List<Findings.Entry> children = t.session.stores().findings().childrenOf(parent);
    assertEquals(1, children.size());
    assertEquals(FindingState.OPEN, children.get(0).state());
    assertEquals(parent, children.get(0).finding.parentFindingId());
    assertEquals(children.get(0).finding.id(), t.session.activeFindingId());
    assertEquals(List.of("ANALYSING", "PLAN_READY", "AWAITING_APPROVAL", "REMEDIATING", "VERIFYING", "CLOSED", "DRIFT_DETECTED", "REOPENED"), transitions(t, parent));
    // The supervisor read the grantor and the audit row, and the guard checked the recorded difference before any move.
    assertTrue(t.events("gate.checked").stream().anyMatch(e -> e.payload().get("rule").equals("guard.reopen_legality") && e.payload().get("result").equals("PASS")));
    assertTrue(t.events("gate.checked").stream().anyMatch(e -> e.payload().get("rule").equals("policy.tools.reopen_finding.finding_states")));
    TimelineEvent drift = t.events("drift.detected").get(0);
    assertTrue(drift.summary().contains("dba_oncall"), drift.summary());
    assertEquals(1, t.events("chaos.fired").size());
    assertEquals(3, t.session.stores().audit().all().size());
    assertTrue(t.session.stores().evidence().all().stream().anyMatch(e -> e.tool().equals("read_membership_grantor")
        && "dba_oncall".equals(((Map<?, ?>) e.data()).get("grantor"))));
    assertEquals(1, t.session.counters().mutations.get(), "reopening changes nothing in the target");
    assertTrue(t.poller.tick(t.session).isEmpty(), "REOPENED is terminal for the parent");
  }

  /** I20: one transition, three consecutive events, or nothing at all. */
  @Test
  void reopenIsAtomic() throws Exception {
    TestSession t = closed(new TestSession());
    UUID parent = t.findingId();
    t.send("trigger_drift", "dba", t.args("finding_id", parent.toString()));
    t.poller.tick(t.session);
    List<TimelineEvent> all = t.events();
    int i = -1;
    for (int k = 0; k < all.size(); k++) if (all.get(k).kind().equals("finding.state_changed") && "REOPENED".equals(all.get(k).payload().get("to"))) i = k;
    assertTrue(i >= 0);
    assertEquals("finding.created", all.get(i + 1).kind());
    assertEquals("session.active_finding_changed", all.get(i + 2).kind());
    assertEquals(all.get(i).seq() + 1, all.get(i + 1).seq());
    assertEquals(all.get(i).seq() + 2, all.get(i + 2).seq());
    assertEquals(all.get(i).seq(), t.session.stores().findings().get(parent).reopenedBySeq);

    // Forced to lose: the parent was moved elsewhere first, so the compare-and-set fails and nothing happens.
    TestSession u = closed(new TestSession());
    UUID p2 = u.findingId();
    u.force(p2, FindingState.CLOSED, FindingState.DRIFT_DETECTED);
    u.force(p2, FindingState.DRIFT_DETECTED, FindingState.NEEDS_ATTENTION);
    long seq = u.session.timeline().lastSeq();
    Decision.Supervision d = new Decision.Supervision("reopen", "drift", List.of("ev-1"));
    assertEquals(Lifecycle.Result.LOST_RACE, u.workflow.reopen(u.session, p2, d));
    assertTrue(u.session.stores().findings().childrenOf(p2).isEmpty());
    assertEquals(p2, u.session.activeFindingId());
    assertEquals(seq, u.session.timeline().lastSeq());

    // A concurrent snapshot never shows a REOPENED parent without its child.
    TestSession v = closed(new TestSession());
    UUID p3 = v.findingId();
    v.force(p3, FindingState.CLOSED, FindingState.DRIFT_DETECTED);
    AtomicBoolean stop = new AtomicBoolean();
    AtomicInteger violations = new AtomicInteger();
    Thread reader = new Thread(() -> {
      while (!stop.get()) {
        Map<String, Object> snap = adr.app.Snapshot.of(v.session);
        @SuppressWarnings("unchecked") List<Map<String, Object>> findings = (List<Map<String, Object>>) snap.get("findings");
        Map<String, Object> parentRow = findings.get(0);
        if ("REOPENED".equals(parentRow.get("state")) && ((List<?>) parentRow.get("children")).isEmpty()) violations.incrementAndGet();
        if ("REOPENED".equals(parentRow.get("state")) && !snap.get("active_finding_id").equals(((List<?>) parentRow.get("children")).get(0))) violations.incrementAndGet();
      }
    });
    reader.start();
    for (int k = 0; k < 200; k++) Thread.onSpinWait();
    assertEquals(Lifecycle.Result.OK, v.workflow.reopen(v.session, p3, d));
    Thread.sleep(20);
    stop.set(true);
    reader.join(2000);
    assertEquals(0, violations.get());
  }

  /** I21: the poller asks Lifecycle like every other caller and does nothing on SESSION_GONE or LOST_RACE. */
  @Test
  void driftUsesCommonTransition() {
    TestSession t = closed(new TestSession());
    UUID f = t.findingId();
    t.send("trigger_drift", "dba", t.args("finding_id", f.toString()));
    adr.app.Session old = t.session;
    // Reset mid-tick: the session is swapped out before the poller's transition runs.
    assertEquals(200, t.send("reset", "requester", Map.of()).status());
    assertFalse(old.isCurrent());
    List<Lifecycle.Result> r = t.poller.tick(old);
    assertTrue(r.isEmpty() || r.stream().allMatch(x -> x == Lifecycle.Result.SESSION_GONE), r.toString());
    assertEquals(FindingState.CLOSED, old.stores().findings().stateOf(f));
    assertEquals(0, old.timeline().all().stream().filter(e -> e.kind().equals("agent.turn") && e.actor().equals("supervision")).count());

    // A finding that is no longer CLOSED when the transition runs: LOST_RACE and no supervisor.
    TestSession u = closed(new TestSession());
    UUID g = u.findingId();
    u.send("trigger_drift", "dba", u.args("finding_id", g.toString()));
    u.force(g, FindingState.CLOSED, FindingState.DRIFT_DETECTED);
    assertTrue(u.poller.tick(u.session).isEmpty(), "inState(CLOSED) no longer lists it");
    assertEquals(0, u.events("agent.turn").stream().filter(e -> e.actor().equals("supervision")).count());
    assertThrows(IllegalArgumentException.class, () -> u.force(g, FindingState.REOPENED, FindingState.OPEN));
  }

  /** I17: busy considers every finding, so a REOPENED parent with a child in ANALYSING refuses reset. */
  @Test
  void busyConsidersEveryFinding() {
    TestSession t = closed(new TestSession());
    UUID parent = t.findingId();
    t.send("trigger_drift", "dba", t.args("finding_id", parent.toString()));
    t.poller.tick(t.session);
    assertEquals(FindingState.REOPENED, t.stateOf(parent));
    UUID child = t.session.activeFindingId();
    assertTrue(Boolean.TRUE.equals(adr.app.Snapshot.of(t.session).get("can_reset")));
    t.force(child, FindingState.OPEN, FindingState.ANALYSING);
    assertEquals(409, t.send("reset", "requester", Map.of()).status());
    assertEquals(false, adr.app.Snapshot.of(t.session).get("can_reset"));
    t.force(child, FindingState.ANALYSING, FindingState.NEEDS_ATTENTION);
    assertEquals(200, t.send("reset", "requester", Map.of()).status());
  }

  /** The reopen-legality guard validates the recommendation before the workflow changes any state. */
  @Test
  void reopenRequiresGuardApproval() {
    ModelClient eager = (agent, phase, trigger, ctx) -> {
      Turn turn = TestSession.RECORDED.next(agent, phase, trigger, ctx);
      if (turn != null && agent == AgentId.supervision && turn.decision() != null) {
        // Pretend the observation was lost: the recommendation must then be refused, not acted on.
        ctx.session.stores().findings().get(ctx.findingId).drift = new DriftObservation("a".repeat(64), "a".repeat(64), Map.of(), "now");
      }
      return turn;
    };
    TestSession t = closed(new TestSession(eager));
    UUID parent = t.findingId();
    t.send("trigger_drift", "dba", t.args("finding_id", parent.toString()));
    t.poller.tick(t.session);
    assertTrue(t.events("gate.checked").stream().anyMatch(e -> e.payload().get("rule").equals("guard.reopen_legality") && e.payload().get("result").equals("REFUSED")));
    assertTrue(t.session.stores().findings().childrenOf(parent).isEmpty(), "no child was created");
    assertEquals(parent, t.session.activeFindingId());
    assertEquals(FindingState.NEEDS_ATTENTION, t.stateOf(parent), "no corrective turn, so the run stopped safely from DRIFT_DETECTED");
    assertTrue(t.events("finding.state_changed").stream().noneMatch(e -> "REOPENED".equals(e.payload().get("to"))));
  }

  /** The whole guided run, with the guided faults, visits every state in order and ends with the child in OPEN. */
  @Test
  void findingHappyPathVisitsEveryState() {
    TestSession t = new TestSession();
    UUID f = t.findingId();
    t.startAnalysis();
    String hash = t.session.stores().plans().latest(f).hash();
    t.send("request_approval", "requester", t.args("finding_id", f.toString(), "plan_hash", hash));
    assertEquals(409, t.send("approve", "requester", t.args("finding_id", f.toString(), "plan_hash", hash)).status());
    t.send("arm_chaos", "dba", t.args("switch", "duplicate_delivery"));
    t.send("arm_chaos", "dba", t.args("switch", "drop_response"));
    t.send("approve", "dba", t.args("finding_id", f.toString(), "plan_hash", hash));
    t.send("trigger_drift", "dba", t.args("finding_id", f.toString()));
    t.poller.tick(t.session);
    assertEquals(GoldenFlow.WITH_DRIFT, transitions(t, f));
    UUID child = t.session.activeFindingId();
    assertNotEquals(f, child);
    assertEquals(FindingState.OPEN, t.stateOf(child));
    assertEquals(1, t.session.counters().mutations.get());
    assertEquals(1, t.session.counters().duplicatesRefused.get());
    assertEquals(1, t.session.counters().refusals.get(), "exactly one refusal: the self-approval");
    assertEquals(0, GoldenFlow.misses(t.session));
    // Running the lifecycle again on the child is explore-mode behaviour and works with a new plan hash.
    assertEquals(202, t.send("start_analysis", "requester", t.args("finding_id", child.toString())).status());
    assertEquals(FindingState.PLAN_READY, t.stateOf(child));
    assertNotEquals(hash, t.session.stores().plans().latest(child).hash());
  }

  @Test
  void goldenFlowHasNoRecordingMiss() {
    List<GoldenFlow.Result> results = GoldenFlow.run(TestSession.POLICY, TestSession.RECORDED, adr.stores.Seed.load());
    assertEquals(4, results.size());
    for (GoldenFlow.Result r : results) {
      assertTrue(r.ok(), r.config() + ": " + r.detail());
      assertEquals(0, r.misses());
      assertEquals(r.config().equals("drift") ? GoldenFlow.WITH_DRIFT : GoldenFlow.BASE, r.states(), r.config());
    }
    Map<String, Object> health = adr.app.StartupChecks.run().health();
    assertEquals("ok", health.get("status"));
    assertEquals(Map.of("no_faults", "ok", "duplicate_and_drop", "ok", "abort_before_commit", "ok", "drift", "ok"), health.get("golden_flow"));
  }

  @Test
  void authorisedChangeIsResealedNotReopened() {
    ModelClient annotating = (agent, phase, trigger, ctx) -> {
      Turn turn = TestSession.RECORDED.next(agent, phase, trigger, ctx);
      if (turn != null && agent == AgentId.supervision && turn.decision() != null) {
        return new Turn(turn.when(), turn.say(), turn.calls(), new Decision.Supervision("annotate", "The change is covered by ticket INC-2291.", turn.decision().evidence()));
      }
      return turn;
    };
    TestSession t = closed(new TestSession(annotating));
    UUID f = t.findingId();
    String sealed = t.session.stores().findings().get(f).sealedFingerprint;
    t.send("trigger_drift", "dba", t.args("finding_id", f.toString()));
    t.poller.tick(t.session);
    assertEquals(FindingState.CLOSED, t.stateOf(f));
    assertNotEquals(sealed, t.session.stores().findings().get(f).sealedFingerprint, "resealed with the new fingerprint");
    assertTrue(t.session.stores().findings().childrenOf(f).isEmpty());
    assertEquals(2, t.events("evidence.sealed").size());
    assertTrue(t.poller.tick(t.session).isEmpty(), "no further drift after the reseal");
  }
}
