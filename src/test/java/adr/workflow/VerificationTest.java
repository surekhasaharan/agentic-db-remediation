package adr.workflow;

import adr.TestSession;
import adr.broker.Guards;
import adr.domain.Decision;
import adr.domain.FindingState;
import adr.domain.TimelineEvent;
import adr.domain.VerificationRecord;
import adr.stores.Findings;
import adr.target.Caller;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VerificationTest {

  static TestSession closed() {
    TestSession t = new TestSession();
    t.startAnalysis();
    String hash = t.session.stores().plans().latest(t.findingId()).hash();
    t.send("request_approval", "requester", t.args("finding_id", t.findingId().toString(), "plan_hash", hash));
    t.send("approve", "dba", t.args("finding_id", t.findingId().toString(), "plan_hash", hash));
    return t;
  }

  @Test
  void happyPathVerifiesClosesAndSeals() {
    TestSession t = closed();
    assertEquals(FindingState.CLOSED, t.state());
    Findings.Entry e = t.session.stores().findings().get(t.findingId());
    VerificationRecord v = e.verification;
    assertNotNull(v);
    assertTrue(v.allPassed(), v.failed().toString());
    assertEquals(6, v.assertions().size());
    assertEquals(2, v.probes().size());
    assertEquals(3, v.scenarios().size());
    assertEquals("42501", v.probes().get(0).detail().substring(v.probes().get(0).detail().length() - 5));
    assertNotNull(e.sealedFingerprint);
    assertEquals(t.session.target().fingerprint(Caller.agent_supervisor, "orders_app"), e.sealedFingerprint);
    assertEquals(1, t.events("evidence.sealed").size());
    assertEquals(1, t.session.stores().corpus().overlay().size());
    assertEquals("success", t.session.stores().corpus().overlay().get(0).disposition());
    // The verification results were stored before the verifier's turn: the gate event precedes the verifier's first turn.
    List<TimelineEvent> all = t.events();
    int computed = -1, firstVerifierTurn = -1;
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i).kind().equals("verification.completed")) computed = i;
      if (firstVerifierTurn < 0 && all.get(i).kind().equals("agent.turn") && all.get(i).actor().equals("verification")) firstVerifierTurn = i;
    }
    assertTrue(computed >= 0 && computed < firstVerifierTurn);
    assertEquals(1, t.session.counters().mutations.get());
    assertEquals(0, t.session.counters().refusals.get());
    assertTrue(Boolean.TRUE.equals(adr.app.Snapshot.of(t.session).get("can_reset")));
  }

  /** I10: with the stored checks failing, a recorded pass is overridden by the verdict guard. */
  @Test
  void verdictGuardOverridesPass() {
    VerificationRecord forced = VerificationRecord.of(UUID.randomUUID(),
        List.of(new VerificationRecord.Check("S3_direct_grants_equal_plan", false, "forced")),
        List.of(new VerificationRecord.Check("N1", true, "ok")), List.of(new VerificationRecord.Check("place_order", true, "ok")), "now");
    Guards.Result r = Guards.verdict(new Decision.Verification("pass", "all good", List.of("ev-1")), forced);
    assertFalse(r.ok());
    assertTrue(r.failures().get(0).contains("S3_direct_grants_equal_plan"));
    assertTrue(Guards.verdict(new Decision.Verification("fail", "no", List.of("ev-1")), forced).ok());

    TestSession t = new TestSession();
    // Before the facts are computed, an out-of-band re-grant makes the assertions fail.
    t.workflow.beforeVerificationHook = s -> s.target().grantMembership(Caller.dba_oncall, "orders_owner", "orders_app");
    t.startAnalysis();
    String hash = t.session.stores().plans().latest(t.findingId()).hash();
    t.send("request_approval", "requester", t.args("finding_id", t.findingId().toString(), "plan_hash", hash));
    t.send("approve", "dba", t.args("finding_id", t.findingId().toString(), "plan_hash", hash));
    Findings.Entry e = t.session.stores().findings().get(t.findingId());
    assertFalse(e.verification.allPassed());
    assertTrue(t.events("gate.checked").stream().anyMatch(x -> x.payload().get("rule").equals("guard.verdict") && x.payload().get("result").equals("REFUSED")));
    assertTrue(t.events("agent.decision").stream().anyMatch(x -> x.actor().equals("verification") && Boolean.FALSE.equals(x.payload().get("accepted"))));
    assertEquals(FindingState.NEEDS_ATTENTION, t.state());
    assertNull(e.sealedFingerprint, "nothing was sealed");
    assertTrue(t.session.stores().corpus().overlay().isEmpty());
  }
}
