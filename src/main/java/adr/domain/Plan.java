package adr.domain;

import java.util.List;

/** One plan version and its hash. Added lists describe the diff from the previous version. */
public record Plan(int version, String hash, PlanExec exec, List<GrantSpec> addedGrants, List<String> addedScenarios,
                   String policyVersion, String createdAt) {
  public Plan {
    Require.range(version, 1, 1000, "version");
    Require.matches(hash, Require.HEX64, "hash");
    Require.nonNull(exec, "exec");
    addedGrants = Require.listOrEmpty(addedGrants, 64, "added_grants");
    addedScenarios = Require.listOrEmpty(addedScenarios, 16, "added_scenarios");
    Require.nonBlank(policyVersion, "policy_version");
    Require.nonBlank(createdAt, "created_at");
  }
}
