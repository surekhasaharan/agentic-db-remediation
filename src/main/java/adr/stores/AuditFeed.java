package adr.stores;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Role-change history. The out-of-band client appends to the session's copy. */
public final class AuditFeed {
  private final List<Seed.AuditRow> rows = new CopyOnWriteArrayList<>();

  public AuditFeed(Seed.AuditSeed seed) { rows.addAll(seed.rows()); }

  public List<Seed.AuditRow> all() { return List.copyOf(rows); }

  public List<Seed.AuditRow> rowsMentioning(String role) {
    return rows.stream().filter(r -> r.action().contains(role) || r.actor().equals(role)).toList();
  }

  public void append(Seed.AuditRow row) { rows.add(row); }
}
