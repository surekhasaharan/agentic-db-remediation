package adr.workflow;

import adr.app.Session;
import adr.broker.Policy;
import adr.domain.Actor;
import adr.domain.ActorType;
import adr.domain.Approval;
import adr.domain.FindingState;
import adr.domain.Labels;
import adr.domain.Operation;
import adr.domain.Plan;
import adr.domain.TimelineEvent;
import adr.stores.Findings;
import adr.stores.Lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * An approval binds to one plan hash. Persona, separation of duties, hash binding, expiry and voiding all
 * execute; what D0 does not do is authenticate the person, and every approval says so.
 */
public final class ApprovalService {
  private final Policy policy;
  /** The clock, replaceable by tests to exercise expiry. */
  public volatile Supplier<Instant> clock = Instant::now;

  public ApprovalService(Policy policy) { this.policy = policy; }

  public record Outcome(Workflow.Refusal refusal, Operation operation) {
    static Outcome refused(int status, String code, String message) { return new Outcome(new Workflow.Refusal(status, code, message), null); }
  }

  public Workflow.Refusal request(Session s, UUID findingId, String planHash, Actor actor) {
    Plan latest = s.stores().plans().latest(findingId);
    if (latest == null || !latest.hash().equals(planHash)) {
      gate(s, findingId, "approval.plan_binding", false, "the hash does not name the latest plan version" + (latest == null ? "" : " " + latest.hash().substring(0, 8)));
      return new Workflow.Refusal(409, "PLAN_HASH_MISMATCH", "plan_hash must equal the finding's latest plan version");
    }
    Instant now = clock.get();
    Approval approval = new Approval(UUID.randomUUID(), findingId, planHash, actor, now,
        now.plus(Duration.ofMinutes(policy.approval().expiryMinutes())));
    Lifecycle.Result r = Lifecycle.transition(s, s.stores(), findingId, FindingState.PLAN_READY, FindingState.AWAITING_APPROVAL,
        "approval requested for plan " + planHash.substring(0, 8), () -> {
          s.stores().approvals().add(approval);
          Map<String, Object> p = new LinkedHashMap<>();
          p.put("approval", adr.app.Snapshot.approval(approval));
          p.put("expires_in_minutes", policy.approval().expiryMinutes());
          return List.of(Workflow.human(findingId, actor, "approve", "approval.requested",
              actor.displayName() + " (" + actor.role() + ", " + Labels.PERSONA_TAG + ") requested approval of plan " + planHash.substring(0, 8), p));
        });
    if (r != Lifecycle.Result.OK) return new Workflow.Refusal(409, "LOST_RACE", "The finding moved before the command ran");
    return null;
  }

  public Outcome approve(Session s, UUID findingId, String planHash, Actor actor, String comment) {
    Approval a = s.stores().approvals().open(findingId);
    if (a == null) return Outcome.refused(409, "NO_OPEN_APPROVAL", "there is no open approval request");
    Instant now = clock.get();

    boolean sod = !policy.approval().separationOfDuties() || !actor.id().equals(a.requester().id());
    gate(s, findingId, "approval.separation_of_duties", sod, sod ? "approver " + actor.id() + " is not the requester " + a.requester().id()
        : "the requester " + a.requester().displayName() + " cannot approve their own request");
    if (!sod) return Outcome.refused(409, "SEPARATION_OF_DUTIES", "the requester cannot approve");

    boolean persona = policy.approval().approverPersonas().contains(actor.persona());
    gate(s, findingId, "approval.persona_allowed", persona, persona ? actor.persona() + " may approve" : actor.persona() + " is not an approver persona " + policy.approval().approverPersonas());
    if (!persona) return Outcome.refused(409, "PERSONA_NOT_ALLOWED", "only " + policy.approval().approverPersonas() + " may approve");

    Plan latest = s.stores().plans().latest(findingId);
    boolean bound = planHash.equals(a.planHash()) && latest != null && latest.hash().equals(planHash);
    gate(s, findingId, "approval.plan_binding", bound, bound ? "hash " + planHash.substring(0, 8) + " equals the requested and latest plan" : "hash does not equal the requested plan " + a.planHash().substring(0, 8));
    if (!bound) return Outcome.refused(409, "PLAN_HASH_MISMATCH", "plan_hash must equal the approved plan version");

    boolean fresh = !a.isExpired(now);
    gate(s, findingId, "approval.expiry", fresh, fresh ? "requested " + Duration.between(a.requestedAt(), now).toMinutes() + " minutes ago, within " + policy.approval().expiryMinutes()
        : "the request expired at " + a.expiresAt());
    if (!fresh) {
      a.mark(Approval.Status.EXPIRED, now);
      Lifecycle.transition(s, s.stores(), findingId, FindingState.AWAITING_APPROVAL, FindingState.PLAN_READY, "approval expired", Lifecycle.Effects.NONE);
      return Outcome.refused(409, "APPROVAL_EXPIRED", "the approval request expired; request it again");
    }

    Findings.Entry entry = s.stores().findings().get(findingId);
    Operation op = new Operation(UUID.randomUUID(), findingId, planHash);
    Operation existing = s.stores().operations().putIfAbsent(op);
    Operation use = existing == null ? op : existing;
    Lifecycle.Result r = Lifecycle.transition(s, s.stores(), findingId, FindingState.AWAITING_APPROVAL, FindingState.REMEDIATING,
        "approved by " + actor.displayName() + ", operation " + use.id().toString().substring(0, 8) + " created", () -> {
          a.decide(Approval.Status.APPROVED, actor, comment, now);
          entry.currentOperationId = use.id();
          List<TimelineEvent> ev = new ArrayList<>();
          Map<String, Object> p = new LinkedHashMap<>();
          p.put("approval", adr.app.Snapshot.approval(a));
          p.put("decision", "approved");
          p.put("comment", comment);
          p.put("plan_hash", planHash);
          ev.add(Workflow.human(findingId, actor, "approve", "approval.decided",
              "Approved by " + actor.displayName() + ", " + actor.role() + " (" + Labels.PERSONA_TAG + "), plan " + planHash.substring(0, 8), p));
          if (existing != null) {
            ev.add(TimelineEvent.of(findingId, ActorType.gate, "execution_guard", "remediate", "gate.checked",
                "operation.unique: REFUSED. Duplicate trigger for plan " + planHash.substring(0, 8) + ", existing operation reused",
                Map.of("rule", "operation.unique", "result", "REFUSED", "operation_id", existing.id().toString()), Labels.PLATFORM));
          } else {
            ev.add(TimelineEvent.of(findingId, ActorType.gate, "execution_guard", "remediate", "operation.created",
                "operation.unique: PASS. Operation " + op.id().toString().substring(0, 8) + " created in APPROVED for plan " + planHash.substring(0, 8),
                Map.of("rule", "operation.unique", "result", "PASS", "operation_id", op.id().toString(), "plan_hash", planHash), Labels.PLATFORM));
          }
          return ev;
        });
    if (r != Lifecycle.Result.OK) return Outcome.refused(409, "LOST_RACE", "The finding moved before the command ran");
    return new Outcome(null, use);
  }

  public Workflow.Refusal reject(Session s, UUID findingId, String planHash, Actor actor, String comment) {
    Approval a = s.stores().approvals().open(findingId);
    if (a == null) return new Workflow.Refusal(409, "NO_OPEN_APPROVAL", "there is no open approval request");
    if (!planHash.equals(a.planHash())) {
      gate(s, findingId, "approval.plan_binding", false, "hash does not equal the requested plan");
      return new Workflow.Refusal(409, "PLAN_HASH_MISMATCH", "plan_hash must equal the requested plan version");
    }
    Instant now = clock.get();
    Lifecycle.Result r = Lifecycle.transition(s, s.stores(), findingId, FindingState.AWAITING_APPROVAL, FindingState.PLAN_READY,
        "rejected by " + actor.displayName(), () -> {
          a.decide(Approval.Status.REJECTED, actor, comment, now);
          Map<String, Object> p = new LinkedHashMap<>();
          p.put("approval", adr.app.Snapshot.approval(a));
          p.put("decision", "rejected");
          p.put("comment", comment);
          return List.of(Workflow.human(findingId, actor, "approve", "approval.decided",
              "Rejected by " + actor.displayName() + ", " + actor.role() + " (" + Labels.PERSONA_TAG + "), plan " + planHash.substring(0, 8), p));
        });
    if (r != Lifecycle.Result.OK) return new Workflow.Refusal(409, "LOST_RACE", "The finding moved before the command ran");
    return null;
  }

  /** Voids every open approval whose hash is not the current plan's. Called inside a transition's effects. */
  public static List<TimelineEvent> voidOpen(Session s, UUID findingId, String reason) {
    List<TimelineEvent> out = new ArrayList<>();
    for (Approval ap : s.stores().approvals().forFinding(findingId)) {
      if (ap.status() == Approval.Status.REQUESTED || ap.status() == Approval.Status.APPROVED) {
        ap.mark(Approval.Status.VOIDED, Instant.now());
        out.add(TimelineEvent.of(findingId, ActorType.gate, "approval_service", "approve", "approval.voided",
            "approval.plan_binding: VOIDED. " + reason, Map.of("rule", "approval.plan_binding", "result", "VOIDED",
                "approval_id", ap.id().toString(), "plan_hash", ap.planHash()), Labels.PLATFORM));
      }
    }
    return out;
  }

  private static void gate(Session s, UUID findingId, String rule, boolean ok, String summary) {
    Workflow.gate(s, findingId, "approve", rule, ok, summary);
  }
}
