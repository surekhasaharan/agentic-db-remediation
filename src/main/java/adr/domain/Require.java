package adr.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Explicit checks for missing, null and blank values. Every boundary record calls these in its compact
 * constructor. There is no silent trimming and no default unless the contract documents one.
 */
public final class Require {
  private Require() {}

  public static String nonBlank(String v, String field) {
    if (v == null || v.isBlank()) throw new InvalidInput(field, "missing or blank");
    return v;
  }

  public static <T> T nonNull(T v, String field) {
    if (v == null) throw new InvalidInput(field, "missing");
    return v;
  }

  public static String matches(String v, Pattern p, String field) {
    nonBlank(v, field);
    if (!p.matcher(v).matches()) throw new InvalidInput(field, "does not match " + p.pattern() + ": " + clip(v));
    return v;
  }

  public static String maxLen(String v, int max, String field) {
    if (v != null && v.length() > max) throw new InvalidInput(field, "longer than " + max + " characters");
    return v;
  }

  public static <E extends Enum<E>> E oneOf(String v, Class<E> e, String field) {
    nonBlank(v, field);
    for (E c : e.getEnumConstants()) if (c.name().equals(v)) return c;
    throw new InvalidInput(field, "unknown value: " + clip(v));
  }

  /** Boxed on purpose: null is caught, not defaulted to zero. */
  public static int range(Integer v, int min, int max, String field) {
    if (v == null) throw new InvalidInput(field, "missing");
    if (v < min || v > max) throw new InvalidInput(field, "outside " + min + " to " + max);
    return v;
  }

  public static <T> List<T> nonEmpty(List<T> v, int maxSize, String field) {
    if (v == null || v.isEmpty()) throw new InvalidInput(field, "missing or empty");
    if (v.size() > maxSize) throw new InvalidInput(field, "more than " + maxSize + " entries");
    for (int i = 0; i < v.size(); i++) if (v.get(i) == null) throw new InvalidInput(field + "[" + i + "]", "missing");
    return List.copyOf(v);
  }

  public static <T> List<T> listOrEmpty(List<T> v, int maxSize, String field) {
    if (v == null) throw new InvalidInput(field, "missing");
    if (v.size() > maxSize) throw new InvalidInput(field, "more than " + maxSize + " entries");
    for (int i = 0; i < v.size(); i++) if (v.get(i) == null) throw new InvalidInput(field + "[" + i + "]", "missing");
    return List.copyOf(v);
  }

  public static List<String> nonBlankStrings(List<String> v, int maxSize, int maxLen, String field) {
    List<String> out = listOrEmpty(v, maxSize, field);
    for (int i = 0; i < out.size(); i++) {
      nonBlank(out.get(i), field + "[" + i + "]");
      maxLen(out.get(i), maxLen, field + "[" + i + "]");
    }
    return out;
  }

  private static String clip(String v) {
    return v.length() > 40 ? v.substring(0, 40) + "..." : v;
  }

  public static final Pattern ROLE_NAME = Pattern.compile("^[a-z_][a-z0-9_]{0,39}$");
  public static final Pattern OBJECT_NAME = Pattern.compile("^[a-z_][a-z0-9_]{0,62}(\\([a-z, ]*\\))?$");
  public static final Pattern UUID_REF = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  public static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");
  public static final Pattern DOC_ID = Pattern.compile("^[A-Z]{2,5}-[0-9]{2,5}$");
}
