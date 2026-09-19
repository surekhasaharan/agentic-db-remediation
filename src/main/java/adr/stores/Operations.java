package adr.stores;

import adr.domain.Operation;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** One operation per finding and plan hash, layer one of the three that defeat duplicate delivery. */
public final class Operations {
  private final ConcurrentHashMap<String, Operation> byKey = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Operation> byId = new ConcurrentHashMap<>();
  private final List<Operation> order = new CopyOnWriteArrayList<>();

  /** Returns the existing operation if one already exists for this finding and plan hash, else null. */
  public Operation putIfAbsent(Operation op) {
    Operation existing = byKey.putIfAbsent(op.findingId() + ":" + op.planHash(), op);
    if (existing == null) {
      byId.put(op.id(), op);
      order.add(op);
    }
    return existing;
  }

  public Operation get(UUID id) { return byId.get(id); }
  public Operation forPlan(UUID findingId, String planHash) { return byKey.get(findingId + ":" + planHash); }
  public List<Operation> all() { return List.copyOf(order); }
  public List<Operation> forFinding(UUID findingId) { return order.stream().filter(o -> o.findingId().equals(findingId)).toList(); }
}
