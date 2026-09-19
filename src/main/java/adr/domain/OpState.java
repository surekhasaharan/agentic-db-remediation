package adr.domain;

/** Immutable state, attempt and lease owner. Every transition is a compareAndSet on this record. */
public record OpState(OperationState state, int attempt, String leaseOwner, boolean retryable) {
  public static OpState approved() { return new OpState(OperationState.APPROVED, 0, null, false); }

  public OpState executing(String owner) { return new OpState(OperationState.EXECUTING, attempt + 1, owner, false); }
  public OpState applied() { return new OpState(OperationState.APPLIED, attempt, null, false); }
  public OpState failedNotApplied(boolean retryable) { return new OpState(OperationState.FAILED_NOT_APPLIED, attempt, null, retryable); }
  public OpState outcomeUnknown() { return new OpState(OperationState.OUTCOME_UNKNOWN, attempt, null, false); }
  public OpState needsHuman() { return new OpState(OperationState.NEEDS_HUMAN, attempt, null, false); }
}
