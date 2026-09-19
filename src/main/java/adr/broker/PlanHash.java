package adr.broker;

import adr.domain.GrantSpec;
import adr.domain.Json;
import adr.domain.PlanExec;
import adr.target.Fingerprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sorts grants and scenarios, serialises the fields in a fixed order with ORDER_MAP_ENTRIES_BY_KEYS, and takes
 * SHA-256. Includes finding_id and expected_before, so a plan for another finding or another before-state has
 * another hash.
 */
public final class PlanHash {
  private PlanHash() {}

  public static String of(PlanExec exec) {
    Map<String, Object> m = new TreeMap<>();
    m.put("finding_id", exec.findingId().toString());
    m.put("target_role", exec.targetRole());
    m.put("owner_role", exec.ownerRole());
    List<Map<String, Object>> grants = new ArrayList<>();
    for (GrantSpec g : exec.grants()) {
      Map<String, Object> gm = new TreeMap<>();
      gm.put("privilege", g.privilege());
      gm.put("kind", g.kind());
      gm.put("object", g.object());
      grants.add(gm);
    }
    m.put("grants", grants);
    m.put("scenarios", exec.scenarios());
    m.put("expected_before", exec.expectedBefore());
    return Fingerprint.sha256(Json.write(Json.SNAKE, m));
  }
}
