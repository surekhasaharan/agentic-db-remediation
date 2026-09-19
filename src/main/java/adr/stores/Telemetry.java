package adr.stores;

import java.util.List;

/** Seeded statement shapes and job history. Read-only. */
public final class Telemetry {
  private final Seed.TelemetrySeed telemetry;
  private final Seed.JobRunsSeed jobs;

  public Telemetry(Seed.TelemetrySeed telemetry, Seed.JobRunsSeed jobs) {
    this.telemetry = telemetry;
    this.jobs = jobs;
  }

  public int windowDays() { return telemetry.windowDays(); }

  public List<Seed.TelemetryRow> rowsFor(String role, int days) {
    return telemetry.rows().stream().filter(r -> r.role().equals(role) && r.lastSeenDaysAgo() <= days).toList();
  }

  public List<Seed.JobRun> jobsFor(String role) {
    return jobs.jobs().stream().filter(j -> j.role().equals(role)).toList();
  }

  public List<Seed.TelemetryRow> allRows() { return telemetry.rows(); }
  public List<Seed.JobRun> allJobs() { return jobs.jobs(); }
}
