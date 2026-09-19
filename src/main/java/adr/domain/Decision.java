package adr.domain;

import java.util.List;

/**
 * What an agent submits. Each record validates missing, null and blank values in its constructor; a guard then
 * accepts or refuses it, and only the workflow moves state.
 */
public sealed interface Decision permits Decision.Analysis, Decision.Remediation, Decision.Verification, Decision.Supervision {
  String decision();
  String reason();
  List<String> evidence();

  static String reason(String r) {
    Require.nonBlank(r, "reason");
    return Require.maxLen(r, 500, "reason");
  }

  static List<String> evidence(List<String> e) {
    return Require.nonBlankStrings(Require.nonEmpty(e, 32, "evidence"), 32, 120, "evidence");
  }

  enum Confidence { low, medium, high }

  record RetainGrant(String privilege, String kind, String object, List<String> evidence) {
    public RetainGrant {
      Require.matches(privilege, GrantSpec.PRIVILEGE, "retain_grants.privilege");
      Require.oneOf(kind, GrantSpec.Kind.class, "retain_grants.kind");
      Require.matches(object, GrantSpec.OBJECT_KEY, "retain_grants.object");
      evidence = Require.nonBlankStrings(evidence, 16, 120, "retain_grants.evidence");
    }
    public GrantSpec grant() { return new GrantSpec(privilege, kind, object); }
  }

  record AddressedOutcome(String docId, String disposition, String reason) {
    public enum Disposition { incorporated, noted, rejected }
    public AddressedOutcome {
      Require.matches(docId, Require.DOC_ID, "addressed_outcomes.doc_id");
      Require.oneOf(disposition, Disposition.class, "addressed_outcomes.disposition");
      Require.nonBlank(reason, "addressed_outcomes.reason");
      Require.maxLen(reason, 500, "addressed_outcomes.reason");
    }
  }

  record Analysis(String decision, String confidence, String reason, List<RetainGrant> retainGrants,
                  List<String> healthScenarios, List<AddressedOutcome> addressedOutcomes, List<String> unknowns,
                  List<String> evidence) implements Decision {
    public enum Verdict { fix, accept, defer }
    public Analysis {
      Require.oneOf(decision, Verdict.class, "decision");
      Require.oneOf(confidence, Confidence.class, "confidence");
      reason = Decision.reason(reason);
      retainGrants = Require.listOrEmpty(retainGrants, 64, "retain_grants");
      healthScenarios = Require.nonBlankStrings(healthScenarios, 16, 40, "health_scenarios");
      addressedOutcomes = Require.listOrEmpty(addressedOutcomes, 32, "addressed_outcomes");
      unknowns = Require.nonBlankStrings(unknowns, 16, 500, "unknowns");
      evidence = Decision.evidence(evidence);
    }
    public Verdict verdict() { return Verdict.valueOf(decision); }
  }

  record Remediation(String decision, String reason, List<String> evidence) implements Decision {
    public enum Verdict { applied, escalate }
    public Remediation {
      Require.oneOf(decision, Verdict.class, "decision");
      reason = Decision.reason(reason);
      evidence = Decision.evidence(evidence);
    }
    public Verdict verdict() { return Verdict.valueOf(decision); }
  }

  record Verification(String decision, String reason, List<String> evidence) implements Decision {
    public enum Verdict { pass, fail, inconclusive }
    public Verification {
      Require.oneOf(decision, Verdict.class, "decision");
      reason = Decision.reason(reason);
      evidence = Decision.evidence(evidence);
    }
    public Verdict verdict() { return Verdict.valueOf(decision); }
  }

  record Supervision(String decision, String reason, List<String> evidence) implements Decision {
    public enum Verdict { reopen, annotate, inconclusive }
    public Supervision {
      Require.oneOf(decision, Verdict.class, "decision");
      reason = Decision.reason(reason);
      evidence = Decision.evidence(evidence);
    }
    public Verdict verdict() { return Verdict.valueOf(decision); }
  }
}
