package adr.domain;

/**
 * A human actor. In D0 every actor is a demo persona chosen in the page, so authenticated is always false and
 * identity_mode is demo_persona. Separation of duties compares persona ids, which proves the rule, not the identity.
 */
public record Actor(String id, String displayName, String role, String persona, boolean authenticated) {
  public Actor {
    Require.nonBlank(id, "actor.id");
    Require.nonBlank(displayName, "actor.display_name");
    Require.nonBlank(role, "actor.role");
    Require.nonBlank(persona, "actor.persona");
  }

  public String identityMode() { return Labels.IDENTITY_MODE; }

  public static final Actor SAM = new Actor("sam", "Sam", "security engineer", "requester", false);
  public static final Actor DANA = new Actor("dana", "Dana", "DBA", "dba", false);

  public static Actor forPersona(String persona) {
    return switch (persona) {
      case "requester" -> SAM;
      case "dba" -> DANA;
      default -> throw new InvalidInput("persona", "unknown value: " + persona);
    };
  }
}
