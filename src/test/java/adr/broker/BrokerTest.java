package adr.broker;

import adr.TestSession;
import adr.domain.AgentId;
import adr.domain.FindingState;
import adr.domain.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BrokerTest {

  static RunContext ctx(TestSession t, AgentId agent) { return new RunContext(t.session, t.findingId(), agent, "A"); }

  /** I1 and I2: each agent calling a foreign tool is refused; an extra argument on a write call is refused. */
  @Test
  void foreignToolAndExtraArgsRefused() {
    TestSession t = new TestSession();
    ToolBroker broker = t.workflow.broker();
    for (AgentId agent : AgentId.values()) {
      for (ToolSpec spec : Tools.all()) {
        if (spec.allows(agent)) continue;
        ToolResult r = broker.invoke(ctx(t, agent), new ToolCall("x", spec.name(), Map.of()));
        assertFalse(r.ok(), agent + " " + spec.name());
        assertEquals(ErrorCode.TOOL_NOT_ALLOWED, r.error().code(), agent + " " + spec.name());
      }
    }
    ToolResult unknown = broker.invoke(ctx(t, AgentId.analysis), new ToolCall("x", "drop_database", Map.of()));
    assertEquals(ErrorCode.TOOL_NOT_ALLOWED, unknown.error().code());
    // Extra argument on the write tool: strict deserialisation refuses it before any reference is resolved.
    Operation op = new Operation(UUID.randomUUID(), t.findingId(), "a".repeat(64));
    t.session.stores().operations().putIfAbsent(op);
    ToolResult extra = broker.invoke(ctx(t, AgentId.remediation), new ToolCall("apply", "apply_right_size_role",
        Map.of("operation_ref", op.id().toString(), "grants", List.of("SUPERUSER"))));
    assertEquals(ErrorCode.INVALID_ARGS, extra.error().code());
    assertTrue(t.session.target().state().ledger().isEmpty());
    assertEquals(FindingState.OPEN, t.state());
    assertTrue(t.session.counters().refusals.get() > 0);
  }

  @Test
  void unknownReferencesAndKillSwitchAreRefused() {
    TestSession t = new TestSession();
    ToolBroker broker = t.workflow.broker();
    ToolResult r = broker.invoke(ctx(t, AgentId.remediation), new ToolCall("p", "preflight_check", Map.of("operation_ref", UUID.randomUUID().toString())));
    assertEquals(ErrorCode.UNKNOWN_REF, r.error().code());
    t.session.killSwitch().set(true);
    ToolResult probe = broker.invoke(ctx(t, AgentId.verification), new ToolCall("n", "run_negative_probe", Map.of("role", "orders_app")));
    assertEquals(ErrorCode.KILLED, probe.error().code());
    ToolResult read = broker.invoke(ctx(t, AgentId.analysis), new ToolCall("g", "read_grants", Map.of("role", "orders_app")));
    assertTrue(read.ok(), "reads still work under the kill switch");
    t.session.killSwitch().set(false);
    ToolResult prot = broker.invoke(ctx(t, AgentId.verification), new ToolCall("n", "run_negative_probe", Map.of("role", "orders_owner")));
    assertEquals(ErrorCode.PROTECTED_TARGET, prot.error().code());
  }

  record Case(AgentId agent, String tool, Map<String, Object> args, String field) {}

  static Stream<Arguments> toolCases() {
    String uuid = "7d1f4c1e-9a1b-4c3e-8f2a-1b2c3d4e5f60";
    List<Case> base = List.of(
        new Case(AgentId.analysis, "read_role_graph", Map.of("role", "orders_app"), "role"),
        new Case(AgentId.analysis, "read_grants", Map.of("role", "orders_app"), "role"),
        new Case(AgentId.analysis, "read_catalog_object", Map.of("schema", "orders", "name", "orders", "kind", "table"), "schema"),
        new Case(AgentId.analysis, "read_catalog_object", Map.of("schema", "orders", "name", "orders", "kind", "table"), "name"),
        new Case(AgentId.analysis, "read_catalog_object", Map.of("schema", "orders", "name", "orders", "kind", "table"), "kind"),
        new Case(AgentId.analysis, "read_query_telemetry", Map.of("role", "orders_app", "days", 14), "role"),
        new Case(AgentId.analysis, "read_job_runs", Map.of("role", "orders_app"), "role"),
        new Case(AgentId.analysis, "search_outcomes", Map.of("terms", List.of("owner")), "terms"),
        new Case(AgentId.remediation, "preflight_check", Map.of("operation_ref", uuid), "operation_ref"),
        new Case(AgentId.remediation, "apply_right_size_role", Map.of("operation_ref", uuid), "operation_ref"),
        new Case(AgentId.remediation, "get_operation_status", Map.of("operation_ref", uuid), "operation_ref"),
        new Case(AgentId.verification, "assert_privileges", Map.of("finding_ref", uuid), "finding_ref"),
        new Case(AgentId.verification, "run_negative_probe", Map.of("role", "orders_app"), "role"),
        new Case(AgentId.verification, "run_health_scenarios", Map.of("role", "orders_app", "scenarios", List.of("place_order")), "role"),
        new Case(AgentId.verification, "run_health_scenarios", Map.of("role", "orders_app", "scenarios", List.of("place_order")), "scenarios"),
        new Case(AgentId.supervision, "read_control_fingerprint", Map.of("finding_ref", uuid), "finding_ref"),
        new Case(AgentId.supervision, "read_membership_grantor", Map.of("role", "orders_owner", "member", "orders_app"), "member"),
        new Case(AgentId.supervision, "read_role_change_log", Map.of("role", "orders_owner"), "role"));
    List<Arguments> out = new java.util.ArrayList<>();
    for (Case c : base) for (String v : List.of("absent", "null", "empty", "blank")) out.add(Arguments.of(c, v));
    return out.stream();
  }

  /** I18 for tool arguments: every required field, four variants, refusal names the field, nothing changes. */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("toolCases")
  void missingNullBlankInputsAreRefused(Case c, String variant) {
    TestSession t = new TestSession();
    Map<String, Object> args = new LinkedHashMap<>(c.args());
    switch (variant) {
      case "absent" -> args.remove(c.field());
      case "null" -> args.put(c.field(), null);
      case "empty" -> args.put(c.field(), c.field().equals("terms") || c.field().equals("scenarios") ? List.of() : "");
      default -> args.put(c.field(), c.field().equals("terms") || c.field().equals("scenarios") ? List.of("   ") : "   ");
    }
    String before = adr.domain.Json.write(adr.domain.Json.SNAKE, adr.app.Snapshot.of(t.session).get("findings"));
    ToolResult r = t.workflow.broker().invoke(ctx(t, c.agent()), new ToolCall("x", c.tool(), args));
    assertFalse(r.ok(), variant + " " + r);
    assertEquals(ErrorCode.INVALID_ARGS, r.error().code(), variant + ": " + r.error());
    assertTrue(r.error().field().startsWith(c.field()), "field named: " + r.error().field());
    assertEquals(before, adr.domain.Json.write(adr.domain.Json.SNAKE, adr.app.Snapshot.of(t.session).get("findings")));
    assertTrue(t.session.target().state().ledger().isEmpty());
    assertEquals(0, t.session.stores().evidence().size());
  }

  @Test
  void decisionRecordsRefuseMissingNullBlank() {
    for (String json : List.of(
        "{\"decision\":\"fix\",\"confidence\":\"medium\",\"reason\":\"\",\"retain_grants\":[],\"health_scenarios\":[\"x\"],\"addressed_outcomes\":[],\"unknowns\":[],\"evidence\":[\"ev-1\"]}",
        "{\"decision\":\"fix\",\"confidence\":\"medium\",\"reason\":\"r\",\"retain_grants\":[],\"health_scenarios\":null,\"addressed_outcomes\":[],\"unknowns\":[],\"evidence\":[\"ev-1\"]}",
        "{\"decision\":\"fix\",\"confidence\":\"medium\",\"reason\":\"r\",\"retain_grants\":[{\"privilege\":\" \",\"kind\":\"table\",\"object\":\"orders.orders\",\"evidence\":[\"ev-1\"]}],\"health_scenarios\":[\"x\"],\"addressed_outcomes\":[],\"unknowns\":[],\"evidence\":[\"ev-1\"]}",
        "{\"confidence\":\"medium\",\"reason\":\"r\",\"retain_grants\":[],\"health_scenarios\":[\"x\"],\"addressed_outcomes\":[],\"unknowns\":[],\"evidence\":[\"ev-1\"]}")) {
      assertThrows(adr.domain.InvalidInput.class, () -> adr.domain.Json.read(adr.domain.Json.SNAKE, json, adr.domain.Decision.Analysis.class), json);
    }
    for (String json : List.of("{\"decision\":\"pass\",\"reason\":\"ok\",\"evidence\":[]}", "{\"decision\":\"maybe\",\"reason\":\"ok\",\"evidence\":[\"ev-1\"]}",
        "{\"decision\":\"pass\",\"reason\":\"ok\",\"evidence\":[\"ev-1\"],\"extra\":1}")) {
      assertThrows(adr.domain.InvalidInput.class, () -> adr.domain.Json.read(adr.domain.Json.SNAKE, json, adr.domain.Decision.Verification.class), json);
    }
  }
}
