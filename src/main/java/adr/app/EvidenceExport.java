package adr.app;

import adr.domain.Labels;

import java.util.LinkedHashMap;
import java.util.Map;

/** The evidence export: every finding, plan, approval, operation, evidence item and event, with the labels. */
public final class EvidenceExport {
  private EvidenceExport() {}

  public static Map<String, Object> of(Session s) {
    synchronized (s.lock()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("export", "adr-demo evidence");
      m.put("session_epoch", s.epoch());
      m.put("persistence", Labels.PERSISTENCE);
      m.put("agent_mode", Labels.AGENT_MODE);
      m.put("target_mode", Labels.TARGET_MODE);
      m.put("identity_mode", Labels.IDENTITY_MODE);
      m.put("captured", "authored");
      m.put("labels", Snapshot.labels());
      m.put("counters", s.counters().snapshot());
      m.put("findings", Snapshot.of(s).get("findings"));
      m.put("approvals", s.stores().approvals().all().stream().map(Snapshot::approval).toList());
      m.put("operations", s.stores().operations().all().stream().map(Snapshot::operation).toList());
      java.util.List<Map<String, Object>> evidence = new java.util.ArrayList<>();
      for (adr.domain.Evidence e : s.stores().evidence().all()) {
        Map<String, Object> em = new LinkedHashMap<>();
        em.put("id", e.id());
        em.put("tool", e.tool());
        em.put("source_label", e.sourceLabel());
        boolean target = Labels.SIMULATED_PG.equals(e.sourceLabel()) || Labels.APP_SIM.equals(e.sourceLabel());
        em.put("target_mode", target ? Labels.TARGET_MODE : null);
        em.put("run_id", e.runId());
        em.put("ts", e.ts());
        em.put("data", e.data());
        evidence.add(em);
      }
      m.put("evidence", evidence);
      m.put("plans", s.stores().plans().forFinding(s.activeFindingId()).stream().map(Snapshot::plan).toList());
      m.put("corpus_overlay", s.stores().corpus().overlay());
      m.put("timeline", s.timeline().all());
      return m;
    }
  }
}
