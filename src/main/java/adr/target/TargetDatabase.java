package adr.target;

import adr.domain.PlanExec;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The target extension point. D0: SimulatedPgTarget. Later: JdbcPostgresTarget. */
public interface TargetDatabase {

  record CatalogObject(String kind, String schema, String name, String key, String owner, boolean publicExecute,
                       List<PgState.Requirement> requires) {}

  record Check(String name, boolean passed, String detail) {}

  record Preflight(boolean passed, List<Check> checks, String liveFingerprint) {}

  record ApplyResult(boolean alreadyApplied, PgState.LedgerRow row, String fingerprintAfter) {}

  record StatusResult(boolean inFlight, boolean found, String fingerprint, PgState.LedgerRow row) {
    public static StatusResult stillInFlight() { return new StatusResult(true, false, null, null); }
  }

  Map<String, Object> snapshot(Caller as, String role);
  String fingerprint(Caller as, String role);
  CatalogObject describe(Caller as, String schema, String name, String kind);
  Preflight preflight(Caller as, PlanExec exec);
  /** One transaction: the change and its idempotency marker commit or vanish together. */
  ApplyResult apply(Caller as, UUID operationId, PlanExec exec, CommitHook hook);
  /** Takes the same lock as apply, so it never reads an in-flight write. */
  StatusResult operationStatus(Caller as, UUID operationId);
  /** Used only by the out-of-band client. */
  void grantMembership(Caller as, String role, String member);
  Privileges.ProbeResult attempt(Caller as, String role, Privileges.SqlAction action);
}
