package adr.broker;

import adr.app.Session;
import adr.domain.ActorType;
import adr.domain.Evidence;
import adr.domain.InvalidInput;
import adr.domain.Labels;
import adr.domain.OpState;
import adr.domain.Operation;
import adr.domain.OperationState;
import adr.domain.TimelineEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The production pipeline's order and refusals in one method, so recorded agents are constrained exactly as
 * live ones would be. Validation is step 3: a refused input never reaches a guard, a lock or the target.
 */
public final class ToolBroker {
  private final Policy policy;

  public ToolBroker(Policy policy) { this.policy = policy; }

  public Policy policy() { return policy; }

  public ToolResult invoke(RunContext run, ToolCall call) {
    Session s = run.session;
    String tool = call.tool() == null ? "" : call.tool();
    // 1 allowlist
    ToolSpec spec = Tools.get(tool);
    if (spec == null || !spec.allows(run.agent)) {
      return refuse(run, call, ToolError.of(ErrorCode.TOOL_NOT_ALLOWED, run.agent + " may not call " + tool));
    }
    // 2 kill switch
    if (s.killSwitch().get() && spec.kind() != ToolSpec.Kind.READ) {
      return refuse(run, call, ToolError.of(ErrorCode.KILLED, "kill switch is on: " + spec.kind().name().toLowerCase() + " tools are blocked"));
    }
    // 3 strict parse, then explicit missing/null/blank checks
    Object args;
    try {
      args = Inputs.parse(call.args(), spec.argsType());
    } catch (InvalidInput e) {
      return refuse(run, call, ToolError.invalid(e.field(), e.problem()));
    }
    // 4 resolve references in this session
    Operation op = null;
    if (args instanceof Args.OperationRefArgs ref) {
      op = s.stores().operations().get(ref.id());
      if (op == null || !op.findingId().equals(run.findingId))
        return refuse(run, call, ToolError.of(ErrorCode.UNKNOWN_REF, "operation " + ref.operationRef() + " is not in this session"));
      run.operationId = op.id();
    }
    if (args instanceof Args.FindingRefArgs ref && !s.stores().findings().contains(ref.id())) {
      return refuse(run, call, ToolError.of(ErrorCode.UNKNOWN_REF, "finding " + ref.findingRef() + " is not in this session"));
    }
    // 5 protected targets: applies to what a write or probe would act on
    if (spec.kind() != ToolSpec.Kind.READ) {
      String named = namedRole(args);
      if (named != null && policy.isProtectedRole(named))
        return refuse(run, call, ToolError.of(ErrorCode.PROTECTED_TARGET, named + " is a protected role"));
    }
    // 6 preconditions
    if (spec.name().equals("apply_right_size_role")) {
      OpState st = op.state();
      if (st.state() == OperationState.OUTCOME_UNKNOWN)
        return refuse(run, call, ToolError.of(ErrorCode.RECONCILE_REQUIRED, "outcome unknown: reconcile with get_operation_status first", false, List.of("get_operation_status")));
      if (st.state() == OperationState.EXECUTING)
        return refuse(run, call, ToolError.of(ErrorCode.LEASE_HELD, "lease held by " + st.leaseOwner()));
      if (st.state() == OperationState.APPLIED || st.state() == OperationState.NEEDS_HUMAN
          || (st.state() == OperationState.FAILED_NOT_APPLIED && (!st.retryable() || st.attempt() >= Operation.MAX_ATTEMPTS)))
        return refuse(run, call, ToolError.of(ErrorCode.STATE_CONFLICT, "operation is " + st.state() + " and cannot be executed"));
      if (!op.preflightPassed())
        return refuse(run, call, ToolError.of(ErrorCode.STATE_CONFLICT, "pre-flight has not passed for this operation"));
    }
    // 7 policy
    Policy.Decision pd = policy.evaluateTool(spec, op == null ? null : op.state().state());
    if (spec.kind() != ToolSpec.Kind.READ || !pd.allowed()) {
      gate(s, run, pd.rule(), pd.allowed() ? "PASS" : "REFUSED", pd.reason(), Map.of("tool", spec.name()));
    }
    if (!pd.allowed()) return refuse(run, call, ToolError.of(ErrorCode.POLICY_DENIED, pd.rule() + ": " + pd.reason()), false);
    // 8 (duplicate delivery races the lease inside the execution guard) 9 handler
    Handled h;
    try {
      h = spec.handler().handle(run, args);
    } catch (ToolException e) {
      return refuse(run, call, e.error(), false);
    } catch (InvalidInput e) {
      return refuse(run, call, ToolError.invalid(e.field(), e.problem()), false);
    }
    // 10 evidence and tool event
    Evidence ev = s.stores().evidence().add(spec.name(), h.sourceLabel(), h.data(), run.runId);
    run.bind(call.alias(), ev.id());
    ToolResult r = ToolResult.ok(spec.name(), ev.id(), h.sourceLabel(), h.data(), h.outcome());
    run.results.add(r);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tool", spec.name());
    payload.put("agent", run.agent.name());
    payload.put("caller", spec.caller().name());
    payload.put("args", call.args());
    payload.put("evidence_id", ev.id());
    payload.put("outcome", h.outcome() == null ? "ok" : h.outcome());
    payload.put("source_label", h.sourceLabel());
    s.timeline().append(TimelineEvent.of(run.findingId, ActorType.tool, spec.name(), run.stage(), "tool.invoked",
        spec.name() + ": ok" + (h.outcome() == null ? "" : ", " + h.outcome()) + " → " + ev.id(), payload, h.sourceLabel()));
    // 11
    return r;
  }

  private static String namedRole(Object args) {
    if (args instanceof Args.RoleArgs r) return r.role();
    if (args instanceof Args.HealthScenariosArgs r) return r.role();
    if (args instanceof Args.MembershipArgs r) return r.member();
    return null;
  }

  private ToolResult refuse(RunContext run, ToolCall call, ToolError e) { return refuse(run, call, e, true); }

  private ToolResult refuse(RunContext run, ToolCall call, ToolError e, boolean gateEvent) {
    Session s = run.session;
    String tool = call.tool() == null ? "" : call.tool();
    ToolResult r = ToolResult.error(tool, e);
    run.results.add(r);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tool", tool);
    payload.put("agent", run.agent.name());
    payload.put("error", e);
    if (gateEvent) {
      gate(s, run, "broker." + e.code().name().toLowerCase(), "REFUSED", e.message(), payload);
    } else {
      s.timeline().append(TimelineEvent.of(run.findingId, ActorType.tool, tool, run.stage(), "tool.invoked",
          tool + ": " + e.code() + (e.retryable() ? ", retryable" : "") + ". " + e.message(), payload, Labels.PLATFORM));
    }
    return r;
  }

  private static void gate(Session s, RunContext run, String rule, String result, String summary, Map<String, Object> extra) {
    Map<String, Object> payload = new LinkedHashMap<>(extra);
    payload.put("rule", rule);
    payload.put("result", result);
    TimelineEvent e = TimelineEvent.of(run.findingId, ActorType.gate, "tool_broker", run.stage(), "gate.checked",
        rule + ": " + result + ". " + summary, payload, Labels.PLATFORM);
    if (result.equals("REFUSED")) s.recordRefusal(e); else s.timeline().append(e);
  }

  public static UUID uuid(String s) { return UUID.fromString(s); }
}
