package adr.target;

import adr.domain.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A role's control fingerprint: its attributes, memberships and direct grants, canonicalised and hashed. */
public final class Fingerprint {
  private Fingerprint() {}

  public static Map<String, Object> snapshot(PgState s, String role) {
    PgState.Role r = s.roles().get(role);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("role", role);
    m.put("can_login", r != null && r.canLogin());
    m.put("attributes", r == null ? List.of() : new ArrayList<>(r.attributes()));
    List<Map<String, Object>> memberships = new ArrayList<>();
    s.memberships().stream().filter(x -> x.member().equals(role))
        .sorted((a, b) -> a.role().compareTo(b.role()))
        .forEach(x -> memberships.add(Map.of("role", x.role(), "grantor", x.grantor(), "admin_option", x.adminOption())));
    m.put("memberships", memberships);
    List<Map<String, Object>> grants = new ArrayList<>();
    s.grants().stream().filter(g -> g.grantee().equals(role))
        .sorted((a, b) -> a.object().equals(b.object()) ? a.privilege().compareTo(b.privilege()) : a.object().compareTo(b.object()))
        .forEach(g -> grants.add(Map.of("privilege", g.privilege(), "object", g.object())));
    m.put("grants", grants);
    m.put("effective_roles", new ArrayList<>(s.effectiveRoles(role)));
    return m;
  }

  public static String of(PgState s, String role) {
    Map<String, Object> snap = snapshot(s, role);
    Map<String, Object> hashed = new TreeMap<>();
    hashed.put("role", snap.get("role"));
    hashed.put("attributes", snap.get("attributes"));
    hashed.put("memberships", snap.get("memberships"));
    hashed.put("grants", snap.get("grants"));
    return sha256(Json.write(Json.SNAKE, hashed));
  }

  public static String sha256(String canonical) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** What changed between two snapshots, as added and removed memberships and grants. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
    Map<String, Object> d = new LinkedHashMap<>();
    List<Object> mb = (List<Object>) before.getOrDefault("memberships", List.of());
    List<Object> ma = (List<Object>) after.getOrDefault("memberships", List.of());
    List<Object> gb = (List<Object>) before.getOrDefault("grants", List.of());
    List<Object> ga = (List<Object>) after.getOrDefault("grants", List.of());
    List<Object> addedM = new ArrayList<>(ma); addedM.removeAll(mb);
    List<Object> removedM = new ArrayList<>(mb); removedM.removeAll(ma);
    List<Object> addedG = new ArrayList<>(ga); addedG.removeAll(gb);
    List<Object> removedG = new ArrayList<>(gb); removedG.removeAll(ga);
    if (!addedM.isEmpty()) d.put("added_memberships", addedM);
    if (!removedM.isEmpty()) d.put("removed_memberships", removedM);
    if (!addedG.isEmpty()) d.put("added_grants", addedG);
    if (!removedG.isEmpty()) d.put("removed_grants", removedG);
    return d;
  }
}
