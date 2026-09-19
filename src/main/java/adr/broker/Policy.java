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

  public record ToolRule(List<String> operationStates, List<String> findingStates) {}

  public record Doc(Integer version, @com.fasterxml.jackson.annotation.JsonProperty("default") String defaultDecision, List<String> protectedRoles, PlanAdmission planAdmission,
                    ApprovalRules approval, Map<String, ToolRule> tools) {
    public Doc {
      Require.range(version, 1, 1000, "version");
      Require.nonBlank(defaultDecision, "default");
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
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      Doc d = Json.read(Json.CAMEL.copy().setPropertyNamingStrategy(null), text, Doc.class);
      return new Policy(d, Fingerprint.sha256(text));
    } catch (IOException e) {
      throw new IllegalStateException("policy.json: " + e.getMessage(), e);
    }
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

  /** Tool-level policy, evaluated at broker step 7. */
  public Decision evaluateTool(ToolSpec tool, OperationState opState) {
    ToolRule rule = doc.tools().get(tool.name());
    if (rule != null && rule.operationStates() != null) {
      if (opState == null || !rule.operationStates().contains(opState.name()))
        return new Decision(false, "policy.tools." + tool.name() + ".operation_states",
            "operation is " + opState + ", allowed states " + rule.operationStates());
      return new Decision(true, "policy.tools." + tool.name() + ".operation_states", "operation is " + opState);
    }
    return new Decision(true, "policy.default_allow_for_" + tool.kind().name().toLowerCase(), "no tool rule applies");
  }

  public Decision evaluateReopen(FindingState state) {
    ToolRule rule = doc.tools().get("reopen_finding");
    if (rule != null && rule.findingStates() != null && !rule.findingStates().contains(state.name()))
      return new Decision(false, "policy.tools.reopen_finding.finding_states", "finding is " + state);
    return new Decision(true, "policy.tools.reopen_finding.finding_states", "finding is " + state);
  }
}
