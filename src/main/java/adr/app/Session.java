package adr.app;

import adr.chaos.ChaosSwitches;
import adr.domain.ActorType;
import adr.domain.Counters;
import adr.domain.FindingState;
import adr.domain.Labels;
import adr.domain.TimelineEvent;
import adr.stores.Findings;
import adr.stores.InMemoryStores;
import adr.stores.Seed;
import adr.stores.SessionHandle;
import adr.stores.Timeline;
import adr.target.AppSimulator;
import adr.target.PgState;
import adr.target.SimulatedPgTarget;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One reviewer's private world. Reset replaces the object; nothing survives a restart by design. */
public final class Session implements SessionHandle {
  public static final Duration TTL = Duration.ofMinutes(30);
  public static final int REFUSALS_RECORDED_AFTER_CAP = 100;

  private final String id;
  private final String epoch;
  private final SessionRegistry registry;
  private final Object lock = new Object();
  private final InMemoryStores stores;
  private final SimulatedPgTarget target;
  private final AppSimulator appSimulator;
  private volatile UUID activeFindingId;
  private final ChaosSwitches chaos = new ChaosSwitches();
  private final Counters counters = new Counters();
  private final AtomicInteger activeRuns = new AtomicInteger();
  private final AtomicBoolean burstRunning = new AtomicBoolean();
  private final AtomicBoolean killSwitch = new AtomicBoolean();
  private final Map<String, Commands.Result> idempotency = new LinkedHashMap<>(64, 0.75f, false) {
    @Override protected boolean removeEldestEntry(Map.Entry<String, Commands.Result> e) { return size() > 500; }
  };
  private volatile Instant lastTouched = Instant.now();

  private Session(String id, String epoch, SessionRegistry registry, InMemoryStores stores, Seed seed) {
    this.id = id;
    this.epoch = epoch;
    this.registry = registry;
    this.stores = stores;
    this.target = new SimulatedPgTarget(PgState.fromSeed(seed.pgState), seed.finding.subjectRole(), seed.finding.ownerRole());
    this.appSimulator = new AppSimulator(seed.scenarios);
  }

  public static Session fromSeed(String id, String epoch, SessionRegistry registry, Seed seed) {
    InMemoryStores stores = new InMemoryStores(epoch, seed);
    Session s = new Session(id, epoch, registry, stores, seed);
    s.activeFindingId = seed.finding.uuid();
    stores.timeline().append(TimelineEvent.of(null, ActorType.system, "session_registry", "session", "system.notice",
        "Session started. Everything here lives in memory until reset or expiry.",
        Map.of("session_id", id, "epoch", epoch), Labels.PLATFORM));
    return s;
  }

  public String id() { return id; }
  public String epoch() { return epoch; }
  public InMemoryStores stores() { return stores; }
  public Timeline timeline() { return stores.timeline(); }
  public SimulatedPgTarget target() { return target; }
  public AppSimulator appSimulator() { return appSimulator; }
  public UUID activeFindingId() { return activeFindingId; }
  public void activeFindingId(UUID id) { activeFindingId = id; }
  public ChaosSwitches chaos() { return chaos; }
  public Counters counters() { return counters; }
  public AtomicInteger activeRuns() { return activeRuns; }
  public AtomicBoolean burstRunning() { return burstRunning; }
  public AtomicBoolean killSwitch() { return killSwitch; }
  public Map<String, Commands.Result> idempotency() { return idempotency; }
  public Instant lastTouched() { return lastTouched; }
  public Instant expiresAt() { return lastTouched.plus(TTL); }
  public void touch() { lastTouched = Instant.now(); }
  public boolean isExpired(Instant now) { return now.isAfter(expiresAt()); }

  /** Reset is refused while anything is running; busy considers every finding, not only the active one. */
  public boolean busy() {
    if (activeRuns.get() > 0 || burstRunning.get()) return true;
    for (Findings.Entry e : stores.findings().all()) {
      FindingState s = e.state();
      if (!s.isStable()) return true;
    }
    return false;
  }

  /** Refusals are counted always and recorded until the cap plus 100. */
  public void recordRefusal(TimelineEvent e) {
    counters.refusals.incrementAndGet();
    Timeline t = stores.timeline();
    if (t.isClosed()) return;
    if (!t.atCap()) {
      t.append(e);
      return;
    }
    int n = counters.refusalsRecordedAfterCap.incrementAndGet();
    if (n < REFUSALS_RECORDED_AFTER_CAP) {
      t.append(e);
    } else if (n == REFUSALS_RECORDED_AFTER_CAP) {
      t.append(TimelineEvent.of(e.findingId(), ActorType.gate, e.actor(), e.stage(), e.kind(),
          e.summary() + ". Further refusals will not be recorded in this timeline.", e.payload(), e.sourceLabel()));
    } else {
      counters.refusalsNotRecorded.incrementAndGet();
    }
  }

  @Override public Object lock() { return lock; }
  @Override public boolean isCurrent() { return registry == null || registry.isCurrent(this); }
}
