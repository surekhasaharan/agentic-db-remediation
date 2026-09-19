package adr.agents;

import adr.TestSession;
import adr.broker.RunContext;
import adr.broker.ToolCall;
import adr.broker.ToolResult;
import adr.domain.AgentId;
import adr.domain.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class RecordingsTest {

  static ObjectNode raw(String file) throws Exception {
    try (InputStream in = RecordingsTest.class.getResourceAsStream("/recordings/" + file)) {
      return (ObjectNode) Json.SNAKE.readTree(in);
    }
  }

  static RecordingValidator.Report validateMutated(String file, Consumer<ObjectNode> mutate) throws Exception {
    Map<String, Recording> all = new LinkedHashMap<>(RecordedModelClient.loadAll());
    ObjectNode node = raw(file);
    mutate.accept(node);
    Recording r = Json.convert(Json.SNAKE, node, Recording.class);
    all.put(r.key(), r);
    return RecordingValidator.validate(all);
  }

  static ObjectNode turn(ObjectNode rec, String when) {
    for (JsonNode t : rec.get("turns")) if (t.get("when").asText().equals(when)) return (ObjectNode) t;
    throw new AssertionError("no turn " + when);
  }

  /** I15: the shipped recordings pass, and each kind of gap fails with a message naming the file and the turn. */
  @Test
  void recordingsCoverEveryRequiredTrigger() throws Exception {
    RecordingValidator.Report shipped = RecordingValidator.validate(RecordedModelClient.loadAll());
    assertTrue(shipped.ok(), shipped.errors().toString());
    assertTrue(shipped.warnings().isEmpty(), "dead turns: " + shipped.warnings());

    RecordingValidator.Report missing = validateMutated("remediation.json", n -> {
      ArrayNode turns = (ArrayNode) n.get("turns");
      for (int i = 0; i < turns.size(); i++) if (turns.get(i).get("when").asText().equals("ok:get_operation_status:applied")) { turns.remove(i); break; }
    });
    assertFalse(missing.ok());
    assertTrue(missing.errors().stream().anyMatch(p -> p.file().equals("remediation/main") && p.turn().equals("ok:get_operation_status:applied")), missing.errors().toString());

    RecordingValidator.Report misspelt = validateMutated("remediation.json", n -> turn(n, "ok:preflight_check").put("when", "ok:preflght_check"));
    assertFalse(misspelt.ok());
    assertTrue(misspelt.errors().stream().anyMatch(p -> p.turn().equals("ok:preflght_check")), misspelt.errors().toString());
    assertTrue(misspelt.errors().stream().anyMatch(p -> p.turn().equals("ok:preflight_check") && p.message().contains("required")), misspelt.errors().toString());

    RecordingValidator.Report dupAlias = validateMutated("analysis-a.json", n -> ((ObjectNode) turn(n, "start").get("calls").get(1)).put("as", "role_graph"));
    assertFalse(dupAlias.ok());
    assertTrue(dupAlias.errors().stream().anyMatch(p -> p.file().equals("analysis/A") && p.message().contains("alias role_graph is reused")), dupAlias.errors().toString());

    RecordingValidator.Report badRef = validateMutated("analysis-a.json", n -> ((ArrayNode) turn(n, "ok:read_query_telemetry").get("decision").get("evidence")).add("$ev:nope"));
    assertFalse(badRef.ok());
    assertTrue(badRef.errors().stream().anyMatch(p -> p.message().contains("$ev:nope")), badRef.errors().toString());

    RecordingValidator.Report undeclared = validateMutated("remediation.json", n -> turn(n, "error:apply_right_size_role:LOCK_TIMEOUT").put("when", "error:apply_right_size_role:DISK_FULL"));
    assertFalse(undeclared.ok());
    assertTrue(undeclared.errors().stream().anyMatch(p -> p.message().contains("DISK_FULL")), undeclared.errors().toString());

    RecordingValidator.Report foreign = validateMutated("verification.json", n -> ((ObjectNode) turn(n, "start").get("calls").get(0)).put("tool", "apply_right_size_role"));
    assertFalse(foreign.ok());

    RecordingValidator.Report badGuard = validateMutated("analysis-b.json", n -> {
      ObjectNode t = turn(n, "ok:read_job_runs").deepCopy();
      t.put("when", "guard_rejected:vibes");
      ((ArrayNode) n.get("turns")).add(t);
    });
    assertTrue(badGuard.errors().stream().anyMatch(p -> p.turn().equals("guard_rejected:vibes")), badGuard.errors().toString());

    assertThrows(adr.domain.InvalidInput.class, () -> Json.convert(Json.SNAKE, raw("analysis-a.json").put("captured", "model"), Recording.class));
  }

  /** Two calls to the same tool yield two evidence references; a retried alias rebinds to the latest result. */
  @Test
  void aliasesBindDistinctEvidence() {
    TestSession t = new TestSession();
    RunContext ctx = new RunContext(t.session, t.findingId(), AgentId.analysis, "B");
    ToolResult a = t.workflow.broker().invoke(ctx, new ToolCall("ledger_archive", "read_catalog_object", Map.of("schema", "orders", "name", "ledger_archive", "kind", "table")));
    ToolResult b = t.workflow.broker().invoke(ctx, new ToolCall("close_period", "read_catalog_object", Map.of("schema", "orders", "name", "close_period(date)", "kind", "function")));
    assertTrue(a.ok() && b.ok());
    assertNotEquals(ctx.aliases.get("ledger_archive"), ctx.aliases.get("close_period"));
    assertEquals("orders.ledger_archive", t.session.stores().evidence().get(ctx.aliases.get("ledger_archive")).data() instanceof Map<?, ?> m ? m.get("key") : null);
    assertEquals("orders.close_period(date)", ((Map<?, ?>) t.session.stores().evidence().get(ctx.aliases.get("close_period")).data()).get("key"));
    ToolResult g1 = t.workflow.broker().invoke(ctx, new ToolCall("grants", "read_grants", Map.of("role", "orders_app")));
    ToolResult g2 = t.workflow.broker().invoke(ctx, new ToolCall("grants", "read_grants", Map.of("role", "orders_app")));
    assertNotEquals(g1.evidenceId(), g2.evidenceId());
    assertEquals(g2.evidenceId(), ctx.aliases.get("grants"), "the alias points at the latest result");
    assertNotNull(t.session.stores().evidence().get(g1.evidenceId()), "the earlier result stays under its own id");
    assertEquals(4, ctx.producedEvidence.size());
  }

  @Test
  void recordedTurnsReactToLiveTriggers() {
    TestSession t = new TestSession();
    RunContext ctx = new RunContext(t.session, t.findingId(), AgentId.remediation, "main");
    ctx.operationId = java.util.UUID.randomUUID();
    Turn start = TestSession.RECORDED.next(AgentId.remediation, "main", "start", ctx);
    assertEquals("preflight_check", start.calls().get(0).tool());
    assertEquals(ctx.operationId.toString(), start.calls().get(0).args().get("operation_ref"), "$op is substituted from the live run");
    assertNull(TestSession.RECORDED.next(AgentId.remediation, "main", "error:apply_right_size_role:LEASE_HELD", ctx), "no LEASE_HELD turn: the agent only ever sees the winner's result");
    ctx.bind("status", "ev-42");
    Turn applied = TestSession.RECORDED.next(AgentId.remediation, "main", "ok:get_operation_status:applied", ctx);
    assertEquals("applied", applied.decision().decision());
    assertEquals(java.util.List.of("ev-42"), applied.decision().evidence());
  }
}
