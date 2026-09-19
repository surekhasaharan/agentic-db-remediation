package adr.app;

import adr.domain.ActorType;
import adr.domain.Labels;
import adr.domain.TimelineEvent;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main {
  public static void main(String[] args) {
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    SessionRegistry registry = new SessionRegistry((id, epoch) -> {
      Session s = new Session(id, epoch, null);
      s.timeline().append(TimelineEvent.of(null, ActorType.system, "session_registry", "session", "system.notice",
          "Session started", Map.of("session_id", id), Labels.PLATFORM));
      return s;
    });
    ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "housekeeping");
      t.setDaemon(true);
      return t;
    });
    housekeeping.scheduleAtFixedRate(() -> registry.sweep(Instant.now()), 1, 1, TimeUnit.MINUTES);
    new Api(registry).build().start(port);
    System.out.println("adr-demo listening on http://localhost:" + port);
  }
}
