package adr.domain;

import java.util.regex.Pattern;

/** One privilege in a plan. Object keys are "schema", "schema.table" or "schema.function(args)". */
public record GrantSpec(String privilege, String kind, String object) implements Comparable<GrantSpec> {
  public static final Pattern PRIVILEGE = Pattern.compile("^[A-Z]{3,12}$");
  public static final Pattern OBJECT_KEY = Pattern.compile("^[a-z_][a-z0-9_]{0,62}(\\.[a-z_][a-z0-9_]{0,62}(\\([a-z0-9_, ]*\\))?)?$");

  public enum Kind { schema, table, function }

  public GrantSpec {
    Require.matches(privilege, PRIVILEGE, "privilege");
    Require.oneOf(kind, Kind.class, "kind");
    Require.matches(object, OBJECT_KEY, "object");
  }

  public String schema() {
    int dot = object.indexOf('.');
    return dot < 0 ? object : object.substring(0, dot);
  }

  @Override public int compareTo(GrantSpec o) {
    int c = object.compareTo(o.object);
    if (c != 0) return c;
    c = privilege.compareTo(o.privilege);
    return c != 0 ? c : kind.compareTo(o.kind);
  }

  @Override public String toString() { return privilege + " on " + object; }
}
