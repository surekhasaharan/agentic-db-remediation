package adr.broker;

import adr.domain.FindingState;
import adr.domain.GrantSpec;
import adr.domain.Json;
import adr.domain.OperationState;
import adr.domain.PlanExec;
import adr.domain.Require;
import adr.target.Fingerprint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** resources/policy.json, loaded at start-up, its SHA-256 recorded on every decision as the policy version. */
public final class Policy {

  public record PlanAdmission(List<String> findingTypes, Boolean targetMustEqualFindingSubject, Integer maxGrants,
                              List<String> allowedPrivileges, Integer minHealthScenarios, List<String> requireGuards) {
    public PlanAdmission {
      findingTypes = Require.nonBlankStrings(findingTypes, 16, 80, "planAdmission.findingTypes");
      Require.nonNull(targetMustEqualFindingSubject, "planAdmission.targetMustEqualFindingSubject");
      Require.range(maxGrants, 1, 64, "planAdmission.maxGrants");
      allowedPrivileges = Require.nonBlankStrings(allowedPrivileges, 16, 20, "planAdmission.allowedPrivileges");
      Require.range(minHealthScenarios, 0, 16, "planAdmission.minHealthScenarios");
      requireGuards = Require.nonBlankStrings(requireGuards, 16, 40, "planAdmission.requireGuards");
    }
  }

  public record ApprovalRules(Boolean required, List<String> approverPersonas, Boolean separationOfDuties, Integer expiryMinutes) {
    public ApprovalRules {
      Require.nonNull(required, "approval.required");
      approverPersonas = Require.nonBlankStrings(approverPersonas, 8, 20, "approval.approverPersonas");
      Require.nonNull(separationOfDuties, "approval.separationOfDuties");
      Require.range(expiryMinutes, 1, 1440, "approval.expiryMinutes");
    }
  }

  /** An entry is the only way a tool is permitted. allow defaults to true when the entry exists; states constrain it. */
  public record ToolRule(Boolean allow, List<String> operationStates, List<String> findingStates) {
    public boolean allowed() { return allow == null || allow; }
  }

  public record Doc(Integer version, @com.fasterxml.jackson.annotation.JsonProperty("default") String defaultDecision, List<String> protectedRoles, PlanAdmission planAdmission,
                    ApprovalRules approval, Map<String, ToolRule> tools) {
    public Doc {
      Require.range(version, 1, 1000, "version");
      Require.nonBlank(defaultDecision, "default");
      if (!defaultDecision.equals("deny") && !defaultDecision.equals("allow")) throw new adr.domain.InvalidInput("default", "must be deny or allow");
      protectedRoles = Require.nonBlankStrings(protectedRoles, 32, 40, "protectedRoles");
      Require.nonNull(planAdmission, "planAdmission");
      Require.nonNull(approval, "approval");
      Require.nonNull(tools, "tools");
    }
  }

  public record Decision(boolean allowed, String rule, String reason) {}

  private final Doc doc;
  private final String version;
  private final List<Pattern> protectedPatterns = new ArrayList<>();

  private Policy(Doc doc, String version) {
    this.doc = doc;
    this.version = version;
    for (String p : doc.protectedRoles()) protectedPatterns.add(Pattern.compile("^" + p.replace("*", ".*") + "$"));
  }

  public static Policy load() {
    try (InputStream in = Policy.class.getResourceAsStream("/policy.json")) {
      if (in == null) throw new IllegalStateException("missing policy.json");
      return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("policy.json: " + e.getMessage(), e);
    }
  }

  public static Policy fromJson(String text) {
    Doc d = Json.read(Json.CAMEL.copy().setPropertyNamingStrategy(null), text, Doc.class);
    return new Policy(d, Fingerprint.sha256(text));
  }

  public boolean defaultDeny() { return doc.defaultDecision().equals("deny"); }

  /** True when the policy has an entry that permits the tool, ignoring state constraints. */
  public boolean permits(String tool) {
    ToolRule rule = doc.tools().get(tool);
    return rule == null ? !defaultDeny() : rule.allowed();
  }

  public String version() { return version; }
  public Doc doc() { return doc; }
  public ApprovalRules approval() { return doc.approval(); }

  public boolean isProtectedRole(String role) {
    for (Pattern p : protectedPatterns) if (p.matcher(role).matches()) return true;
    return false;
  }

  /** Plan admission: the closed list of what a plan may contain. Returns the failures, empty when admitted. */
  public List<String> admitPlan(String findingType, String subjectRole, PlanExec exec, List<String> guardsPassed) {
    PlanAdmission a = doc.planAdmission();
    List<String> failures = new ArrayList<>();
    if (!a.findingTypes().contains(findingType)) failures.add("finding type " + findingType + " is not admitted");
    if (a.targetMustEqualFindingSubject() && !exec.targetRole().equals(subjectRole))
      failures.add("target role " + exec.targetRole() + " is not the finding's subject " + subjectRole);
    if (exec.grants().size() > a.maxGrants()) failures.add(exec.grants().size() + " grants exceed the limit of " + a.maxGrants());
    for (GrantSpec g : exec.grants()) {
      if (!a.allowedPrivileges().contains(g.privilege())) failures.add("privilege " + g.privilege() + " is not allowed");
      if (isProtectedRole(g.object()) || g.kind().equals("role")) failures.add("grant targets a protected role: " + g.object());
    }
    if (isProtectedRole(exec.targetRole())) failures.add("target role " + exec.targetRole() + " is protected");
    if (exec.scenarios().size() < a.minHealthScenarios()) failures.add("fewer than " + a.minHealthScenarios() + " health scenarios");
    for (String g : a.requireGuards()) if (!guardsPassed.contains(g)) failures.add("required guard " + g + " did not run or did not pass");
    return failures;
  }

  /**
   * Tool-level policy, evaluated at broker step 7. Least privilege: a tool is permitted only by an explicit
   * entry; with "default": "deny" an unlisted tool is refused, whatever the allowlist says.
   */
  public Decision evaluateTool(ToolSpec tool, OperationState opState) {
    ToolRule rule = doc.tools().get(tool.name());
    if (rule == null) {
      return defaultDeny()
          ? new Decision(false, "policy.default", "no policy entry for " + tool.name() + " and the default is deny")
          : new Decision(true, "policy.default", "no policy entry for " + tool.name() + " and the default is allow");
    }
    if (!rule.allowed()) return new Decision(false, "policy.tools." + tool.name() + ".allow", "the policy entry denies this tool");
    if (rule.operationStates() != null) {
      if (opState == null || !rule.operationStates().contains(opState.name()))
        return new Decision(false, "policy.tools." + tool.name() + ".operation_states",
            "operation is " + opState + ", allowed states " + rule.operationStates());
      return new Decision(true, "policy.tools." + tool.name() + ".operation_states", "operation is " + opState);
    }
    return new Decision(true, "policy.tools." + tool.name() + ".allow", "permitted by an explicit policy entry");
  }

  public Decision evaluateReopen(FindingState state) {
    ToolRule rule = doc.tools().get("reopen_finding");
    if (rule == null || !rule.allowed())
      return new Decision(false, "policy.tools.reopen_finding.allow", rule == null ? "no policy entry for reopen_finding" : "the policy entry denies reopen");
    if (rule.findingStates() != null && !rule.findingStates().contains(state.name()))
      return new Decision(false, "policy.tools.reopen_finding.finding_states", "finding is " + state);
    return new Decision(true, "policy.tools.reopen_finding.finding_states", "finding is " + state);
  }
}
