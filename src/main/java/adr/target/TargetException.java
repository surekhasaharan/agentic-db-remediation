package adr.target;

/** Everything the simulated engine can refuse or lose, with a code the broker classifies. */
public class TargetException extends RuntimeException {
  public enum Code {
    NOT_ALLOWED, NOT_FOUND, LOCK_TIMEOUT, PLAN_ALREADY_APPLIED, PRECONDITION_FAILED, VALIDATION_FAILED,
    ABORTED_BEFORE_COMMIT, RESPONSE_LOST
  }

  private final Code code;

  public TargetException(Code code, String message) {
    super(code + ": " + message);
    this.code = code;
  }

  public Code code() { return code; }

  /** Explicit error before commit: nothing changed, safe to retry. */
  public static final class AbortedBeforeCommit extends TargetException {
    public AbortedBeforeCommit(String m) { super(Code.ABORTED_BEFORE_COMMIT, m); }
  }

  /** The change committed but the reply never arrived. The caller must not inspect the target. */
  public static final class ResponseLost extends TargetException {
    public ResponseLost(String m) { super(Code.RESPONSE_LOST, m); }
  }
}
