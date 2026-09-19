package adr.workflow;

import adr.TestSession;
import adr.agents.ModelClient;
import adr.agents.Turn;
import adr.app.Commands;
import adr.broker.ErrorCode;
import adr.broker.RunContext;
import adr.broker.ToolCall;
import adr.broker.ToolResult;
import adr.domain.AgentId;
import adr.domain.Approval;
import adr.domain.Delivery;
import adr.domain.FindingState;
import adr.domain.Operation;
import adr.domain.OperationState;
import adr.domain.TimelineEvent;
import adr.target.Caller;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The remediation stage with every fault seam. Recorded agents, real broker, real guards, real simulated target. */
class ExecutionTest {

  static String planHash(TestSession t) { return t.session.stores().plans().latest(t.findingId()).hash(); }

  static Commands.Result requestApproval(TestSession t) {
    return t.send("request_approval", "requester", t.args("finding_id", t.findingId().toString(), "plan_hash", planHash(t)));
  }

  static Commands.Result approve(TestSession t, String persona) {
    return t.send("approve", persona, t.args("finding_id", t.findingId().toString(), "plan_hash", planHash(t), "comment", "Looks right."));
  }

  static void arm(TestSession t, String sw) { assertEquals(202, t.send("arm_chaos", "dba", t.args("switch", sw)).status()); }

  static Operation operation(TestSession t) {
    return t.session.stores().operations().get(t.session.stores().findings().get(t.findingId()).currentOperationId);
  }

  /** I5 and I16, looped 100 times on fresh sessions; every other iteration also drops the response. */
  @RepeatedTest(100)
  void duplicateDeliveryAppliesOnce(RepetitionInfo info) {
    TestSession t = new TestSession();
    t.startAnalysis();
    requestApproval(t);
    arm(t, "duplicate_delivery");
    boolean drop = info.getCurrentRepetition() % 2 == 0;
    if (drop) arm(t, "drop_response");
    assertEquals(202, approve(t, "dba").status());

    assertEquals(1, t.session.target().state().ledger().size(), "ledger size");
    assertEquals(1, t.session.counters().mutations.get(), "mutations");
    assertEquals(1, t.session.counters().duplicatesRefused.get(), "duplicates refused");
    Operation op = operation(t);
    assertEquals(OperationState.APPLIED, op.state().state());
    assertEquals(1, op.deliveries().stream().filter(d -> d.result().equals(Delivery.WON)).count());
    assertEquals(1, op.deliveries().stream().filter(d -> d.result().equals(Delivery.LEASE_HELD)).count());
    // The two gate events appear in fixed order: winner first, then the refusal.
    List<TimelineEvent> all = t.events();
    int won = -1, refused = -1;
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i).kind().equals("lease.won")) won = i;
      if (all.get(i).kind().equals("delivery.duplicate_refused")) refused = i;
    }
    assertTrue(won >= 0 && refused == won + 1, "lease.won at " + won + ", duplicate_refused at " + refused);
    // No LEASE_HELD trigger ever reaches the recording, and the agent's bound result is the winner's.
    for (TimelineEvent e : t.events("agent.turn")) assertFalse(String.valueOf(e.payload().get("trigger")).contains("LEASE_HELD"));
    List<TimelineEvent> applyEvents = t.events("tool.invoked").stream().filter(e -> e.payload().get("tool").equals("apply_right_size_role")).toList();
    assertEquals(1, applyEvents.size(), "one apply invocation reached the handler");
    if (drop) {
      assertEquals(List.of("APPROVED", "EXECUTING", "OUTCOME_UNKNOWN", "APPLIED"), op.history());
      assertTrue(String.valueOf(applyEvents.get(0).summary()).contains("TIMEOUT"));
    } else {
      assertEquals(List.of("APPROVED", "EXECUTING", "APPLIED"), op.history());
    }
    assertEquals(FindingState.CLOSED, t.state());
    assertEquals(0, t.session.activeRuns().get());
  }

  /** I8: a lost response is OUTCOME_UNKNOWN, the write tool is refused until reconciled, and status reconciles once. */
  @Test
  void lostResponseReconcilesWithoutSecondMutation() {
    AtomicReference<ToolResult> secondApply = new AtomicReference<>();
    ModelClient probing = (agent, phase, trigger, ctx) -> {
      if (agent == AgentId.remediation && trigger.equals("error:apply_right_size_role:TIMEOUT")) {
        // A direct second apply while the outcome is unknown must be refused before any lock or target call.
        secondApply.set(ctx.session.stores().operations().get(ctx.operationId) == null ? null
            : new adr.broker.ToolBroker(TestSession.POLICY).invoke(ctx, new ToolCall("again", "apply_right_size_role", Map.of("operation_ref", ctx.operationId.toString()))));
      }
      return TestSession.RECORDED.next(agent, phase, trigger, ctx);
    };
    TestSession t = new TestSession(probing);
    t.startAnalysis();
    requestApproval(t);
    arm(t, "drop_response");
    approve(t, "dba");
    Operation op = operation(t);
    assertEquals(List.of("APPROVED", "EXECUTING", "OUTCOME_UNKNOWN", "APPLIED"), op.history());
    assertNotNull(secondApply.get());
    assertEquals(ErrorCode.RECONCILE_REQUIRED, secondApply.get().error().code());
    assertEquals(List.of("get_operation_status"), secondApply.get().error().nextAllowedTools());
    assertEquals(1, t.session.target().state().ledger().size());
    assertEquals(1, t.session.counters().mutations.get());
    assertTrue(t.events("chaos.fired").stream().anyMatch(e -> e.payload().get("switch").equals("drop_response")));
    TimelineEvent status = t.events("tool.invoked").stream().filter(e -> e.payload().get("tool").equals("get_operation_status")).findFirst().orElseThrow();
    assertEquals("applied", status.payload().get("outcome"));
    assertEquals(FindingState.CLOSED, t.state());
  }

  /** I6 and I7 end to end: an abort before commit leaves no trace and the same operation retries once. */
  @Test
  void abortBeforeCommitLeavesNoTrace() {
    TestSession t = new TestSession();
    t.startAnalysis();
    requestApproval(t);
    arm(t, "abort_before_commit");
    approve(t, "dba");
    Operation op = operation(t);
    assertEquals(List.of("APPROVED", "EXECUTING", "FAILED_NOT_APPLIED", "EXECUTING", "APPLIED"), op.history());
    assertEquals(2, op.state().attempt());
    assertEquals(1, t.session.target().state().ledger().size());
    assertEquals(op.id(), t.session.target().state().ledger().keySet().iterator().next());
    assertEquals(1, t.session.counters().mutations.get());
    List<TimelineEvent> applies = t.events("tool.invoked").stream().filter(e -> e.payload().get("tool").equals("apply_right_size_role")).toList();
    assertEquals(2, applies.size());
    assertTrue(applies.get(0).summary().contains("ABORTED_BEFORE_COMMIT"));
    assertTrue(applies.get(0).summary().contains("retryable"));
    assertEquals(FindingState.CLOSED, t.state());
  }

  /** The plan is stale by the time it executes: the operation fails without applying and the plan returns to analysis. */
  @Test
  void stalePlanReturnsToAnalysis() {
    ModelClient changing = (agent, phase, trigger, ctx) -> {
      if (agent == AgentId.remediation && trigger.equals("ok:preflight_check")) {
        // Between approval and execution, an on-call DBA re-grants the membership with a different grantor.
        ctx.session.target().grantMembership(Caller.dba_oncall, "orders_owner", "orders_app");
      }
      return TestSession.RECORDED.next(agent, phase, trigger, ctx);
    };
    TestSession t = new TestSession(changing);
    t.startAnalysis();
    requestApproval(t);
    approve(t, "dba");
    Operation op = operation(t);
    assertEquals(OperationState.FAILED_NOT_APPLIED, op.state().state());
    assertFalse(op.state().retryable());
    assertTrue(t.session.target().state().ledger().isEmpty(), "nothing applied");
    assertEquals(0, t.session.counters().mutations.get());
    assertTrue(t.events("finding.state_changed").stream().anyMatch(e -> e.payload().get("from").equals("REMEDIATING") && e.payload().get("to").equals("ANALYSING")));
    Approval a = t.session.stores().approvals().all().get(0);
    assertEquals(Approval.Status.VOIDED, a.status());
    assertTrue(t.events("approval.voided").size() >= 1);
    // Analysis restarted against the changed state and produced a new plan with a new expected_before.
    assertEquals(FindingState.PLAN_READY, t.state());
    assertEquals(4, t.session.stores().plans().forFinding(t.findingId()).size());
    assertNotEquals(t.session.stores().plans().forFinding(t.findingId()).get(1).hash(), planHash(t));
  }

  /** I15 at run time: a miss makes no further tool call, reconciles the operation, and stops in NEEDS_ATTENTION. */
  @Test
  void recordingMissStopsSafely() {
    for (String removed : List.of("error:apply_right_size_role:TIMEOUT", "ok:get_operation_status:applied")) {
      ModelClient missing = (agent, phase, trigger, ctx) -> agent == AgentId.remediation && trigger.equals(removed) ? null
          : TestSession.RECORDED.next(agent, phase, trigger, ctx);
      TestSession t = new TestSession(missing);
      t.startAnalysis();
      requestApproval(t);
      arm(t, "drop_response");
      approve(t, "dba");
      Operation op = operation(t);
      assertEquals(OperationState.APPLIED, op.state().state(), removed);
      assertEquals(1, t.session.target().state().ledger().size());
      assertEquals(1, t.session.counters().mutations.get());
      assertEquals(FindingState.NEEDS_ATTENTION, t.state(), removed);
      assertTrue(t.session.stores().findings().get(t.findingId()).attentionReason.startsWith("DEMO_FLOW_UNAVAILABLE"));
      long calls = t.events("tool.invoked").stream().filter(e -> e.payload().get("agent").equals("remediation")).count();
      assertEquals(removed.startsWith("error") ? 2 : 3, calls, "no tool call after the miss: " + removed);
      assertTrue(t.events("system.notice").stream().anyMatch(e -> e.summary().startsWith("Demo flow unavailable")));
      assertEquals(200, t.send("reset", "requester", Map.of()).status(), "reset is allowed from NEEDS_ATTENTION");
    }
  }

  @Test
  void approvalRules() {
    // Requester cannot approve.
    TestSession t = new TestSession();
    t.startAnalysis();
    requestApproval(t);
    Commands.Result self = approve(t, "requester");
    assertEquals(409, self.status());
    assertEquals("SEPARATION_OF_DUTIES", self.code());
    assertEquals(FindingState.AWAITING_APPROVAL, t.state());
    assertTrue(t.session.stores().operations().all().isEmpty());
    assertTrue(t.events("gate.checked").stream().anyMatch(e -> e.payload().get("rule").equals("approval.separation_of_duties") && e.payload().get("result").equals("REFUSED")));
    // Hash mismatch creates no operation.
    Commands.Result wrong = t.send("approve", "dba", t.args("finding_id", t.findingId().toString(), "plan_hash", "f".repeat(64)));
    assertEquals("PLAN_HASH_MISMATCH", wrong.code());
    assertTrue(t.session.stores().operations().all().isEmpty());
    // Expired: the clock moves 16 minutes.
    t.workflow.approvals().clock = () -> java.time.Instant.now().plusSeconds(16 * 60);
    Commands.Result late = approve(t, "dba");
    assertEquals("APPROVAL_EXPIRED", late.code());
    assertEquals(FindingState.PLAN_READY, t.state());
    assertEquals(Approval.Status.EXPIRED, t.session.stores().approvals().latest(t.findingId()).status());
    assertTrue(t.session.stores().operations().all().isEmpty());
    t.workflow.approvals().clock = java.time.Instant::now;
    // Voided: an open request marked void cannot be approved.
    requestApproval(t);
    t.session.stores().approvals().latest(t.findingId()).mark(Approval.Status.VOIDED, java.time.Instant.now());
    assertEquals("NO_OPEN_APPROVAL", approve(t, "dba").code());
    assertTrue(t.session.stores().operations().all().isEmpty());
    // Reject returns to PLAN_READY; the same requester may then request again and the DBA approves.
    t.force(t.findingId(), FindingState.AWAITING_APPROVAL, FindingState.PLAN_READY);
    requestApproval(t);
    assertEquals(202, t.send("reject", "dba", t.args("finding_id", t.findingId().toString(), "plan_hash", planHash(t), "comment", "Not yet")).status());
    assertEquals(FindingState.PLAN_READY, t.state());
    assertEquals(Approval.Status.REJECTED, t.session.stores().approvals().latest(t.findingId()).status());
    requestApproval(t);
    assertEquals(202, approve(t, "dba").status());
    assertEquals(1, t.session.stores().operations().all().size());
    assertEquals(FindingState.CLOSED, t.state());
    // Request with a stale hash is refused.
    TestSession u = new TestSession();
    u.startAnalysis();
    assertEquals("PLAN_HASH_MISMATCH", u.send("request_approval", "requester", u.args("finding_id", u.findingId().toString(),
        "plan_hash", u.session.stores().plans().forFinding(u.findingId()).get(0).hash())).code());
  }

  @Test
  void approvalsCarryDemoPersonaLabel() {
    TestSession t = new TestSession();
    t.startAnalysis();
    requestApproval(t);
    approve(t, "dba");
    for (String kind : List.of("approval.requested", "approval.decided")) {
      for (TimelineEvent e : t.events(kind)) {
        Map<?, ?> actor = (Map<?, ?>) e.payload().get("actor");
        assertEquals(false, actor.get("authenticated"));
        assertEquals("demo_persona", actor.get("identity_mode"));
        assertTrue(e.summary().contains("demo persona, not authenticated"));
      }
    }
    Map<String, Object> export = adr.app.EvidenceExport.of(t.session);
    @SuppressWarnings("unchecked") List<Map<String, Object>> approvals = (List<Map<String, Object>>) export.get("approvals");
    assertEquals(1, approvals.size());
    Map<?, ?> approver = (Map<?, ?>) approvals.get(0).get("approver");
    assertEquals("dana", approver.get("id"));
    assertEquals("dba", approver.get("persona"));
    assertEquals(false, approver.get("authenticated"));
    assertEquals("demo_persona", approver.get("identity_mode"));
    assertEquals("demo_persona", ((Map<?, ?>) approvals.get(0).get("requester")).get("identity_mode"));
    assertEquals("demo_persona", approvals.get(0).get("identity_mode"));
  }

  @Test
  void killSwitchBlocksTheWriteAndKeepsTheFindingWhereItWas() {
    TestSession t = new TestSession();
    t.startAnalysis();
    requestApproval(t);
    t.send("kill_switch", "dba", t.args("on", true));
    approve(t, "dba");
    assertTrue(t.session.target().state().ledger().isEmpty());
    Operation op = operation(t);
    assertEquals(OperationState.APPROVED, op.state().state());
    assertTrue(t.events("gate.checked").stream().anyMatch(e -> e.payload().get("rule").equals("broker.killed")));
    assertEquals(FindingState.NEEDS_ATTENTION, t.state(), "KILLED is a stopping code, so the run ends safely");
    assertTrue(t.session.stores().findings().get(t.findingId()).attentionReason.startsWith("KILLED"));
  }
}
