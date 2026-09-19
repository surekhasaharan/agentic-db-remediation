package adr.agents;

import adr.domain.AgentId;
import adr.domain.Require;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** A recording file as authored: a small table of turns keyed by what just happened. Holds no tool results. */
public record Recording(String agent, String phase, String captured, List<RawTurn> turns) {

  public record RawCall(String as, String tool, Map<String, Object> args) {
    public RawCall {
      Require.nonBlank(as, "as");
      Require.nonBlank(tool, "tool");
      Require.nonNull(args, "args");
    }
  }

  public record RawTurn(String when, String say, List<RawCall> calls, JsonNode decision) {
    public RawTurn {
      Require.nonBlank(when, "when");
      Require.nonBlank(say, "say");
      Require.maxLen(say, 600, "say");
      boolean hasCalls = calls != null && !calls.isEmpty();
      boolean hasDecision = decision != null && !decision.isNull();
      if (hasCalls == hasDecision) throw new adr.domain.InvalidInput("turn " + when, "exactly one of calls or decision is required");
      if (calls != null) calls = List.copyOf(calls);
    }
    public boolean hasCalls() { return calls != null && !calls.isEmpty(); }
    public RawCall lastCall() { return calls.get(calls.size() - 1); }
  }

  public Recording {
    Require.oneOf(agent, AgentId.class, "agent");
    Require.nonBlank(phase, "phase");
    Require.nonBlank(captured, "captured");
    if (!captured.equals("authored")) throw new adr.domain.InvalidInput("captured", "D0 recordings must be authored, not " + captured);
    turns = Require.nonEmpty(turns, 64, "turns");
  }

  public AgentId agentId() { return AgentId.valueOf(agent); }
  public String key() { return agent + "/" + phase; }

  public RawTurn turn(String when) {
    for (RawTurn t : turns) if (t.when().equals(when)) return t;
    return null;
  }
}
