package adr.target;

import adr.stores.AuditFeed;
import adr.stores.Seed;

/**
 * Reaches the target without touching the broker, which is what makes the drift change genuinely out of band
 * inside one process. Acts as dba_oncall, who holds admin option on the owner role.
 */
public final class OutOfBandClient {
  private OutOfBandClient() {}

  public static Seed.AuditRow regrant(TargetDatabase target, AuditFeed audit, String role, String member) {
    target.grantMembership(Caller.dba_oncall, role, member);
    Seed.AuditRow row = new Seed.AuditRow(0, Caller.dba_oncall.name(), "GRANT " + role + " TO " + member,
        "On-call incident: restored the old access to unblock a job", "INC-2291");
    audit.append(row);
    return row;
  }
}
