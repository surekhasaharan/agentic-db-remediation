package adr.app;

import adr.domain.ActorType;
import adr.domain.Labels;
import adr.domain.TimelineEvent;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** At most 20 live sessions. The oldest idle one is evicted when full; a sweep drops expired ones. */
public final class SessionRegistry {
  public static final int MAX_SESSIONS = 20;

  public interface Factory {
    Session create(String id, String epoch, SessionRegistry registry);
  }

  private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
  private final Factory factory;
  private final SecureRandom random = new SecureRandom();

  public SessionRegistry(Factory factory) {
    this.factory = factory;
  }

  public Session get(String id) {
    if (id == null) return null;
    Session s = sessions.get(id);
    if (s != null) s.touch();
    return s;
  }

  public boolean isCurrent(Session s) {
    return s != null && sessions.get(s.id()) == s;
  }

  public Session mint() {
    synchronized (this) {
      if (sessions.size() >= MAX_SESSIONS) evictOldestIdle();
      String id = randomHex(16);
      Session s = factory.create(id, newEpoch(), this);
      sessions.put(id, s);
      return s;
    }
  }

  /** Swaps a fresh session in under the same id. The caller holds old.lock() and has checked busy(). */
  public Session replace(Session old) {
    Session fresh = factory.create(old.id(), newEpoch(), this);
    sessions.put(old.id(), fresh);
    return fresh;
  }

  public void sweep(Instant now) {
    for (Map.Entry<String, Session> e : sessions.entrySet()) {
      Session s = e.getValue();
      if (s.isExpired(now) && !s.busy()) expire(s);
    }
  }

  public List<Session> live() { return List.copyOf(sessions.values()); }

  private void evictOldestIdle() {
    sessions.values().stream().min(Comparator.comparing(Session::lastTouched)).ifPresent(this::expire);
  }

  private void expire(Session s) {
    if (sessions.remove(s.id(), s)) {
      s.timeline().closeWith(TimelineEvent.of(null, ActorType.system, "session_registry", "session",
          "session.expired", "Session expired", Map.of("session_id", s.id()), Labels.PLATFORM));
    }
  }

  public int size() { return sessions.size(); }

  public String newEpoch() { return randomHex(8); }

  private String randomHex(int bytes) {
    byte[] b = new byte[bytes];
    random.nextBytes(b);
    return HexFormat.of().formatHex(b);
  }
}
