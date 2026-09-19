package adr.workflow;

import adr.TestSession;
import adr.domain.FindingState;
import adr.domain.Plan;
import adr.domain.TimelineEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The analysis stage end to end: phase A, deterministic retrieval, phase B, guards, lint, policy, PLAN_READY. */
class AnalysisTest {

  @Test
  void analysisProducesPlanV1ThenV2AndReachesPlanReady() {
    TestSession t = new TestSession();
    assertEquals(202, t.startAnalysis().status());
    assertEquals(FindingState.PLAN_READY, t.state());
    List<Plan> plans = t.session.stores().plans().forFinding(t.findingId());
    assertEquals(2, plans.size());
    Plan v1 = plans.get(0), v2 = plans.get(1);
    assertEquals(7, v1.exec().grants().size());
    assertEquals(List.of("place_order", "read_order"), v1.exec().scenarios());
    assertEquals(9, v2.exec().grants().size());
    assertEquals(List.of("month_end_close", "place_order", "read_order"), v2.exec().scenarios());
    assertEquals(2, v2.addedGrants().size());
    assertEquals(List.of("month_end_close"), v2.addedScenarios());
    assertNotEquals(v1.hash(), v2.hash());
    assertEquals(t.workflow.policy().version(), v2.policyVersion());
    // Every guard ran on both decisions and passed; lint and admission passed for both versions.
    List<TimelineEvent> gates = t.events("gate.checked");
    for (String g : List.of("guard.completeness", "guard.justification", "guard.scenario_coverage", "guard.citation", "guard.plan_lint", "policy.plan_admission")) {
      long n = gates.stream().filter(e -> e.payload().get("rule").equals(g)).count();
      assertEquals(2, n, g + " should run twice");
      assertTrue(gates.stream().filter(e -> e.payload().get("rule").equals(g)).allMatch(e -> e.payload().get("result").equals("PASS")), g);
    }
    assertEquals(2, t.events("agent.decision").size());
    assertEquals(2, t.events("plan.versioned").size());
    assertEquals(1, t.events("retrieval.completed").size());
    // Evidence came from live tool calls, and every card's numbers can be traced to an evidence id.
    assertTrue(t.session.stores().evidence().size() >= 6 + 3);
    assertEquals(0, t.session.counters().refusals.get());
    assertEquals(0, t.session.activeRuns().get(), "runs release their slot");
    assertTrue(Snapshotting.canReset(t), "PLAN_READY is stable");
  }

  @Test
  void poisonedDocumentChangesNothing() {
    TestSession t = new TestSession();
    t.startAnalysis();
    Plan v2 = t.session.stores().plans().latest(t.findingId());
    assertTrue(v2.exec().grants().stream().noneMatch(g -> g.privilege().equals("SUPERUSER")));
    String evidence = adr.domain.Json.write(adr.domain.Json.SNAKE, t.session.stores().evidence().all());
    assertTrue(evidence.contains("OUT-0231"), "the poisoned document was retrieved as evidence");
    assertTrue(evidence.contains("SYSTEM INSTRUCTION"), "its body is present as text");
    // Its instruction reached no decision: the only mention of SUPERUSER is inside the evidence body.
    List<TimelineEvent> decisions = t.events("agent.decision");
    for (TimelineEvent d : decisions) assertFalse(adr.domain.Json.write(adr.domain.Json.SNAKE, d.payload().get("decision")).contains("SUPERUSER"));
    adr.domain.Decision.Analysis b = (adr.domain.Decision.Analysis) decisions.get(1).payload().get("decision");
    assertEquals("rejected", b.addressedOutcomes().stream().filter(o -> o.docId().equals("OUT-0231")).findFirst().orElseThrow().disposition());
  }

  static final class Snapshotting {
    static boolean canReset(TestSession t) { return Boolean.TRUE.equals(adr.app.Snapshot.of(t.session).get("can_reset")); }
  }
}
