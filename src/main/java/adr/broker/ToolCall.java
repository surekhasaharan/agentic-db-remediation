package adr.broker;

import java.util.Map;

/** One call as an agent turn states it. The alias exists only for recordings. */
public record ToolCall(String alias, String tool, Map<String, Object> args) {}
