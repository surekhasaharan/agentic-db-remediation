package adr.agents;

import adr.broker.Guards;
import adr.broker.Inputs;
import adr.broker.ToolSpec;
import adr.broker.Tools;
import adr.domain.AgentId;
import adr.domain.InvalidInput;
import adr.domain.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs before the port opens and fails the process on any violation, so a broken build never serves traffic.
 * Required coverage is "start" plus, for every tool that ends a turn, each declared outcome and each recoverable
 * error code. Stopping codes need no turn: they always end the run through the safe stop.
 */
public final class RecordingValidator {
  public record Problem(String file, String turn, String message) {
    @Override public String toString() { return file + " [" + turn + "]: " + message; }
  }

  public record Report(List<Problem> errors, List<Problem> warnings) {
    public boolean ok() { return errors.isEmpty(); }
  }

  private RecordingValidator() {}

  public static Report validate(Map<String, Recording> recordings) {
    List<Problem> errors = new ArrayList<>();
    List<Problem> warnings = new ArrayList<>();
    for (Recording r : recordings.values()) validate(r, errors, warnings);
    return new Report(errors, warnings);
  }

  static void validate(Recording r, List<Problem> errors, List<Problem> warnings) {
    String file = r.key();
    AgentId agent = r.agentId();
    Set<String> whens = new HashSet<>();
    Map<String, Recording.RawCall> aliases = new HashMap<>();
    Set<String> definedAliases = new LinkedHashSet<>();

    // Aliases first, so that "@alias" triggers and $ev: references can be checked.
    for (Recording.RawTurn t : r.turns()) {
      if (!t.hasCalls()) continue;
      for (Recording.RawCall c : t.calls()) {
        Recording.RawCall prev = aliases.get(c.as());
        if (prev != null && !(prev.tool().equals(c.tool()) && prev.args().equals(c.args()))) {
          errors.add(new Problem(file, t.when(), "alias " + c.as() + " is reused for a different call"));
        }
        aliases.put(c.as(), c);
        definedAliases.add(c.as());
      }
    }

    Set<String> covered = new HashSet<>();
    Set<String> required = new LinkedHashSet<>();
    required.add("start");
    for (Recording.RawTurn t : r.turns()) {
      if (!whens.add(t.when())) errors.add(new Problem(file, t.when(), "duplicate when"));
      checkTrigger(r, agent, t, definedAliases, errors);
      covered.add(t.when());
      if (t.hasCalls()) {
        for (Recording.RawCall c : t.calls()) {
          ToolSpec spec = Tools.get(c.tool());
          if (spec == null || !spec.allows(agent)) {
            errors.add(new Problem(file, t.when(), "call " + c.as() + " names tool " + c.tool() + " which " + agent + " may not call"));
            continue;
          }
          try {
            Inputs.parse(Aliases.substitute(c.args(), RecordingValidator::dummy), spec.argsType());
          } catch (InvalidInput e) {
            errors.add(new Problem(file, t.when(), "call " + c.as() + " has invalid arguments: " + e.getMessage()));
          }
        }
        Recording.RawCall last = t.lastCall();
        ToolSpec spec = Tools.get(last.tool());
        if (spec != null) required.addAll(spec.requiredTriggers());
      } else {
        List<String> refs = new ArrayList<>();
        Aliases.collectEvidenceRefs(t.decision(), refs);
        for (String ref : refs) {
          if (!definedAliases.contains(ref) && !ref.startsWith("retrieval:"))
            errors.add(new Problem(file, t.when(), "$ev:" + ref + " names no alias defined in this recording"));
        }
        try {
          JsonNode resolved = Aliases.substitute(t.decision(), RecordingValidator::dummy);
          RecordedModelClient.toDecision(agent, resolved);
        } catch (InvalidInput e) {
          errors.add(new Problem(file, t.when(), "decision is invalid: " + e.getMessage()));
        }
      }
    }
    for (String req : required) {
      if (!covered.contains(req) && !coveredByAlias(req, covered, aliases))
        errors.add(new Problem(file, req, "no recorded turn for required trigger " + req));
    }
    // Reachability from start, following declared outcomes.
    Set<String> reachable = new HashSet<>();
    reachable.add("start");
    boolean grew = true;
    while (grew) {
      grew = false;
      for (Recording.RawTurn t : r.turns()) {
        if (!reachable.contains(t.when())) continue;
        if (t.hasCalls()) {
          Recording.RawCall last = t.lastCall();
          ToolSpec spec = Tools.get(last.tool());
          if (spec == null) continue;
          for (String trig : spec.requiredTriggers()) grew |= reachable.add(trig);
          for (var e : spec.errors()) grew |= reachable.add("error:" + last.tool() + ":" + e);
          grew |= reachable.add("ok:" + last.tool());
          for (String o : spec.outcomes()) grew |= reachable.add("ok:" + last.tool() + ":" + o);
          for (String x : new ArrayList<>(reachable)) {
            String viaAlias = x.replace(":" + last.tool(), ":@" + last.as());
            grew |= reachable.add(viaAlias);
          }
        } else {
          for (String g : Guards.NAMES) grew |= reachable.add("guard_rejected:" + g);
        }
      }
    }
    for (Recording.RawTurn t : r.turns()) {
      if (!reachable.contains(t.when())) warnings.add(new Problem(file, t.when(), "turn is not reachable from start"));
    }
  }

  private static boolean coveredByAlias(String req, Set<String> covered, Map<String, Recording.RawCall> aliases) {
    for (var e : aliases.entrySet()) {
      String tool = e.getValue().tool();
      if (req.contains(":" + tool) && covered.contains(req.replace(":" + tool, ":@" + e.getKey()))) return true;
    }
    return false;
  }

  private static void checkTrigger(Recording r, AgentId agent, Recording.RawTurn t, Set<String> aliases, List<Problem> errors) {
    String w = t.when();
    String file = r.key();
    if (w.equals("start")) return;
    if (w.startsWith("guard_rejected:")) {
      String g = w.substring("guard_rejected:".length());
      if (!Guards.NAMES.contains(g)) errors.add(new Problem(file, w, "names no real guard"));
      return;
    }
    String[] parts = w.split(":");
    if (parts.length < 2 || !(parts[0].equals("ok") || parts[0].equals("error"))) {
      errors.add(new Problem(file, w, "trigger must be start, ok:<tool>[:outcome], error:<tool>:<CODE> or guard_rejected:<guard>"));
      return;
    }
    String toolName = parts[1];
    if (toolName.startsWith("@")) {
      String alias = toolName.substring(1);
      if (!aliases.contains(alias)) { errors.add(new Problem(file, w, "alias " + alias + " is not defined")); return; }
      toolName = null;
      for (Recording.RawTurn x : r.turns()) if (x.hasCalls()) for (Recording.RawCall c : x.calls()) if (c.as().equals(alias)) toolName = c.tool();
    }
    ToolSpec spec = Tools.get(toolName);
    if (spec == null || !spec.allows(agent)) { errors.add(new Problem(file, w, "tool " + toolName + " is not in " + agent + "'s allowlist")); return; }
    if (parts[0].equals("ok")) {
      if (parts.length == 3 && !spec.outcomes().contains(parts[2])) errors.add(new Problem(file, w, "tool " + toolName + " declares no outcome " + parts[2]));
      if (parts.length == 2 && !spec.outcomes().isEmpty()) errors.add(new Problem(file, w, "tool " + toolName + " always reports an outcome; use ok:" + toolName + ":<outcome>"));
      if (parts.length > 3) errors.add(new Problem(file, w, "malformed trigger"));
    } else {
      if (parts.length != 3) { errors.add(new Problem(file, w, "error trigger needs a code")); return; }
      boolean declared = spec.errors().stream().anyMatch(c -> c.name().equals(parts[2]));
      if (!declared) errors.add(new Problem(file, w, "tool " + toolName + " declares no error code " + parts[2]));
    }
  }

  static String dummy(String placeholder) {
    if (placeholder.equals("$op") || placeholder.equals("$finding")) return UUID.randomUUID().toString();
    return "ev-0";
  }

  public static String hashOf(Map<String, Recording> recordings) {
    StringBuilder sb = new StringBuilder();
    for (Recording r : recordings.values()) sb.append(Json.write(Json.SNAKE, r));
    return adr.target.Fingerprint.sha256(sb.toString());
  }
}
