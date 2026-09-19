package adr.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** The invariant counters shown at the top of the page and written to the export. */
public final class Counters {
  public final AtomicInteger mutations = new AtomicInteger();
  public final AtomicInteger duplicatesRefused = new AtomicInteger();
  public final AtomicInteger refusals = new AtomicInteger();
  public final AtomicInteger refusalsRecordedAfterCap = new AtomicInteger();
  public final AtomicInteger refusalsNotRecorded = new AtomicInteger();

  public Map<String, Object> snapshot() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("mutations", mutations.get());
    m.put("duplicates_refused", duplicatesRefused.get());
    m.put("refusals", refusals.get());
    m.put("refusals_not_recorded", refusalsNotRecorded.get());
    return m;
  }
}
