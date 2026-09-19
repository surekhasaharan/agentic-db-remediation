package adr.broker;

import java.util.Map;

/** A handler's successful result before the broker wraps it in evidence. */
public record Handled(Map<String, Object> data, String sourceLabel, String outcome) {
  public static Handled of(Map<String, Object> data, String label) { return new Handled(data, label, null); }
  public static Handled of(Map<String, Object> data, String label, String outcome) { return new Handled(data, label, outcome); }
}
