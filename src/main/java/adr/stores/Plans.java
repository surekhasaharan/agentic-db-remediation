package adr.stores;

import adr.domain.Plan;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class Plans {
  private final ConcurrentHashMap<UUID, List<Plan>> byFinding = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Plan> byHash = new ConcurrentHashMap<>();

  public void add(UUID findingId, Plan p) {
    byFinding.computeIfAbsent(findingId, k -> new CopyOnWriteArrayList<>()).add(p);
    byHash.put(p.hash(), p);
  }

  public List<Plan> forFinding(UUID findingId) { return List.copyOf(byFinding.getOrDefault(findingId, List.of())); }

  public Plan latest(UUID findingId) {
    List<Plan> l = byFinding.get(findingId);
    return l == null || l.isEmpty() ? null : l.get(l.size() - 1);
  }

  public Plan byHash(String hash) { return byHash.get(hash); }

  public int nextVersion(UUID findingId) { return forFinding(findingId).size() + 1; }
}
