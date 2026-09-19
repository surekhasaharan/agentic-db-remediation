package adr.target;

import java.util.Set;

/**
 * The privilege rule, used by every probe and scenario. Five rules, strict enough that a wrong plan really fails:
 * effective roles are transitive; owners may do anything on their objects; otherwise a matching direct grant
 * plus USAGE on the schema is needed; executing a security-invoker function also evaluates its requirements
 * as the caller; PUBLIC execute is per function.
 */
public final class Privileges {
  private Privileges() {}

  public static final String PERMISSION_DENIED = "42501";
  public static final String UNDEFINED_OBJECT = "42P01";

  public record SqlAction(String privilege, String object) {
    @Override public String toString() { return privilege + " " + object; }
  }

  public record ProbeResult(boolean ok, String sqlstate, String detail) {
    public static ProbeResult ok(String detail) { return new ProbeResult(true, null, detail); }
    public static ProbeResult denied(String detail) { return new ProbeResult(false, PERMISSION_DENIED, detail); }
  }

  public static ProbeResult attempt(PgState s, String role, SqlAction a) {
    PgState.DbObject obj = s.objects().get(a.object());
    if (obj == null) return new ProbeResult(false, UNDEFINED_OBJECT, "object " + a.object() + " does not exist");
    Set<String> eff = s.effectiveRoles(role);

    if (eff.contains(obj.owner())) return ProbeResult.ok(role + " owns " + obj.key() + " through " + obj.owner());

    // Ownership-only operations.
    if (a.privilege().equals("DROP") || (a.privilege().equals("CREATE") && !obj.kind().equals("schema"))) {
      return ProbeResult.denied("permission denied for " + obj.kind() + " " + obj.key() + ": " + a.privilege() + " requires ownership");
    }

    boolean granted = s.hasGrant(eff, a.privilege(), obj.key())
        || (obj.kind().equals("function") && a.privilege().equals("EXECUTE") && obj.publicExecute());
    if (!granted) return ProbeResult.denied("permission denied for " + obj.kind() + " " + obj.key() + ": no " + a.privilege() + " grant");

    if (!obj.kind().equals("schema")) {
      PgState.DbObject schema = s.objects().get(obj.schema());
      boolean usage = schema != null && (eff.contains(schema.owner()) || s.hasGrant(eff, "USAGE", schema.key()));
      if (!usage) return ProbeResult.denied("permission denied for schema " + obj.schema() + ": no USAGE grant");
    }

    if (obj.kind().equals("function") && a.privilege().equals("EXECUTE")) {
      for (PgState.Requirement r : obj.requires()) {
        ProbeResult inner = attempt(s, role, new SqlAction(r.privilege(), r.object()));
        if (!inner.ok()) return new ProbeResult(false, inner.sqlstate(), "inside " + obj.key() + ": " + inner.detail());
      }
    }
    return ProbeResult.ok(a.privilege() + " on " + obj.key() + " allowed by grant");
  }
}
