package adr.stores;

import adr.domain.Evidence;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Append-only evidence with ids the guards check. Earlier results stay under their own ids. */
public final class EvidenceLog {
  private final List<Evidence> all = new CopyOnWriteArrayList<>();
  private final ConcurrentHashMap<String, Evidence> byId = new ConcurrentHashMap<>();
  private final AtomicInteger counter = new AtomicInteger();

  public Evidence add(String tool, String sourceLabel, Object data, String runId) {
    Evidence e = new Evidence("ev-" + counter.incrementAndGet(), tool, sourceLabel, data, runId, Instant.now().toString());
    all.add(e);
    byId.put(e.id(), e);
    return e;
  }

  public Evidence get(String id) { return id == null ? null : byId.get(id); }
  public List<Evidence> all() { return List.copyOf(all); }
  public int size() { return all.size(); }
}
