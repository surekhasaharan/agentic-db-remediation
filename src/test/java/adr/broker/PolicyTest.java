package adr.broker;

import adr.TestSession;
import adr.agents.RecordedModelClient;
import adr.agents.Recording;
import adr.domain.AgentId;
import adr.domain.FindingState;
import adr.domain.Json;
import adr.domain.OperationState;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic least privilege: a tool is permitted only by an explicit policy entry. */
class PolicyTest {

  static String policyJson() throws Exception {
    try (InputStream in = PolicyTest.class.getResourceAsStream("/policy.json")) {
      return new String(in.readAllBytes());
    }
  }

  static Policy without(String... tools) throws Exception {
    ObjectNode doc = (ObjectNode) Json.CAMEL.readTree(policyJson());
    for (String t : tools) ((ObjectNode) doc.get("tools")).remove(t);
    return Policy.fromJson(Json.write(Json.CAMEL, doc));
  }

  @Test
  void everyDemoToolHasAnExplicitPolicyEntry() {
    Policy p = TestSession.POLICY;
    assertTrue(p.defaultDeny());
    for (ToolSpec spec : Tools.all()) {
      assertTrue(p.doc().tools().containsKey(spec.name()), spec.name() + " needs an explicit entry");
      assertTrue(p.permits(spec.name()), spec.name());
    }
    assertTrue(p.permits("reopen_finding"), "the workflow's reopen move is also listed");
    assertFalse(p.permits("drop_database"));
    assertFalse(p.permits("rollback_operation"));
    // Every recorded call names a permitted tool.
    for (Recording r : RecordedModelClient.loadAll().values()) {
      for (Recording.RawTurn t : r.turns()) {
        if (!t.hasCalls()) continue;
        for (Recording.RawCall c : t.calls()) assertTrue(p.permits(c.tool()), r.key() + " " + t.when() + " " + c.tool());
      }
    }
  }

  /** With "default": "deny", removing a tool's entry refuses it at broker step 7 even though the allowlist admits it. */
  @Test
  void policyDefaultDenyRefusesUnlistedTools() throws Exception {
    Policy p = without("read_grants", "run_negative_probe");
    ToolSpec grants = Tools.get("read_grants");
    Policy.Decision d = p.evaluateTool(grants, null);
    assertFalse(d.allowed());
    assertEquals("policy.default", d.rule());
    assertTrue(p.evaluateTool(Tools.get("read_role_graph"), null).allowed());

    TestSession t = new TestSession();
    ToolBroker broker = new ToolBroker(p);
    RunContext ctx = new RunContext(t.session, t.findingId(), AgentId.analysis, "A");
    ToolResult r = broker.invoke(ctx, new ToolCall("g", "read_grants", Map.of("role", "orders_app")));
    assertFalse(r.ok());
    assertEquals(ErrorCode.POLICY_DENIED, r.error().code());
    assertEquals(0, t.session.stores().evidence().size(), "no handler ran");
    assertTrue(t.events("gate.checked").stream().anyMatch(e -> e.payload().get("rule").equals("policy.default") && e.payload().get("result").equals("REFUSED")));
    ToolResult ok = broker.invoke(ctx, new ToolCall("r", "read_role_graph", Map.of("role", "orders_app")));
    assertTrue(ok.ok());
    RunContext v = new RunContext(t.session, t.findingId(), AgentId.verification, "main");
    assertEquals(ErrorCode.POLICY_DENIED, broker.invoke(v, new ToolCall("n", "run_negative_probe", Map.of("role", "orders_app"))).error().code());

    // An explicit entry can also deny outright.
    ObjectNode doc = (ObjectNode) Json.CAMEL.readTree(policyJson());
    ((ObjectNode) doc.get("tools").get("search_outcomes")).put("allow", false);
    Policy denying = Policy.fromJson(Json.write(Json.CAMEL, doc));
    Policy.Decision sd = denying.evaluateTool(Tools.get("search_outcomes"), null);
    assertFalse(sd.allowed());
    assertEquals("policy.tools.search_outcomes.allow", sd.rule());
    assertTrue(denying.evaluateTool(Tools.get("apply_right_size_role"), OperationState.APPROVED).allowed());
    assertFalse(denying.evaluateTool(Tools.get("apply_right_size_role"), OperationState.OUTCOME_UNKNOWN).allowed());
    assertFalse(without("reopen_finding").evaluateReopen(FindingState.DRIFT_DETECTED).allowed());
    assertTrue(TestSession.POLICY.evaluateReopen(FindingState.DRIFT_DETECTED).allowed());
    assertFalse(TestSession.POLICY.evaluateReopen(FindingState.CLOSED).allowed());
  }

  /** The guided flow stops safely, not silently, when a recording calls a tool the policy denies. */
  @Test
  void deniedToolStopsTheRunSafely() throws Exception {
    Policy p = without("read_job_runs");
    TestSession t = new TestSession(TestSession.RECORDED);
    adr.workflow.Workflow w = new adr.workflow.Workflow(Runnable::run, p, TestSession.RECORDED);
    adr.app.Commands commands = new adr.app.Commands(t.registry, w);
    commands.handle(t.session, Json.write(Json.SNAKE, Map.of("type", "start_analysis", "persona", "requester",
        "args", Map.of("finding_id", t.findingId().toString()))), "k");
    assertEquals(FindingState.NEEDS_ATTENTION, t.state());
    assertTrue(t.session.stores().findings().get(t.findingId()).attentionReason.startsWith("POLICY_DENIED"));
    assertEquals(1, t.session.stores().plans().forFinding(t.findingId()).size(), "plan v1 exists; phase B stopped at the denied read");
  }
}
