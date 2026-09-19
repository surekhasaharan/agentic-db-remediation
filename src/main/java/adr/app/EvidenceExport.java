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
      m.put("evidence", s.stores().evidence().all());
      m.put("corpus_overlay", s.stores().corpus().overlay());
      m.put("timeline", s.timeline().all());
      return m;
    }
  }
}
