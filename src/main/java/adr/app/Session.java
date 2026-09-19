package adr.app;

import adr.stores.SessionHandle;
import adr.stores.Timeline;

import java.time.Duration;
import java.time.Instant;

/** One reviewer's private world. Reset replaces the object; nothing survives a restart by design. */
public final class Session implements SessionHandle {
  public static final Duration TTL = Duration.ofMinutes(30);

  private final String id;
  private final String epoch;
  private final SessionRegistry registry;
  private final Object lock = new Object();
  private final Timeline timeline;
  private volatile Instant lastTouched = Instant.now();

  public Session(String id, String epoch, SessionRegistry registry) {
    this.id = id;
    this.epoch = epoch;
    this.registry = registry;
    this.timeline = new Timeline(epoch);
  }

  public String id() { return id; }
  public String epoch() { return epoch; }
  public Timeline timeline() { return timeline; }
  public Instant lastTouched() { return lastTouched; }
  public Instant expiresAt() { return lastTouched.plus(TTL); }
  public void touch() { lastTouched = Instant.now(); }
  public boolean isExpired(Instant now) { return now.isAfter(expiresAt()); }

  @Override public Object lock() { return lock; }
  @Override public boolean isCurrent() { return registry == null || registry.isCurrent(this); }
}
