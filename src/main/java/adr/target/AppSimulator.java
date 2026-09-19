package adr.target;

import adr.stores.Seed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A class, not a sidecar. Each scenario is a named list of actions run as the application role. */
public final class AppSimulator {
  public static final int RUNS = 3;

  public record ScenarioResult(String name, boolean passed, int runs, List<String> failures) {}

  private final Map<String, Seed.Scenario> scenarios = new LinkedHashMap<>();

  public AppSimulator(Seed.ScenariosSeed seed) {
    for (Seed.Scenario s : seed.scenarios()) scenarios.put(s.name(), s);
  }

  public boolean knows(String name) { return scenarios.containsKey(name); }
  public List<String> names() { return List.copyOf(scenarios.keySet()); }
  public Seed.Scenario get(String name) { return scenarios.get(name); }

  public ScenarioResult run(TargetDatabase target, String role, String name) {
    Seed.Scenario s = scenarios.get(name);
    if (s == null) return new ScenarioResult(name, false, 0, List.of("unknown scenario"));
    List<String> failures = new ArrayList<>();
    for (int run = 1; run <= RUNS; run++) {
      for (Seed.Action a : s.actions()) {
        Privileges.ProbeResult r = target.attempt(Caller.orders_app, role, new Privileges.SqlAction(a.privilege(), a.object()));
        if (!r.ok()) {
          failures.add("run " + run + ": " + a.privilege() + " " + a.object() + " -> " + r.sqlstate() + " (" + r.detail() + ")");
          break;
        }
      }
    }
    return new ScenarioResult(name, failures.isEmpty(), RUNS, failures);
  }
}
