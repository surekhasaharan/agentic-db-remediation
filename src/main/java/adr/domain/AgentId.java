package adr.domain;

/** The four agents and the simulated database identity each acts through. */
public enum AgentId {
  analysis("agent_analysis", "Analysis agent"),
  remediation("agent_remediator", "Remediation agent"),
  verification("agent_verifier", "Verification agent"),
  supervision("agent_supervisor", "Supervision agent");

  public final String caller;
  public final String displayName;

  AgentId(String caller, String displayName) {
    this.caller = caller;
    this.displayName = displayName;
  }
}
