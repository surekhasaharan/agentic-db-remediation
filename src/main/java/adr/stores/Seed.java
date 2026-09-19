package adr.stores;

import adr.domain.InvalidInput;
import adr.domain.Json;
import adr.domain.Require;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/** The seed files, loaded once at start-up, validated, and copied per session. Immutable and shared. */
public final class Seed {

  public record RoleSeed(String name, Boolean canLogin, List<String> attributes) {
    public RoleSeed {
      Require.matches(name, Require.ROLE_NAME, "roles.name");
      Require.nonNull(canLogin, "roles.canLogin");
      attributes = Require.listOrEmpty(attributes, 16, "roles.attributes");
    }
  }

  public record MembershipSeed(String role, String member, String grantor, Boolean adminOption) {
    public MembershipSeed {
      Require.matches(role, Require.ROLE_NAME, "memberships.role");
      Require.matches(member, Require.ROLE_NAME, "memberships.member");
      Require.matches(grantor, Require.ROLE_NAME, "memberships.grantor");
      Require.nonNull(adminOption, "memberships.adminOption");
    }
  }

  public record Requirement(String privilege, String object) {
    public Requirement {
      Require.matches(privilege, adr.domain.GrantSpec.PRIVILEGE, "requires.privilege");
      Require.matches(object, adr.domain.GrantSpec.OBJECT_KEY, "requires.object");
    }
  }

  public record ObjectSeed(String kind, String key, String owner, Boolean publicExecute, List<Requirement> requires) {
    public ObjectSeed {
      Require.oneOf(kind, adr.domain.GrantSpec.Kind.class, "objects.kind");
      Require.matches(key, adr.domain.GrantSpec.OBJECT_KEY, "objects.key");
      Require.matches(owner, Require.ROLE_NAME, "objects.owner");
      Require.nonNull(publicExecute, "objects.publicExecute");
      requires = Require.listOrEmpty(requires, 16, "objects.requires");
    }
  }

  public record GrantSeed(String grantee, String privilege, String object) {
    public GrantSeed {
      Require.matches(grantee, Require.ROLE_NAME, "grants.grantee");
      Require.matches(privilege, adr.domain.GrantSpec.PRIVILEGE, "grants.privilege");
      Require.matches(object, adr.domain.GrantSpec.OBJECT_KEY, "grants.object");
    }
  }

  public record PgStateSeed(String label, List<RoleSeed> roles, List<MembershipSeed> memberships,
                            List<ObjectSeed> objects, List<GrantSeed> grants) {
    public PgStateSeed {
      Require.nonBlank(label, "label");
      roles = Require.nonEmpty(roles, 64, "roles");
      memberships = Require.listOrEmpty(memberships, 64, "memberships");
      objects = Require.nonEmpty(objects, 64, "objects");
      grants = Require.listOrEmpty(grants, 256, "grants");
    }
  }

  public record FindingSeed(String label, String id, String findingType, String assetId, String subjectRole,
                            String ownerRole, String title, String description, String criticality, Integer detectedDaysAgo) {
    public FindingSeed {
      Require.nonBlank(label, "label");
      Require.matches(id, Require.UUID_REF, "id");
      Require.nonBlank(findingType, "findingType");
      Require.nonBlank(assetId, "assetId");
      Require.matches(subjectRole, Require.ROLE_NAME, "subjectRole");
      Require.matches(ownerRole, Require.ROLE_NAME, "ownerRole");
      Require.nonBlank(title, "title");
      Require.nonBlank(description, "description");
      Require.nonBlank(criticality, "criticality");
      Require.range(detectedDaysAgo, 0, 3650, "detectedDaysAgo");
    }
    public UUID uuid() { return UUID.fromString(id); }
  }

  public record AssetSeed(String label, String id, String name, String application, String team, Boolean piiLabel,
                          String environment, String engine) {
    public AssetSeed {
      Require.nonBlank(label, "label");
      Require.nonBlank(id, "id");
      Require.nonBlank(name, "name");
      Require.nonBlank(application, "application");
      Require.nonBlank(team, "team");
      Require.nonNull(piiLabel, "piiLabel");
      Require.nonBlank(environment, "environment");
      Require.nonBlank(engine, "engine");
    }
  }

  public record TelemetryRow(String role, String object, String command, Integer calls, Integer lastSeenDaysAgo) {
    public TelemetryRow {
      Require.matches(role, Require.ROLE_NAME, "rows.role");
      Require.matches(object, adr.domain.GrantSpec.OBJECT_KEY, "rows.object");
      Require.matches(command, adr.domain.GrantSpec.PRIVILEGE, "rows.command");
      Require.range(calls, 0, Integer.MAX_VALUE, "rows.calls");
      Require.range(lastSeenDaysAgo, 0, 3650, "rows.lastSeenDaysAgo");
    }
  }

  public record TelemetrySeed(String label, Integer windowDays, List<TelemetryRow> rows) {
    public TelemetrySeed {
      Require.nonBlank(label, "label");
      Require.range(windowDays, 1, 30, "windowDays");
      rows = Require.nonEmpty(rows, 1024, "rows");
    }
  }

  public record JobRun(String name, String role, String schedule, List<String> calls, Integer lastRunDaysAgo, String lastStatus) {
    public JobRun {
      Require.nonBlank(name, "jobs.name");
      Require.matches(role, Require.ROLE_NAME, "jobs.role");
      Require.nonBlank(schedule, "jobs.schedule");
      calls = Require.nonBlankStrings(calls, 16, 80, "jobs.calls");
      Require.range(lastRunDaysAgo, 0, 3650, "jobs.lastRunDaysAgo");
      Require.nonBlank(lastStatus, "jobs.lastStatus");
    }
  }

  public record JobRunsSeed(String label, List<JobRun> jobs) {
    public JobRunsSeed {
      Require.nonBlank(label, "label");
      jobs = Require.nonEmpty(jobs, 64, "jobs");
    }
  }

  public record AuditRow(Integer daysAgo, String actor, String action, String reason, String ticket) {
    public AuditRow {
      Require.range(daysAgo, 0, 3650, "rows.daysAgo");
      Require.matches(actor, Require.ROLE_NAME, "rows.actor");
      Require.nonBlank(action, "rows.action");
      Require.nonBlank(reason, "rows.reason");
      Require.nonBlank(ticket, "rows.ticket");
    }
  }

  public record AuditSeed(String label, List<AuditRow> rows) {
    public AuditSeed {
      Require.nonBlank(label, "label");
      rows = Require.listOrEmpty(rows, 256, "rows");
    }
  }

  /** Untrusted corpus content: the shape is checked, the text is never interpreted. */
  public record PrivilegeRef(String privilege, String kind, String object) {
    public PrivilegeRef {
      Require.nonBlank(privilege, "requiredPrivileges.privilege");
      Require.nonBlank(kind, "requiredPrivileges.kind");
      Require.nonBlank(object, "requiredPrivileges.object");
    }
  }

  public record CorpusDoc(String id, String kind, String title, String findingType, String assetFamily, String disposition,
                          String failedScenario, List<PrivilegeRef> requiredPrivileges, List<String> tags, String body) {
    public enum Kind { runbook, outcome, note }
    public CorpusDoc {
      Require.matches(id, Require.DOC_ID, "documents.id");
      Require.oneOf(kind, Kind.class, "documents.kind");
      Require.nonBlank(title, "documents.title");
      Require.nonBlank(findingType, "documents.findingType");
      Require.nonBlank(assetFamily, "documents.assetFamily");
      requiredPrivileges = Require.listOrEmpty(requiredPrivileges, 32, "documents.requiredPrivileges");
      tags = Require.nonBlankStrings(tags, 16, 40, "documents.tags");
      Require.nonBlank(body, "documents.body");
      Require.maxLen(body, 4000, "documents.body");
    }
    public boolean isFailedOutcome() {
      return "outcome".equals(kind) && ("rolled_back".equals(disposition) || "failed".equals(disposition));
    }
  }

  public record CorpusSeed(String label, List<CorpusDoc> documents) {
    public CorpusSeed {
      Require.nonBlank(label, "label");
      documents = Require.nonEmpty(documents, 256, "documents");
    }
  }

  public record FleetAsset(String id, String name, String application, String environment, Integer criticality, Boolean piiLabel) {
    public FleetAsset {
      Require.nonBlank(id, "assets.id");
      Require.nonBlank(name, "assets.name");
      Require.nonBlank(application, "assets.application");
      Require.nonBlank(environment, "assets.environment");
      Require.range(criticality, 1, 5, "assets.criticality");
      Require.nonNull(piiLabel, "assets.piiLabel");
    }
  }

  public record FleetControl(String id, String name, Integer weight) {
    public FleetControl {
      Require.nonBlank(id, "controls.id");
      Require.nonBlank(name, "controls.name");
      Require.range(weight, 1, 10, "controls.weight");
    }
  }

  public record FleetSeed(String label, List<FleetAsset> assets, List<FleetControl> controls) {
    public FleetSeed {
      Require.nonBlank(label, "label");
      assets = Require.nonEmpty(assets, 1000, "assets");
      controls = Require.nonEmpty(controls, 100, "controls");
    }
  }

  public record Action(String privilege, String object) {
    public Action {
      Require.matches(privilege, adr.domain.GrantSpec.PRIVILEGE, "actions.privilege");
      Require.matches(object, adr.domain.GrantSpec.OBJECT_KEY, "actions.object");
    }
  }

  public record Scenario(String name, String description, List<Action> actions) {
    public Scenario {
      Require.matches(name, Require.ROLE_NAME, "scenarios.name");
      Require.nonBlank(description, "scenarios.description");
      actions = Require.nonEmpty(actions, 32, "scenarios.actions");
    }
  }

  public record ScenariosSeed(String label, List<Scenario> scenarios) {
    public ScenariosSeed {
      Require.nonBlank(label, "label");
      scenarios = Require.nonEmpty(scenarios, 32, "scenarios");
    }
  }

  public final PgStateSeed pgState;
  public final FindingSeed finding;
  public final AssetSeed asset;
  public final TelemetrySeed telemetry;
  public final JobRunsSeed jobRuns;
  public final AuditSeed audit;
  public final CorpusSeed corpus;
  public final FleetSeed fleet;
  public final ScenariosSeed scenarios;

  private Seed(PgStateSeed pgState, FindingSeed finding, AssetSeed asset, TelemetrySeed telemetry, JobRunsSeed jobRuns,
               AuditSeed audit, CorpusSeed corpus, FleetSeed fleet, ScenariosSeed scenarios) {
    this.pgState = pgState;
    this.finding = finding;
    this.asset = asset;
    this.telemetry = telemetry;
    this.jobRuns = jobRuns;
    this.audit = audit;
    this.corpus = corpus;
    this.fleet = fleet;
    this.scenarios = scenarios;
  }

  private static volatile Seed instance;

  public static Seed load() {
    Seed s = instance;
    if (s == null) {
      synchronized (Seed.class) {
        s = instance;
        if (s == null) instance = s = loadFromClasspath();
      }
    }
    return s;
  }

  private static Seed loadFromClasspath() {
    return new Seed(
        read("pg-state.json", PgStateSeed.class),
        read("finding.json", FindingSeed.class),
        read("asset.json", AssetSeed.class),
        read("telemetry.json", TelemetrySeed.class),
        read("job-runs.json", JobRunsSeed.class),
        read("audit.json", AuditSeed.class),
        read("corpus.json", CorpusSeed.class),
        read("fleet.json", FleetSeed.class),
        read("scenarios.json", ScenariosSeed.class));
  }

  private static <T> T read(String file, Class<T> type) {
    try (InputStream in = Seed.class.getResourceAsStream("/seed/" + file)) {
      if (in == null) throw new IllegalStateException("missing seed file " + file);
      return Json.read(Json.CAMEL, in, type);
    } catch (InvalidInput e) {
      throw new IllegalStateException("seed/" + file + ": " + e.getMessage(), e);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("seed/" + file + ": " + e.getMessage(), e);
    }
  }
}
