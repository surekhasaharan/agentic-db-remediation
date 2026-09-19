package adr.agents;

import adr.broker.RunContext;
import adr.broker.ToolCall;
import adr.domain.AgentId;
import adr.domain.Decision;
import adr.domain.InvalidInput;
import adr.domain.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks a turn up by (agent, phase, trigger) and substitutes placeholders from the live run. The recorded
 * agent therefore still reacts to live tool results, live refusals and live faults.
 */
public final class RecordedModelClient implements ModelClient {
  public static final List<String> FILES = List.of("analysis-a.json", "analysis-b.json", "remediation.json",
      "verification.json", "supervision.json");

  private final Map<String, Recording> recordings;

  public RecordedModelClient(Map<String, Recording> recordings) { this.recordings = Map.copyOf(recordings); }

  public static RecordedModelClient fromClasspath() { return new RecordedModelClient(loadAll()); }

  public static Map<String, Recording> loadAll() {
    Map<String, Recording> out = new LinkedHashMap<>();
    for (String f : FILES) {
      Recording r = load(f);
      out.put(r.key(), r);
    }
    return out;
  }

  public static Recording load(String file) {
    try (InputStream in = RecordedModelClient.class.getResourceAsStream("/recordings/" + file)) {
      if (in == null) throw new IllegalStateException("missing recording " + file);
      return Json.read(Json.SNAKE, in, Recording.class);
    } catch (InvalidInput e) {
      throw new IllegalStateException("recordings/" + file + ": " + e.getMessage(), e);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("recordings/" + file + ": " + e.getMessage(), e);
    }
  }

  public Map<String, Recording> recordings() { return recordings; }

  @Override public Turn next(AgentId agent, String phase, String trigger, RunContext ctx) {
    Recording r = recordings.get(agent.name() + "/" + phase);
    if (r == null) return null;
    Recording.RawTurn t = r.turn(trigger);
    if (t == null) return null;
    return resolve(agent, t, ctx);
  }

  public static Turn resolve(AgentId agent, Recording.RawTurn t, RunContext ctx) {
    java.util.function.Function<String, String> resolver = s -> {
      if (s.equals("$op")) return ctx.operationId == null ? null : ctx.operationId.toString();
      if (s.equals("$finding")) return ctx.findingId.toString();
      return ctx.aliases.get(s.substring(Aliases.EV.length()));
    };
    List<ToolCall> calls = new ArrayList<>();
    if (t.hasCalls()) {
      for (Recording.RawCall c : t.calls()) {
        @SuppressWarnings("unchecked") Map<String, Object> args = (Map<String, Object>) Aliases.substitute(c.args(), resolver);
        calls.add(new ToolCall(c.as(), c.tool(), args));
      }
    }
    Decision d = null;
    if (t.decision() != null && !t.decision().isNull()) {
      JsonNode resolved = Aliases.substitute(t.decision(), resolver);
      d = toDecision(agent, resolved);
    }
    return new Turn(t.when(), t.say(), calls, d);
  }

  /** Strict Jackson, then the record's own checks. A failure is guard_rejected:schema for the runner. */
  public static Decision toDecision(AgentId agent, JsonNode node) {
    Class<? extends Decision> type = switch (agent) {
      case analysis -> Decision.Analysis.class;
      case remediation -> Decision.Remediation.class;
      case verification -> Decision.Verification.class;
      case supervision -> Decision.Supervision.class;
    };
    return Json.convert(Json.SNAKE, node, type);
  }
}
