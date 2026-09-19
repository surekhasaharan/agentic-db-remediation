package adr.app;

import adr.TestSession;
import adr.domain.FindingState;
import adr.domain.Json;
import adr.domain.Labels;
import adr.domain.TimelineEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** I13: nothing simulated is unlabelled. Checked by scanning the export of a full run. */
class LabelsTest {

  @Test
  @SuppressWarnings("unchecked")
  void everythingSimulatedIsLabelled() {
    TestSession t = new TestSession();
    t.startAnalysis();
    String hash = t.session.stores().plans().latest(t.findingId()).hash();
    t.send("request_approval", "requester", t.args("finding_id", t.findingId().toString(), "plan_hash", hash));
    t.send("approve", "dba", t.args("finding_id", t.findingId().toString(), "plan_hash", hash));
    assertEquals(FindingState.CLOSED, t.state());

    Map<String, Object> export = EvidenceExport.of(t.session);
    assertEquals("recorded", export.get("agent_mode"));
    assertEquals("simulated", export.get("target_mode"));
    assertEquals("demo_persona", export.get("identity_mode"));
    assertEquals("in_memory_session", export.get("persistence"));
    assertEquals("authored", export.get("captured"));
    Map<String, Object> labels = (Map<String, Object>) export.get("labels");
    assertEquals(Labels.BANNER, labels.get("banner"));
    assertEquals(Labels.PERSONA_TAG, labels.get("persona_tag"));

    List<TimelineEvent> timeline = (List<TimelineEvent>) export.get("timeline");
    int decisions = 0, targetEvidence = 0;
    for (TimelineEvent e : timeline) {
      assertEquals("recorded", e.agentMode(), e.kind());
      assertEquals("simulated", e.targetMode(), e.kind());
      assertNotNull(e.sourceLabel(), e.kind());
      if (e.kind().equals("agent.decision")) {
        decisions++;
        assertEquals(true, e.payload().get("recorded"));
      }
    }
    assertEquals(4, decisions, "analysis A and B, remediation, verification");
    List<Map<String, Object>> evidence = (List<Map<String, Object>>) export.get("evidence");
    for (Map<String, Object> ev : evidence) {
      String label = (String) ev.get("source_label");
      assertNotNull(label);
      if (label.equals(Labels.SIMULATED_PG) || label.equals(Labels.APP_SIM)) {
        targetEvidence++;
        assertEquals("simulated", ev.get("target_mode"));
      }
    }
    assertTrue(targetEvidence >= 8, "target evidence items: " + targetEvidence);
    String json = Json.write(Json.SNAKE, export);
    assertTrue(json.contains("\"source_label\":\"Simulated PostgreSQL\""));
    assertTrue(json.contains("demo persona, not authenticated"));
    assertFalse(json.contains("\"durable\""));
    Map<String, Object> state = Snapshot.of(t.session);
    assertEquals("authored", ((Map<?, ?>) state.get("labels")).get("captured"));
  }
}
