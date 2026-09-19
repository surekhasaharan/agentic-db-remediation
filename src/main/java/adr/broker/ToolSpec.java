package adr.broker;

import adr.domain.AgentId;
import adr.target.Caller;

import java.util.Map;
import java.util.Set;

/** A tool's contract: who may call it, as whom, its argument record, and the outcomes and codes it declares. */
public record ToolSpec(String name, Set<AgentId> agents, Caller caller, Kind kind, Class<?> argsType,
                       Set<String> outcomes, Set<ErrorCode> errors, Handler handler) {

  public enum Kind { READ, WRITE, PROBE }

  public interface Handler {
    Handled handle(RunContext run, Object args);
  }

  public boolean allows(AgentId agent) { return agents.contains(agent); }

  /** Every trigger a recording must cover when this tool is the last call of a turn. */
  public Set<String> requiredTriggers() {
    Set<String> out = new java.util.LinkedHashSet<>();
    if (outcomes.isEmpty()) out.add("ok:" + name);
    for (String o : outcomes) out.add("ok:" + name + ":" + o);
    for (ErrorCode c : errors) if (c.recoverable) out.add("error:" + name + ":" + c);
    return out;
  }

  public Map<String, Object> describe() {
    return Map.of("name", name, "kind", kind.name(), "caller", caller.name(),
        "agents", agents.stream().map(Enum::name).sorted().toList());
  }
}
