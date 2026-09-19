package adr.domain;

import java.util.EnumSet;
import java.util.Set;

public enum FindingState {
  OPEN, ANALYSING, PLAN_READY, AWAITING_APPROVAL, REMEDIATING, VERIFYING, CLOSED, DRIFT_DETECTED, REOPENED, NEEDS_ATTENTION;

  public static final Set<FindingState> STABLE =
      EnumSet.of(OPEN, PLAN_READY, AWAITING_APPROVAL, CLOSED, REOPENED, NEEDS_ATTENTION);

  public boolean isStable() { return STABLE.contains(this); }

  /** The lifecycle rail's five stages are groupings of these states. */
  public String stage() {
    return switch (this) {
      case OPEN, ANALYSING, PLAN_READY -> "analyse";
      case AWAITING_APPROVAL -> "approve";
      case REMEDIATING -> "remediate";
      case VERIFYING -> "verify";
      case CLOSED, DRIFT_DETECTED, REOPENED -> "supervise";
      case NEEDS_ATTENTION -> "attention";
    };
  }
}
