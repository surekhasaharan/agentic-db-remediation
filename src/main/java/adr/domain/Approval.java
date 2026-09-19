package adr.domain;

import java.time.Instant;
import java.util.UUID;

/** An approval request bound to one plan hash. Only ApprovalService changes its status. */
public final class Approval {
  public enum Status { REQUESTED, APPROVED, REJECTED, EXPIRED, VOIDED }

  private final UUID id;
  private final UUID findingId;
  private final String planHash;
  private final Actor requester;
  private final Instant requestedAt;
  private final Instant expiresAt;
  private volatile Status status = Status.REQUESTED;
  private volatile Actor approver;
  private volatile String comment;
  private volatile Instant decidedAt;

  public Approval(UUID id, UUID findingId, String planHash, Actor requester, Instant requestedAt, Instant expiresAt) {
    this.id = Require.nonNull(id, "approval.id");
    this.findingId = Require.nonNull(findingId, "approval.finding_id");
    this.planHash = Require.matches(planHash, Require.HEX64, "approval.plan_hash");
    this.requester = Require.nonNull(requester, "approval.requester");
    this.requestedAt = Require.nonNull(requestedAt, "approval.requested_at");
    this.expiresAt = Require.nonNull(expiresAt, "approval.expires_at");
  }

  public UUID id() { return id; }
  public UUID findingId() { return findingId; }
  public String planHash() { return planHash; }
  public Actor requester() { return requester; }
  public Instant requestedAt() { return requestedAt; }
  public Instant expiresAt() { return expiresAt; }
  public Status status() { return status; }
  public Actor approver() { return approver; }
  public String comment() { return comment; }
  public Instant decidedAt() { return decidedAt; }

  public boolean isExpired(Instant now) { return now.isAfter(expiresAt); }

  public void decide(Status s, Actor by, String comment, Instant at) {
    this.status = s;
    this.approver = by;
    this.comment = comment;
    this.decidedAt = at;
  }

  public void mark(Status s, Instant at) {
    this.status = s;
    this.decidedAt = at;
  }
}
