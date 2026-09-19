package adr.app;

import adr.agents.RecordedModelClient;
import adr.agents.Recording;
import adr.agents.RecordingValidator;
import adr.broker.Policy;
import adr.stores.Seed;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Load and validate policy and seeds, validate the recordings, then (phase 6) run the golden flow. Any failure
 * exits before the port opens, so a broken build never serves traffic.
 */
public final class StartupChecks {
  public record Result(Seed seed, Policy policy, RecordedModelClient model, String recordingsHash, Map<String, Object> health) {}

  private StartupChecks() {}

  public static Result run() {
    Seed seed = Seed.load();
    Policy policy = Policy.load();
    Map<String, Recording> recordings = RecordedModelClient.loadAll();
    RecordingValidator.Report report = RecordingValidator.validate(recordings);
    for (RecordingValidator.Problem w : report.warnings()) System.out.println("recording warning: " + w);
    if (!report.ok()) {
      StringBuilder sb = new StringBuilder("recording validation failed:\n");
      for (RecordingValidator.Problem p : report.errors()) sb.append("  ").append(p).append('\n');
      throw new IllegalStateException(sb.toString());
    }
    String hash = RecordingValidator.hashOf(recordings);
    RecordedModelClient model = new RecordedModelClient(recordings);
    java.util.List<adr.workflow.GoldenFlow.Result> golden = adr.workflow.GoldenFlow.run(policy, model, seed);
    Map<String, Object> goldenReport = new LinkedHashMap<>();
    boolean goldenOk = true;
    for (adr.workflow.GoldenFlow.Result g : golden) {
      goldenReport.put(g.config(), g.ok() ? "ok" : g.detail());
      goldenOk &= g.ok();
    }
    if (!goldenOk) throw new IllegalStateException("golden-flow self-check failed: " + goldenReport);
    Map<String, Object> health = new LinkedHashMap<>();
    health.put("status", "ok");
    health.put("golden_flow", goldenReport);
    health.put("policy_version", policy.version());
    health.put("recordings_hash", hash);
    health.put("recordings", recordings.keySet());
    health.put("agent_mode", "recorded");
    health.put("target_mode", "simulated");
    return new Result(seed, policy, model, hash, health);
  }
}
