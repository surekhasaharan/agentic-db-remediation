package adr.broker;

/** Thrown by a handler or a precondition; the broker turns it into the error envelope. */
public final class ToolException extends RuntimeException {
  private final ToolError error;

  public ToolException(ToolError error) {
    super(error.code() + ": " + error.message());
    this.error = error;
  }

  public ToolError error() { return error; }
}
