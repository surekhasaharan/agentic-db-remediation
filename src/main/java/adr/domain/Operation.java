package adr.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** One execution attempt series for one approved plan. Ends at APPLIED; verification belongs to the finding. */
public final class Operation {
  public static final int MAX_ATTEMPTS = 3;
  public static final int MAX_RECONCILIATIONS = 3;

  private final UUID id;
  private final UUID findingId;
  private final String planHash;
  private final Instant createdAt;
  private final AtomicReference<OpState> state = new AtomicReference<>(OpState.approved());
  private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
  private final List<String> history = new CopyOnWriteArrayList<>(List.of(OperationState.APPROVED.name()));
  private final AtomicInteger reconciliations = new AtomicInteger();
  private volatile boolean preflightPassed = false;
  private volatile boolean mutationCounted = false;
  private volatile String lastError;

  public Operation(UUID id, UUID findingId, String planHash) {
    this.id = Require.nonNull(id, "operation.id");
    this.findingId = Require.nonNull(findingId, "operation.finding_id");
    this.planHash = Require.matches(planHash, Require.HEX64, "operation.plan_hash");
    this.createdAt = Instant.now();
  }

  public UUID id() { return id; }
  public UUID findingId() { return findingId; }
  public String planHash() { return planHash; }
  public Instant createdAt() { return createdAt; }
  public OpState state() { return state.get(); }
  public List<Delivery> deliveries() { return deliveries; }
  public List<String> history() { return history; }
  public AtomicInteger reconciliations() { return reconciliations; }
  public boolean preflightPassed() { return preflightPassed; }
  public void preflightPassed(boolean v) { preflightPassed = v; }
  public boolean mutationCounted() { return mutationCounted; }
  public void mutationCounted(boolean v) { mutationCounted = v; }
  public String lastError() { return lastError; }
  public void lastError(String e) { lastError = e; }

  /** The only way to move. A failed compare is handled at each call site. */
  public boolean compareAndSet(OpState expected, OpState next) {
    boolean ok = state.compareAndSet(expected, next);
    if (ok && expected.state() != next.state()) history.add(next.state().name());
    return ok;
  }
}
