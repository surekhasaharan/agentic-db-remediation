package adr.target;

import adr.domain.GrantSpec;
import adr.stores.Seed;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The slice of PostgreSQL this finding touches: roles, memberships, ownership, grants, plus the ledger and
 * the applied-plan set. Immutable; a commit is one reference swap of a new instance.
 */
public record PgState(Map<String, Role> roles, Set<Membership> memberships, Map<String, DbObject> objects,
                      Set<Grant> grants, Map<UUID, LedgerRow> ledger, Set<String> appliedPlanHashes) {

  public record Role(String name, boolean canLogin, Set<String> attributes) {}
  public record Membership(String role, String member, String grantor, boolean adminOption) {}
  public record Requirement(String privilege, String object) {}
  public record DbObject(String kind, String key, String owner, boolean publicExecute, List<Requirement> requires) {
    public String schema() {
      int dot = key.indexOf('.');
      return dot < 0 ? key : key.substring(0, dot);
    }
  }
  public record Grant(String grantee, String privilege, String object) {}
  public record LedgerRow(UUID operationId, String planHash, String before, String after, List<GrantSpec> appliedGrants,
                          String removedMembership, String appliedAt) {}

  public PgState {
    roles = Map.copyOf(roles);
    memberships = Set.copyOf(memberships);
    objects = Map.copyOf(objects);
    grants = Set.copyOf(grants);
    ledger = Map.copyOf(ledger);
    appliedPlanHashes = Set.copyOf(appliedPlanHashes);
  }

  public static PgState fromSeed(Seed.PgStateSeed seed) {
    Map<String, Role> roles = new LinkedHashMap<>();
    for (Seed.RoleSeed r : seed.roles()) roles.put(r.name(), new Role(r.name(), r.canLogin(), new TreeSet<>(r.attributes())));
    Set<Membership> memberships = new HashSet<>();
    for (Seed.MembershipSeed m : seed.memberships()) memberships.add(new Membership(m.role(), m.member(), m.grantor(), m.adminOption()));
    Map<String, DbObject> objects = new LinkedHashMap<>();
    for (Seed.ObjectSeed o : seed.objects()) {
      objects.put(o.key(), new DbObject(o.kind(), o.key(), o.owner(),
          o.publicExecute(), o.requires().stream().map(q -> new Requirement(q.privilege(), q.object())).toList()));
    }
    Set<Grant> grants = new HashSet<>();
    for (Seed.GrantSeed g : seed.grants()) grants.add(new Grant(g.grantee(), g.privilege(), g.object()));
    for (Membership m : memberships) {
      if (!roles.containsKey(m.role()) || !roles.containsKey(m.member()))
        throw new IllegalStateException("membership names an unknown role: " + m);
    }
    for (DbObject o : objects.values()) {
      if (!roles.containsKey(o.owner())) throw new IllegalStateException("object owned by unknown role: " + o.key());
      if (!o.kind().equals("schema") && !objects.containsKey(o.schema()))
        throw new IllegalStateException("object in unknown schema: " + o.key());
    }
    return new PgState(roles, memberships, objects, grants, Map.of(), Set.of());
  }

  /** The role plus everything it is a member of, transitively. */
  public Set<String> effectiveRoles(String role) {
    Set<String> out = new TreeSet<>();
    Deque<String> todo = new ArrayDeque<>();
    todo.add(role);
    while (!todo.isEmpty()) {
      String r = todo.poll();
      if (!out.add(r)) continue;
      for (Membership m : memberships) if (m.member().equals(r)) todo.add(m.role());
    }
    return out;
  }

  public boolean hasGrant(Set<String> effective, String privilege, String object) {
    for (Grant g : grants) if (effective.contains(g.grantee()) && g.privilege().equals(privilege) && g.object().equals(object)) return true;
    return false;
  }

  public PgState plusGrants(String grantee, List<GrantSpec> specs) {
    Set<Grant> g = new HashSet<>(grants);
    for (GrantSpec s : specs) g.add(new Grant(grantee, s.privilege(), s.object()));
    return new PgState(roles, memberships, objects, g, ledger, appliedPlanHashes);
  }

  public PgState minusMembership(String role, String member) {
    Set<Membership> m = new HashSet<>();
    for (Membership x : memberships) if (!(x.role().equals(role) && x.member().equals(member))) m.add(x);
    return new PgState(roles, m, objects, grants, ledger, appliedPlanHashes);
  }

  public PgState plusMembership(Membership added) {
    Set<Membership> m = new HashSet<>(memberships);
    m.removeIf(x -> x.role().equals(added.role()) && x.member().equals(added.member()));
    m.add(added);
    return new PgState(roles, m, objects, grants, ledger, appliedPlanHashes);
  }

  public PgState plusLedger(LedgerRow row) {
    Map<UUID, LedgerRow> l = new HashMap<>(ledger);
    l.put(row.operationId(), row);
    Set<String> h = new HashSet<>(appliedPlanHashes);
    h.add(row.planHash());
    return new PgState(roles, memberships, objects, grants, l, h);
  }

  public Membership membership(String role, String member) {
    for (Membership m : memberships) if (m.role().equals(role) && m.member().equals(member)) return m;
    return null;
  }
}
