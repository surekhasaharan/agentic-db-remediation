package adr.agents;

import adr.broker.ToolCall;
import adr.domain.Decision;

import java.util.List;

/** One agent turn after placeholder substitution: what it says, and either calls or a decision. */
public record Turn(String when, String say, List<ToolCall> calls, Decision decision) {
  public boolean hasCalls() { return calls != null && !calls.isEmpty(); }
}
