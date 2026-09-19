package adr.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a write tool executes: the approved grants, the membership to drop, the scenarios to verify, and the
 * fingerprint the plan was built on. The broker loads it by plan hash, never from a model-authored argument.
 */
public record PlanExec(UUID findingId, String targetRole, String ownerRole, List<GrantSpec> grants,
                       List<String> scenarios, String expectedBefore) {
  public PlanExec {
    Require.nonNull(findingId, "finding_id");
    Require.matches(targetRole, Require.ROLE_NAME, "target_role");
    Require.matches(ownerRole, Require.ROLE_NAME, "owner_role");
    grants = sortedGrants(Require.nonEmpty(grants, 64, "grants"));
    scenarios = sortedStrings(Require.nonBlankStrings(scenarios, 16, 40, "scenarios"));
    Require.matches(expectedBefore, Require.HEX64, "expected_before");
  }

  private static List<GrantSpec> sortedGrants(List<GrantSpec> in) {
    List<GrantSpec> out = new ArrayList<>(in);
    out.sort(null);
    return List.copyOf(out);
  }

  private static List<String> sortedStrings(List<String> in) {
    List<String> out = new ArrayList<>(in);
    out.sort(null);
    return List.copyOf(out);
  }
}
