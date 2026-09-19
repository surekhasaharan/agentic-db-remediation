package adr.domain;

/** A refused input. Carries the field path and the problem, never the offending value beyond 40 characters. */
public final class InvalidInput extends RuntimeException {
  private final String field;
  private final String problem;

  public InvalidInput(String field, String problem) {
    super(field + ": " + problem);
    this.field = field;
    this.problem = problem;
  }

  public String field() { return field; }
  public String problem() { return problem; }
}
