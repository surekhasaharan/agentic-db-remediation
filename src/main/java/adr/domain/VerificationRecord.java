package adr.domain;

import java.util.List;
import java.util.UUID;

/** Results computed by deterministic code before the verifier agent sees them. */
public record VerificationRecord(UUID operationId, List<Check> assertions, List<Check> probes, List<Check> scenarios,
                                 boolean allPassed, String computedAt) {
  public record Check(String name, boolean passed, String detail) {}

  public static VerificationRecord of(UUID op, List<Check> a, List<Check> p, List<Check> s, String at) {
    boolean all = a.stream().allMatch(Check::passed) && p.stream().allMatch(Check::passed) && s.stream().allMatch(Check::passed);
    return new VerificationRecord(op, List.copyOf(a), List.copyOf(p), List.copyOf(s), all, at);
  }

  public List<Check> failed() {
    return java.util.stream.Stream.of(assertions, probes, scenarios).flatMap(List::stream).filter(c -> !c.passed()).toList();
  }
}
