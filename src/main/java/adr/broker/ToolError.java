package adr.broker;

import java.util.List;

/** The error envelope a tool call returns. */
public record ToolError(ErrorCode code, String message, boolean retryable, List<String> nextAllowedTools, String field) {
  public static ToolError of(ErrorCode code, String message) { return new ToolError(code, message, false, List.of(), null); }
  public static ToolError of(ErrorCode code, String message, boolean retryable, List<String> next) {
    return new ToolError(code, message, retryable, next, null);
  }
  public static ToolError invalid(String field, String problem) {
    return new ToolError(ErrorCode.INVALID_ARGS, field + ": " + problem, false, List.of(), field);
  }
}
