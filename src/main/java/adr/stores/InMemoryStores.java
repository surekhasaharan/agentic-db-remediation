package adr.stores;

import adr.domain.Finding;

/** One reviewer's stores, seeded from the shared immutable seed. */
public final class InMemoryStores implements Stores {
  private final Timeline timeline;
  private final Findings findings = new Findings();
  private final Operations operations = new Operations();
  private final Plans plans = new Plans();
  private final Approvals approvals = new Approvals();
  private final EvidenceLog evidence = new EvidenceLog();
  private final Corpus corpus;
  private final Telemetry telemetry;
  private final AuditFeed audit;
  private final Seed seed;

  public InMemoryStores(String epoch, Seed seed) {
    this.seed = seed;
    this.timeline = new Timeline(epoch);
    this.corpus = new Corpus(seed.corpus);
    this.telemetry = new Telemetry(seed.telemetry, seed.jobRuns);
    this.audit = new AuditFeed(seed.audit);
    Seed.FindingSeed f = seed.finding;
    findings.add(new Finding(f.uuid(), null, f.findingType(), f.assetId(), f.subjectRole(), f.ownerRole(),
        f.title(), f.description(), f.criticality()));
  }

  public Seed seed() { return seed; }
  @Override public Timeline timeline() { return timeline; }
  @Override public Findings findings() { return findings; }
  @Override public Operations operations() { return operations; }
  @Override public Plans plans() { return plans; }
  @Override public Approvals approvals() { return approvals; }
  @Override public EvidenceLog evidence() { return evidence; }
  @Override public Corpus corpus() { return corpus; }
  @Override public Telemetry telemetry() { return telemetry; }
  @Override public AuditFeed audit() { return audit; }
}
