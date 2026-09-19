package adr.agents;

import adr.domain.InvalidInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The three substitutions: $ev:<alias> for the evidence id a call produced in this run, $op and $finding.
 * Aliases exist only for recordings; what reaches a guard is always an evidence id.
 */
public final class Aliases {
  private Aliases() {}

  public static final String EV = "$ev:";

  public static Object substitute(Object value, Function<String, String> resolve) {
    if (value instanceof String s) return substituteString(s, resolve);
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), substitute(e.getValue(), resolve));
      return out;
    }
    if (value instanceof List<?> l) return l.stream().map(v -> substitute(v, resolve)).toList();
    return value;
  }

  static String substituteString(String s, Function<String, String> resolve) {
    if (s.equals("$op") || s.equals("$finding") || s.startsWith(EV)) {
      String r = resolve.apply(s);
      if (r == null) throw new InvalidInput("placeholder", "unresolved " + s);
      return r;
    }
    return s;
  }

  public static JsonNode substitute(JsonNode node, Function<String, String> resolve) {
    if (node == null) return null;
    if (node.isTextual()) return new TextNode(substituteString(node.asText(), resolve));
    if (node.isObject()) {
      ObjectNode out = ((ObjectNode) node).objectNode();
      node.fields().forEachRemaining(e -> out.set(e.getKey(), substitute(e.getValue(), resolve)));
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = ((ArrayNode) node).arrayNode();
      for (JsonNode n : node) out.add(substitute(n, resolve));
      return out;
    }
    return node;
  }

  /** Every $ev: alias referenced anywhere in a node. */
  public static void collectEvidenceRefs(JsonNode node, List<String> out) {
    if (node == null) return;
    if (node.isTextual() && node.asText().startsWith(EV)) out.add(node.asText().substring(EV.length()));
    else if (node.isContainerNode()) for (JsonNode n : node) collectEvidenceRefs(n, out);
  }
}
