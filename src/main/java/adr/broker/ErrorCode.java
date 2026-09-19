package adr.broker;

/**
 * Every code the broker can return. Recoverable codes need a recorded turn; stopping codes always end the run
 * through the safe stop.
 */
public enum ErrorCode {
  TOOL_NOT_ALLOWED(false), KILLED(false), INVALID_ARGS(false), UNKNOWN_REF(false), PROTECTED_TARGET(false),
  STATE_CONFLICT(false), LEASE_HELD(false), POLICY_DENIED(false), NOT_FOUND(false),
  PRECONDITION_FAILED(false), PLAN_ALREADY_APPLIED(false), VALIDATION_FAILED(false),
  RECONCILE_REQUIRED(true), TIMEOUT(true), ABORTED_BEFORE_COMMIT(true), LOCK_TIMEOUT(true);

  public final boolean recoverable;

  ErrorCode(boolean recoverable) { this.recoverable = recoverable; }
}
