package adr.workflow;

import adr.app.Session;
import adr.broker.ErrorCode;
import adr.broker.Handled;
import adr.broker.RunContext;
import adr.broker.ToolError;
import adr.broker.ToolException;
import adr.chaos.Chaos;
import adr.domain.ActorType;
import adr.domain.Delivery;
import adr.domain.Labels;
import adr.domain.OpState;
import adr.domain.Operation;
import adr.domain.OperationState;
import adr.domain.PlanExec;
import adr.domain.TimelineEvent;
import adr.target.Caller;
import adr.target.CommitHook;
import adr.target.TargetDatabase;
import adr.target.TargetException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Layers two and three of duplicate-delivery defence, and the outcome classification. One executor at a time
 * through a compare-and-set lease; the target's ledger under its lock catches anything that slips past.
 */
public final class ExecutionGuard {
  public static final long APPLY_DEADLINE_MS = 8000;

  private static final ExecutorService applies = Executors.newVirtualThreadPerTaskExecutor();
  private static final ExecutorService racers = Executors.newVirtualThreadPerTaskExecutor();

  private ExecutionGuard() {}

  /** The lease. A failed compare is LEASE_HELD; an ineligible state is STATE_CONFLICT. */
  public static boolean tryAcquire(Operation op, String invocationId) {
    OpState cur = op.state();
    boolean eligible = cur.state() == OperationState.APPROVED
        || (cur.state() == OperationState.FAILED_NOT_APPLIED && cur.retryable() && cur.attempt() < Operation.MAX_ATTEMPTS);
    if (!eligible) return false;
    return op.compareAndSet(cur, cur.executing(invocationId));
  }

  public static Handled execute(RunContext run, Operation op, PlanExec exec) {
    Session s = run.session;
    String primary = "inv-" + UUID.randomUUID().toString().substring(0, 8);
    String winner;
    if (Chaos.duplicateDelivery(s, run.findingId, op.id())) {
      winner = raceLease(s, run, op, primary);
      if (winner == null) throw new ToolException(ToolError.of(ErrorCode.STATE_CONFLICT,
          "operation is " + op.state().state() + " and cannot be executed"));
    } else {
      if (!tryAcquire(op, primary)) {
        OpState cur = op.state();
        if (cur.state() == OperationState.EXECUTING)
          throw new ToolException(ToolError.of(ErrorCode.LEASE_HELD, "lease held by " + cur.leaseOwner()));
        throw new ToolException(ToolError.of(ErrorCode.STATE_CONFLICT, "operation is " + cur.state() + " and cannot be executed"));
      }
      op.deliveries().add(new Delivery(primary, Delivery.WON));
      winner = primary;
    }
    stateEvent(s, run, op, OperationState.EXECUTING, "Executing: lease " + winner + " won, attempt " + op.state().attempt());

    CommitHook hook = Chaos.hook(s, run.findingId, op.id());
    Future<TargetDatabase.ApplyResult> f = applies.submit(() -> s.target().apply(Caller.agent_remediator, op.id(), exec, hook));
    try {
      TargetDatabase.ApplyResult r = f.get(APPLY_DEADLINE_MS, TimeUnit.MILLISECONDS);
      return applied(s, run, op, r.alreadyApplied(), r.row(), r.fingerprintAfter());
    } catch (TimeoutException e) {
      return unknown(s, run, op, "no reply within " + APPLY_DEADLINE_MS + "ms");
    } catch (ExecutionException e) {
      Throwable c = e.getCause();
      if (c instanceof TargetException te) return classify(s, run, op, te);
      throw new IllegalStateException(c);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unknown(s, run, op, "interrupted while waiting for the reply");
    }
  }

  /** One row per outcome. On ResponseLost the broker never inspects the target. */
  private static Handled classify(Session s, RunContext run, Operation op, TargetException te) {
    return switch (te.code()) {
      case RESPONSE_LOST -> unknown(s, run, op, "the reply was lost after the call was sent");
      case LOCK_TIMEOUT, ABORTED_BEFORE_COMMIT -> {
        OpState cur = op.state();
        op.compareAndSet(cur, cur.failedNotApplied(true));
        op.lastError(te.getMessage());
        ErrorCode code = te.code() == TargetException.Code.LOCK_TIMEOUT ? ErrorCode.LOCK_TIMEOUT : ErrorCode.ABORTED_BEFORE_COMMIT;
        stateEvent(s, run, op, OperationState.FAILED_NOT_APPLIED, "Failed before commit, nothing changed, retryable: " + code);
        throw new ToolException(ToolError.of(code, te.getMessage(), true, List.of("apply_right_size_role")));
      }
      case PRECONDITION_FAILED, PLAN_ALREADY_APPLIED, VALIDATION_FAILED -> {
        OpState cur = op.state();
        op.compareAndSet(cur, cur.failedNotApplied(false));
        op.lastError(te.getMessage());
        ErrorCode code = ErrorCode.valueOf(te.code().name());
        stateEvent(s, run, op, OperationState.FAILED_NOT_APPLIED, "Failed before commit, not retryable: " + code + ". The plan returns to analysis.");
        throw new ToolException(ToolError.of(code, te.getMessage(), false, List.of()));
      }
      default -> throw new ToolException(ToolError.of(ErrorCode.STATE_CONFLICT, te.getMessage()));
    };
  }

  public static Handled applied(Session s, RunContext run, Operation op, boolean alreadyApplied, adr.target.PgState.LedgerRow row, String after) {
    OpState cur = op.state();
    if (cur.state() != OperationState.APPLIED) op.compareAndSet(cur, cur.applied());
    boolean counted = !alreadyApplied && !op.mutationCounted();
    if (counted) {
      op.mutationCounted(true);
      s.counters().mutations.incrementAndGet();
    }
    stateEvent(s, run, op, OperationState.APPLIED, alreadyApplied
        ? "Applied: the ledger already held this operation, no second effect"
        : "Applied: change and ledger row committed together", Map.of("mutation_counted", counted));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("operation_id", op.id().toString());
    data.put("applied", true);
    data.put("already_applied", alreadyApplied);
    data.put("ledger_row", row);
    data.put("fingerprint_after", after);
    return Handled.of(data, Labels.SIMULATED_PG);
  }

  private static Handled unknown(Session s, RunContext run, Operation op, String why) {
    OpState cur = op.state();
    op.compareAndSet(cur, cur.outcomeUnknown());
    op.lastError(why);
    stateEvent(s, run, op, OperationState.OUTCOME_UNKNOWN, "Outcome unknown: the database did not confirm the commit. Only get_operation_status is allowed now.");
    throw new ToolException(ToolError.of(ErrorCode.TIMEOUT, why, false, List.of("get_operation_status")));
  }

  /**
   * Only the lease step is raced. Both invocations wait on one latch, each tries the compare-and-set, and the
   * winner is decided by its return value. The events are appended after the join, winner first.
   */
  static String raceLease(Session s, RunContext run, Operation op, String primary) {
    String duplicate = "inv-" + UUID.randomUUID().toString().substring(0, 8);
    CountDownLatch latch = new CountDownLatch(1);
    Future<Boolean> r1 = racers.submit(() -> { latch.await(); return tryAcquire(op, primary); });
    Future<Boolean> r2 = racers.submit(() -> { latch.await(); return tryAcquire(op, duplicate); });
    latch.countDown();
    boolean w1, w2;
    try {
      w1 = r1.get(2, TimeUnit.SECONDS);
      w2 = r2.get(2, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("lease race did not complete", e);
    }
    if (!w1 && !w2) return null;
    String winner = w1 ? primary : duplicate;
    String loser = w1 ? duplicate : primary;
    op.deliveries().add(new Delivery(winner, Delivery.WON));
    op.deliveries().add(new Delivery(loser, Delivery.LEASE_HELD));
    s.timeline().append(TimelineEvent.of(run.findingId, ActorType.gate, "execution_guard", "remediate", "lease.won",
        "lease.compare_and_set: WON by " + winner, Map.of("rule", "lease.compare_and_set", "result", "PASS",
            "winner", winner, "operation_id", op.id().toString()), Labels.PLATFORM));
    s.counters().duplicatesRefused.incrementAndGet();
    s.timeline().append(TimelineEvent.of(run.findingId, ActorType.gate, "execution_guard", "remediate", "delivery.duplicate_refused",
        "delivery.duplicate: REFUSED, LEASE_HELD by " + winner, Map.of("rule", "delivery.duplicate", "result", "REFUSED",
            "loser", loser, "holder", winner, "operation_id", op.id().toString()), Labels.PLATFORM));
    return winner;
  }

  public static void stateEvent(Session s, RunContext run, Operation op, OperationState to, String summary) {
    stateEvent(s, run, op, to, summary, Map.of());
  }

  public static void stateEvent(Session s, RunContext run, Operation op, OperationState to, String summary, Map<String, Object> extra) {
    List<String> h = op.history();
    String from = h.size() >= 2 ? h.get(h.size() - 2) : h.get(0);
    Map<String, Object> p = new LinkedHashMap<>(extra);
    p.put("rule", "operation.transition");
    p.put("result", "PASS");
    p.put("operation_id", op.id().toString());
    p.put("from", from);
    p.put("to", to.name());
    p.put("attempt", op.state().attempt());
    s.timeline().append(TimelineEvent.of(run.findingId, ActorType.gate, "execution_guard", "remediate", "operation.state_changed",
        summary, p, Labels.PLATFORM));
  }
}
