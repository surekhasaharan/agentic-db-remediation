package adr.domain;

/** The honesty labels. Everything simulated carries one of these; nothing is simulated silently. */
public final class Labels {
  private Labels() {}

  public static final String AGENT_MODE = "recorded";
  public static final String TARGET_MODE = "simulated";
  public static final String PERSISTENCE = "in_memory_session";
  public static final String IDENTITY_MODE = "demo_persona";

  public static final String PLATFORM = "Platform record";
  public static final String SIMULATED_PG = "Simulated PostgreSQL";
  public static final String TELEMETRY = "Seeded demo telemetry";
  public static final String AUDIT = "Simulated audit feed";
  public static final String CORPUS = "Knowledge corpus";
  public static final String APP_SIM = "Application simulator";

  public static final String PRODUCT = "Agentic Database Security Remediation";
  public static final String TAGLINE = "From finding to verified recovery";
  public static final String BANNER =
      "Demo mode: Recorded agents \u2022 Simulated PostgreSQL target \u2022 Demo personas \u2022 "
          + "Live tools, policy, guards, workflow, and fault handling";
  public static final String PERSONA_TAG = "demo persona, not authenticated";
}
