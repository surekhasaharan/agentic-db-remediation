package adr.broker;

import java.util.Map;

/** What the broker returns: evidence id, source label and data, or an error envelope. */
public record ToolResult(String tool, String evidenceId, String sourceLabel, Map<String, Object> data, String outcome, ToolError error) {
  public boolean ok() { return error == null; }

  public static ToolResult ok(String tool, String evidenceId, String sourceLabel, Map<String, Object> data, String outcome) {
    return new ToolResult(tool, evidenceId, sourceLabel, data, outcome, null);
  }

  public static ToolResult error(String tool, ToolError e) {
    return new ToolResult(tool, null, null, Map.of(), null, e);
  }

  /** The trigger a recorded agent reacts to: "ok:<tool>", "ok:<tool>:<outcome>" or "error:<tool>:<CODE>". */
  public String trigger() {
    if (!ok()) return "error:" + tool + ":" + error.code();
    return outcome == null ? "ok:" + tool : "ok:" + tool + ":" + outcome;
  }
}
