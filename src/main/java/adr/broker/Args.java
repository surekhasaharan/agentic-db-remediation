package adr.broker;

import adr.domain.GrantSpec;
import adr.domain.Require;

import java.util.List;
import java.util.UUID;

/** One argument record per tool. Each validates missing, null and blank values in its constructor. */
public final class Args {
  private Args() {}

  public record RoleArgs(String role) {
    public RoleArgs { role = Require.matches(role, Require.ROLE_NAME, "role"); }
  }

  public record ReadCatalogObjectArgs(String schema, String name, String kind) {
    public ReadCatalogObjectArgs {
      schema = Require.matches(schema, Require.ROLE_NAME, "schema");
      name = Require.matches(name, Require.OBJECT_NAME, "name");
      Require.oneOf(kind, GrantSpec.Kind.class, "kind");
    }
  }

  public record ReadQueryTelemetryArgs(String role, Integer days) {
    public ReadQueryTelemetryArgs {
      role = Require.matches(role, Require.ROLE_NAME, "role");
      days = Require.range(days == null ? 14 : days, 1, 30, "days"); // the one documented default
    }
  }

  public record SearchOutcomesArgs(List<String> terms) {
    public SearchOutcomesArgs { terms = Require.nonBlankStrings(Require.nonEmpty(terms, 8, "terms"), 8, 40, "terms"); }
  }

  public record OperationRefArgs(String operationRef) {
    public OperationRefArgs { operationRef = Require.matches(operationRef, Require.UUID_REF, "operation_ref"); }
    public UUID id() { return UUID.fromString(operationRef); }
  }

  public record FindingRefArgs(String findingRef) {
    public FindingRefArgs { findingRef = Require.matches(findingRef, Require.UUID_REF, "finding_ref"); }
    public UUID id() { return UUID.fromString(findingRef); }
  }

  public record HealthScenariosArgs(String role, List<String> scenarios) {
    public HealthScenariosArgs {
      role = Require.matches(role, Require.ROLE_NAME, "role");
      scenarios = Require.nonBlankStrings(Require.nonEmpty(scenarios, 16, "scenarios"), 16, 40, "scenarios");
    }
  }

  public record MembershipArgs(String role, String member) {
    public MembershipArgs {
      role = Require.matches(role, Require.ROLE_NAME, "role");
      member = Require.matches(member, Require.ROLE_NAME, "member");
    }
  }
}
