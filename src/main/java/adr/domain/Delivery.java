package adr.domain;

/** One delivery of a write tool call and what the lease said to it. */
public record Delivery(String invocationId, String result) {
  public static final String WON = "WON";
  public static final String LEASE_HELD = "LEASE_HELD";
}
