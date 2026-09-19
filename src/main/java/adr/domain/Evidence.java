package adr.domain;

/** One tool result, kept forever in the session. Guards work on evidence ids, never on aliases. */
public record Evidence(String id, String tool, String sourceLabel, Object data, String runId, String ts) {}
