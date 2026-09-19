package adr.stores;

import adr.domain.Actor;
import adr.domain.DriftObservation;
import adr.domain.Finding;
import adr.domain.FindingState;
import adr.domain.VerificationRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Findings and their states. casState is package-private: Lifecycle is its only caller. */
public final class Findings {

  public static final class Entry {
    public final Finding finding;
    private final AtomicReference<FindingState> state = new AtomicReference<>(FindingState.OPEN);
    public volatile String sealedFingerprint;
    public volatile Map<String, Object> sealedSnapshot;
    public volatile DriftObservation drift;
    public volatile String attentionReason;
    public volatile Actor requester;
    public volatile UUID currentOperationId;
    public volatile VerificationRecord verification;
    public volatile Long reopenedBySeq;

    Entry(Finding f) { this.finding = f; }

    public FindingState state() { return state.get(); }
  }

  private final ConcurrentHashMap<UUID, Entry> byId = new ConcurrentHashMap<>();
  private final List<UUID> order = new CopyOnWriteArrayList<>();

  public Entry get(UUID id) {
    Entry e = byId.get(id);
    if (e == null) throw new IllegalArgumentException("unknown finding " + id);
    return e;
  }

  public Entry find(UUID id) { return id == null ? null : byId.get(id); }

  public boolean contains(UUID id) { return id != null && byId.containsKey(id); }

  public FindingState stateOf(UUID id) { return get(id).state(); }

  public List<Entry> all() {
    List<Entry> out = new ArrayList<>();
    for (UUID id : order) out.add(byId.get(id));
    return out;
  }

  public List<Entry> inState(FindingState s) {
    return all().stream().filter(e -> e.state() == s).toList();
  }

  public List<Entry> childrenOf(UUID parent) {
    return all().stream().filter(e -> parent.equals(e.finding.parentFindingId())).toList();
  }

  /** Only from Lifecycle effects (inside the session lock) and from the seed copy. */
  public void add(Finding f) {
    if (byId.putIfAbsent(f.id(), new Entry(f)) != null) throw new IllegalStateException("duplicate finding " + f.id());
    order.add(f.id());
  }

  /** Package-private on purpose. One caller: Lifecycle.transition. */
  boolean casState(UUID id, FindingState from, FindingState to) {
    return get(id).state.compareAndSet(from, to);
  }
}
