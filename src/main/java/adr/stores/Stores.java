package adr.stores;

/** The store extension point. D0: InMemoryStores. Later: PostgresStores. */
public interface Stores {
  Timeline timeline();
  Findings findings();
  Operations operations();
  Plans plans();
  Approvals approvals();
  EvidenceLog evidence();
  Corpus corpus();
  Telemetry telemetry();
  AuditFeed audit();
}
