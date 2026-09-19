package adr.agents;

import adr.broker.Guards;
import adr.broker.RunContext;
import adr.broker.ToolBroker;
import adr.broker.ToolCall;
import adr.broker.ToolResult;
import adr.broker.Tools;
import adr.domain.ActorType;
import adr.domain.AgentId;
import adr.domain.Decision;
import adr.domain.InvalidInput;
import adr.domain.Labels;
import adr.domain.TimelineEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * The loop a live model would drive, identical for recorded agents. It can reach only ModelClient and
 * ToolBroker. A recording miss or an exhausted budget ends in a safe stop: no further tool call is made and the
 * workflow takes over.
 */
public final class AgentRunner {
  public static final int AGENT_SLOTS = 2;
  public static final Semaphore SLOTS = new Semaphore(AGENT_SLOTS);

  public record RunResult(Decision decision, String stopCode, String trigger, int steps) {
    public boolean stopped() { return decision == null; }
  }

  private final ModelClient model;
  private final ToolBroker broker;

  public AgentRunner(ModelClient model, ToolBroker broker) {
    this.model = model;
    this.broker = broker;
  }

  public static int maxSteps(AgentId agent, String phase) {
    return switch (agent) {
      case analysis -> phase.equals("A") ? 6 : 5;
      case remediation -> 6;
      case verification, supervision -> 4;
    };
  }

  public RunResult run(RunContext ctx) {
    SLOTS.acquireUninterruptibly();
    ctx.session.activeRuns().incrementAndGet();
    try {
      return loop(ctx);
    } finally {
      ctx.session.activeRuns().decrementAndGet();
      SLOTS.release();
    }
  }

  private RunResult loop(RunContext ctx) {
    String trigger = "start";
    int max = maxSteps(ctx.agent, ctx.phase);
    for (int step = 1; step <= max; step++) {
      ctx.step = step;
      Turn turn;
      try {
        turn = model.next(ctx.agent, ctx.phase, trigger, ctx);
      } catch (InvalidInput e) {
        return safeStop(ctx, "RECORDING_MISS", trigger, "turn for " + trigger + " could not be resolved: " + e.getMessage());
      }
      if (turn == null) return safeStop(ctx, "RECORDING_MISS", trigger, "no recorded turn for " + trigger);
      say(ctx, step, turn);
      if (turn.hasCalls()) {
        ToolResult last = null;
        for (ToolCall call : turn.calls()) {
          last = broker.invoke(ctx, call);
          if (!last.ok()) break;
        }
        trigger = last.trigger();
        if (!last.ok() && !last.error().code().recoverable) {
          return safeStop(ctx, last.error().code().name(), trigger, last.error().message());
        }
        continue;
      }
      List<Guards.Result> results = Guards.check(ctx, turn.decision());
      gateEvents(ctx, results);
      Guards.Result first = results.stream().filter(r -> !r.ok()).findFirst().orElse(null);
      if (first == null) {
        decisionEvent(ctx, turn.decision(), true);
        return new RunResult(turn.decision(), null, trigger, step);
      }
      decisionEvent(ctx, turn.decision(), false);
      trigger = "guard_rejected:" + first.guard();
    }
    return safeStop(ctx, "BUDGET_EXHAUSTED", trigger, "step budget of " + max + " used");
  }

  private RunResult safeStop(RunContext ctx, String code, String trigger, String detail) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("agent", ctx.agent.name());
    p.put("phase", ctx.phase);
    p.put("trigger", trigger);
    p.put("code", code);
    p.put("detail", detail);
    ctx.session.timeline().append(TimelineEvent.of(ctx.findingId, ActorType.system, "agent_runner", ctx.stage(), "agent.stopped",
        ctx.agent.displayName + " stopped safely: " + code + " at trigger " + trigger + ". No further tool call.", p, Labels.PLATFORM));
    return new RunResult(null, code, trigger, ctx.step);
  }

  private void say(RunContext ctx, int step, Turn turn) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("agent", ctx.agent.name());
    p.put("phase", ctx.phase);
    p.put("step", step);
    p.put("trigger", turn.when());
    p.put("say", turn.say());
    p.put("recorded", true);
    p.put("calls", turn.calls().stream().map(c -> Map.of("alias", c.alias(), "tool", c.tool())).toList());
    String what = turn.hasCalls() ? turn.calls().size() + " tool call" + (turn.calls().size() == 1 ? "" : "s") : "a decision";
    ctx.session.timeline().append(TimelineEvent.of(ctx.findingId, ActorType.agent, ctx.agent.name(), ctx.stage(), "agent.turn",
        ctx.agent.displayName + ", step " + step + ": " + what, p, Labels.PLATFORM));
  }

  private void decisionEvent(RunContext ctx, Decision d, boolean accepted) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("agent", ctx.agent.name());
    p.put("phase", ctx.phase);
    p.put("recorded", true);
    p.put("accepted", accepted);
    p.put("decision", d);
    p.put("tools", Tools.namesFor(ctx.agent));
    p.put("evidence_ids", ctx.producedEvidence.stream().sorted().toList());
    ctx.session.timeline().append(TimelineEvent.of(ctx.findingId, ActorType.agent, ctx.agent.name(), ctx.stage(), "agent.decision",
        ctx.agent.displayName + " decided: " + d.decision() + (accepted ? "" : " (refused by a guard)"), p, Labels.PLATFORM));
  }

  private void gateEvents(RunContext ctx, List<Guards.Result> results) {
    for (Guards.Result r : results) {
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("rule", "guard." + r.guard());
      p.put("result", r.ok() ? "PASS" : "REFUSED");
      p.put("failures", r.failures());
      p.put("agent", ctx.agent.name());
      TimelineEvent e = TimelineEvent.of(ctx.findingId, ActorType.gate, "guards", ctx.stage(), "gate.checked",
          "guard." + r.guard() + ": " + (r.ok() ? "PASS" : "REFUSED. " + String.join("; ", r.failures())), p, Labels.PLATFORM);
      if (r.ok()) ctx.session.timeline().append(e); else ctx.session.recordRefusal(e);
    }
  }
}
