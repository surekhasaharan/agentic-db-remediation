package adr.app;

import adr.TestSession;
import adr.domain.FindingState;
import adr.domain.Json;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** I18 for the HTTP command boundary: absent, null, empty and whitespace-only values are refused by name. */
class CommandsInputTest {

  record Case(String type, String persona, Map<String, Object> args, String field) {}

  static Stream<Arguments> cases() {
    String fid = "7d1f4c1e-9a1b-4c3e-8f2a-1b2c3d4e5f60";
    String hash = "a".repeat(64);
    List<Case> base = List.of(
        new Case("start_analysis", "requester", Map.of("finding_id", fid), "args.finding_id"),
        new Case("request_approval", "requester", Map.of("finding_id", fid, "plan_hash", hash), "args.finding_id"),
        new Case("request_approval", "requester", Map.of("finding_id", fid, "plan_hash", hash), "args.plan_hash"),
        new Case("approve", "dba", Map.of("finding_id", fid, "plan_hash", hash), "args.plan_hash"),
        new Case("reject", "dba", Map.of("finding_id", fid, "plan_hash", hash), "args.finding_id"),
        new Case("trigger_drift", "dba", Map.of("finding_id", fid), "args.finding_id"),
        new Case("arm_chaos", "requester", Map.of("switch", "drop_response"), "args.switch"),
        new Case("start_burst", "requester", Map.of("count", 10), "args.count"),
        new Case("kill_switch", "requester", Map.of("on", true), "args.on"),
        new Case("start_analysis", "requester", Map.of("finding_id", fid), "type"),
        new Case("start_analysis", "requester", Map.of("finding_id", fid), "persona"));
    List<Arguments> out = new java.util.ArrayList<>();
    for (Case c : base) for (String variant : List.of("absent", "null", "empty", "blank")) out.add(Arguments.of(c, variant));
    return out.stream();
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("cases")
  void missingNullBlankInputsAreRefused(Case c, String variant) {
    TestSession t = new TestSession();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", c.type());
    body.put("persona", c.persona());
    Map<String, Object> args = new LinkedHashMap<>(c.args());
    String argField = c.field().startsWith("args.") ? c.field().substring(5) : null;
    Object value = switch (variant) {
      case "null" -> null;
      case "empty" -> "";
      case "blank" -> "   ";
      default -> "ABSENT";
    };
    if (argField != null) {
      if (variant.equals("absent")) args.remove(argField); else args.put(argField, value);
    } else {
      if (variant.equals("absent")) body.remove(c.field()); else body.put(c.field(), value);
    }
    body.put("args", args);
    long seq = t.session.timeline().lastSeq();
    Commands.Result r = t.sendRaw(Json.write(Json.SNAKE, body));
    assertEquals(400, r.status(), variant + ": " + r.body());
    Map<?, ?> err = (Map<?, ?>) r.body().get("error");
    assertEquals("INVALID_INPUT", err.get("code"));
    assertEquals(c.field(), err.get("field"), "refusal names the field");
    assertEquals(FindingState.OPEN, t.state(), "state unchanged");
    assertEquals(seq + 1, t.session.timeline().lastSeq(), "one gate event");
    assertEquals(1, t.session.counters().refusals.get());
  }

  @org.junit.jupiter.api.Test
  void bodyAndHeaderAreChecked() {
    TestSession t = new TestSession();
    assertEquals(400, t.sendRaw("").status());
    assertEquals(400, t.sendRaw("not json").status());
    assertEquals(400, t.sendRaw("{\"type\":\"reset\",\"persona\":\"requester\",\"args\":{},\"extra\":1}").status());
    assertEquals(400, t.sendRaw("{" + "\"x\":\"" + "y".repeat(9000) + "\"}").status());
    assertEquals(400, t.commands.handle(t.session, "{\"type\":\"reset\",\"persona\":\"requester\",\"args\":{}}", "").status());
    assertEquals(400, t.commands.handle(t.session, "{\"type\":\"reset\",\"persona\":\"requester\",\"args\":{}}", null).status());
    assertEquals(400, t.commands.handle(t.session, "{\"type\":\"reset\",\"persona\":\"requester\",\"args\":{}}", "k".repeat(65)).status());
    assertEquals(400, t.sendRaw("{\"type\":\"reset\",\"persona\":\"requester\",\"args\":{\"unexpected\":1}}").status());
    assertEquals(400, t.sendRaw("{\"type\":\"start_burst\",\"persona\":\"requester\",\"args\":{\"count\":5001}}").status());
    assertEquals(400, t.sendRaw("{\"type\":\"approve\",\"persona\":\"dba\",\"args\":{\"finding_id\":\""
        + t.findingId() + "\",\"plan_hash\":\"" + "a".repeat(64) + "\",\"comment\":\"" + "c".repeat(501) + "\"}}").status());
  }
}
