package adr.stores;

import adr.domain.Approval;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Approvals {
  private final List<Approval> all = new CopyOnWriteArrayList<>();

  public void add(Approval a) { all.add(a); }
  public List<Approval> all() { return List.copyOf(all); }
  public List<Approval> forFinding(UUID findingId) { return all.stream().filter(a -> a.findingId().equals(findingId)).toList(); }

  /** The one open request for a finding, if any. */
  public Approval open(UUID findingId) {
    return all.stream().filter(a -> a.findingId().equals(findingId) && a.status() == Approval.Status.REQUESTED)
        .reduce((a, b) -> b).orElse(null);
  }

  public Approval latest(UUID findingId) {
    List<Approval> l = forFinding(findingId);
    return l.isEmpty() ? null : l.get(l.size() - 1);
  }
}
