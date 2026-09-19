package adr.workflow;

import adr.app.Session;
import adr.domain.ActorType;
import adr.domain.DriftObservation;
import adr.domain.FindingState;
import adr.domain.Labels;
import adr.domain.Operation;
import adr.domain.TimelineEvent;
import adr.stores.Findings;
import adr.stores.Lifecycle;
import adr.target.Caller;
import adr.target.Fingerprint;
import adr.target.PgState;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Deterministic drift detection. One scheduled thread serves every session that has a subscriber. The poller
 * reads outside any lock, decides that a move is warranted, and asks Lifecycle like every other caller. It
 * makes no agent call until the fingerprint differs.
 */
public final class DriftPoller {
  public static final long INTERVAL_MS = 2000;

  private final Supplier<List<Session>> sessions;
  private final Workflow workflow;

  public DriftPoller(Supplier<List<Session>> sessions, Workflow workflow) {
    this.sessions = sessions;
    this.workflow = workflow;
  }

  public void tick() {
    for (Session s : sessions.get()) {
      if (s.timeline().subscriberCount() > 0) {
        try { tick(s); } catch (RuntimeException e) { System.err.println("drift tick failed: " + e); }
      }
    }
  }

  /** Returns the transition results, one per CLOSED finding, so tests can assert LOST_RACE and SESSION_GONE. */
  public List<Lifecycle.Result> tick(Session s) {
    List<Lifecycle.Result> results = new java.util.ArrayList<>();
    if (s.timeline().isClosed()) return results;
    if (s.timeline().atCap() && s.activeRuns().get() == 0) return results;
    for (Findings.Entry f : s.stores().findings().inState(FindingState.CLOSED)) {
      String role = f.finding.subjectRole();
      String fp = s.target().fingerprint(Caller.agent_supervisor, role);
      if (fp.equals(f.sealedFingerprint)) continue;
      Operation own = f.currentOperationId == null ? null : s.stores().operations().get(f.currentOperationId);
      PgState.LedgerRow row = own == null ? null : s.target().state().ledger().get(own.id());
      if (row != null && row.after().equals(fp)) {
        workflow.reseal(s, f, fp, "the live fingerprint equals this finding's own applied after-state");
        continue;
      }
      Map<String, Object> live = s.target().snapshot(Caller.agent_supervisor, role);
      Map<String, Object> diff = f.sealedSnapshot == null ? Map.of() : Fingerprint.diff(f.sealedSnapshot, live);
      DriftObservation obs = new DriftObservation(f.sealedFingerprint, fp, diff, Instant.now().toString());
      Lifecycle.Result r = Lifecycle.transition(s, s.stores(), f.finding.id(), FindingState.CLOSED, FindingState.DRIFT_DETECTED,
          "fingerprint changed", () -> {
            f.drift = obs;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("rule", "drift.fingerprint_poll");
            p.put("result", "CHANGED");
            p.put("sealed_fingerprint", obs.sealedFingerprint());
            p.put("live_fingerprint", obs.liveFingerprint());
            p.put("diff", obs.diff());
            return List.of(TimelineEvent.of(f.finding.id(), ActorType.gate, "drift_poller", "supervise", "drift.detected",
                "drift.fingerprint_poll: CHANGED. Sealed " + obs.sealedFingerprint().substring(0, 8) + ", live " + fp.substring(0, 8)
                    + ". " + describe(diff) + " The supervisor is asked to classify it.", p, Labels.SIMULATED_PG));
          });
      results.add(r);
      if (r == Lifecycle.Result.OK) workflow.startSupervisor(s, f.finding.id());
    }
    return results;
  }

  @SuppressWarnings("unchecked")
  static String describe(Map<String, Object> diff) {
    StringBuilder sb = new StringBuilder();
    Object am = diff.get("added_memberships");
    if (am instanceof List<?> l && !l.isEmpty()) {
      Map<String, Object> m = (Map<String, Object>) l.get(0);
      sb.append("Membership in ").append(m.get("role")).append(" added by ").append(m.get("grantor")).append('.');
    }
    if (diff.containsKey("removed_grants") || diff.containsKey("added_grants")) sb.append(" Direct grants changed.");
    if (sb.isEmpty()) sb.append("Snapshot differs.");
    return sb.toString().trim();
  }
}
