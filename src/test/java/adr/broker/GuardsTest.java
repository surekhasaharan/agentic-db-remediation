package adr.broker;

import adr.TestSession;
import adr.agents.ModelClient;
import adr.agents.Turn;
import adr.domain.Decision;
import adr.domain.FindingState;
import adr.domain.GrantSpec;
import adr.domain.PlanExec;
import adr.stores.Seed;
import adr.target.Caller;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GuardsTest {

  /** Completeness refuses an unaddressed rolled-back outcome; justification refuses an unsupported grant. */
  @Test
  void completenessAndJustificationGuards() {
    TestSession t = new TestSession();
    t.startAnalysis();
    List<Seed.CorpusDoc> mandatory = t.session.stores().corpus().mandatory("pg.excessive_privilege.owner_membership");
    assertEquals(3, mandatory.size());
    Decision.Analysis omitting = new Decision.Analysis("fix", "medium", "keep everything", List.of(), List.of("place_order"),
        List.of(new Decision.AddressedOutcome("OUT-0198", "noted", "fine")), List.of(), List.of("ev-1"));
    Guards.Result c = Guards.completeness(omitting, mandatory);
    assertFalse(c.ok());
    assertTrue(c.failures().get(0).contains("OUT-0212"), c.failures().toString());
    Decision.Analysis addressed = new Decision.Analysis("fix", "medium", "keep everything", List.of(), List.of("month_end_close"),
        List.of(new Decision.AddressedOutcome("OUT-0212", "incorporated", "yes")), List.of(), List.of("ev-1"));
    assertTrue(Guards.completeness(addressed, mandatory).ok());
    assertTrue(Guards.scenarioCoverage(addressed, t.session.stores().corpus()::get).ok());
    Decision.Analysis uncovered = new Decision.Analysis("fix", "medium", "keep everything", List.of(), List.of("place_order"),
        List.of(new Decision.AddressedOutcome("OUT-0212", "incorporated", "yes")), List.of(), List.of("ev-1"));
    assertFalse(Guards.scenarioCoverage(uncovered, t.session.stores().corpus()::get).ok());

    // The telemetry evidence supports SELECT on customers but never DELETE.
    String telemetryId = t.session.stores().evidence().all().stream().filter(e -> e.tool().equals("read_query_telemetry")).findFirst().orElseThrow().id();
    Guards.Result j = Guards.justification(List.of(
        new Decision.RetainGrant("SELECT", "table", "orders.customers", List.of(telemetryId)),
        new Decision.RetainGrant("DELETE", "table", "orders.customers", List.of(telemetryId))), t.session.stores().evidence()::get);
    assertFalse(j.ok());
    assertEquals(1, j.failures().size());
    assertTrue(j.failures().get(0).startsWith("DELETE on orders.customers"), j.failures().toString());
    // Citing evidence that does not exist supports nothing.
    assertFalse(Guards.justification(List.of(new Decision.RetainGrant("SELECT", "table", "orders.customers", List.of("ev-999"))), t.session.stores().evidence()::get).ok());
    assertFalse(Guards.citation(List.of("ev-999"), java.util.Set.of("ev-1")).ok());
  }

  /** The poisoned document changes nothing: its privilege is refused by validation and by policy. */
  @Test
  void poisonedOutcomeCannotBecomeAGrant() {
    TestSession t = new TestSession();
    assertThrows(adr.domain.InvalidInput.class, () -> new Decision.RetainGrant("SUPERUSER", "role", "orders_app", List.of("ev-1")));
    String before = t.session.target().fingerprint(Caller.agent_analysis, "orders_app");
    PlanExec exec = new PlanExec(t.findingId(), "orders_app", "orders_owner",
        List.of(new GrantSpec("USAGE", "schema", "orders"), new GrantSpec("SUPERUSER", "table", "orders.orders")), List.of("place_order"), before);
    List<String> failures = TestSession.POLICY.admitPlan("pg.excessive_privilege.owner_membership", "orders_app", exec,
        List.of("completeness", "justification", "scenario_coverage", "citation"));
    assertTrue(failures.stream().anyMatch(f -> f.contains("SUPERUSER")), failures.toString());
    PlanExec protectedTarget = new PlanExec(t.findingId(), "orders_owner", "orders_owner", List.of(new GrantSpec("USAGE", "schema", "orders")), List.of("place_order"), before);
    assertFalse(TestSession.POLICY.admitPlan("pg.excessive_privilege.owner_membership", "orders_app", protectedTarget, List.of("completeness", "justification", "scenario_coverage", "citation")).isEmpty());
    assertTrue(TestSession.POLICY.admitPlan("pg.excessive_privilege.owner_membership", "orders_app", exec, List.of("completeness")).stream().anyMatch(f -> f.contains("required guard")));
  }

  /** I15 with a guard in the loop: a decision that overlooks the history is refused, and with no corrective turn the run stops safely. */
  @Test
  void guardRefusalWithoutCorrectiveTurnStopsSafely() {
    ModelClient overlooking = (agent, phase, trigger, ctx) -> {
      Turn turn = TestSession.RECORDED.next(agent, phase, trigger, ctx);
      if (turn != null && agent == adr.domain.AgentId.analysis && phase.equals("B") && turn.decision() instanceof Decision.Analysis a) {
        Decision.Analysis overlooked = new Decision.Analysis(a.decision(), a.confidence(), a.reason(), a.retainGrants(), a.healthScenarios(),
            a.addressedOutcomes().stream().filter(o -> !o.docId().equals("OUT-0212")).toList(), a.unknowns(), a.evidence());
        return new Turn(turn.when(), turn.say(), turn.calls(), overlooked);
      }
      return turn;
    };
    TestSession t = new TestSession(overlooking);
    t.startAnalysis();
    assertEquals(FindingState.NEEDS_ATTENTION, t.state());
    assertTrue(t.session.stores().findings().get(t.findingId()).attentionReason.startsWith("DEMO_FLOW_UNAVAILABLE"));
    assertTrue(t.events("gate.checked").stream().anyMatch(e -> e.payload().get("rule").equals("guard.completeness") && e.payload().get("result").equals("REFUSED")));
    assertEquals(1, t.session.stores().plans().forFinding(t.findingId()).size(), "only v1 exists");
    assertTrue(t.events("agent.stopped").stream().anyMatch(e -> e.payload().get("trigger").equals("guard_rejected:completeness")));
    assertTrue(Boolean.TRUE.equals(adr.app.Snapshot.of(t.session).get("can_reset")), "NEEDS_ATTENTION is stable");
    List<String> tools = new ArrayList<>();
    Stream.of(t.events("tool.invoked")).flatMap(List::stream).forEach(e -> tools.add((String) e.payload().get("tool")));
    assertEquals(6, tools.size(), "no tool call after the stop: " + tools);
  }
}
