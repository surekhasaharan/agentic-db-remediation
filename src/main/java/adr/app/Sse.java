package adr.app;

import adr.domain.ActorType;
import adr.domain.Json;
import adr.domain.Labels;
import adr.domain.TimelineEvent;
import adr.stores.Timeline;
import io.javalin.http.sse.SseClient;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One handler thread per subscriber, holding no lock. Phase 1 replays the backlog straight from the copy;
 * phase 2 serves the live queue with a heartbeat comment every 15 seconds. Overflow closes the client, and
 * the page reconnects with its last seq.
 */
public final class Sse {
  private Sse() {}

  public static final long HEARTBEAT_SECONDS = 15;

  public static void handle(SseClient client, SessionRegistry registry) {
    client.keepAlive();
    var ctx = client.ctx();

    String epoch = ctx.queryParam("epoch");
    long after = parseAfter(ctx.queryParam("after")); // validated by the before-handler in Api
    String lastId = ctx.header("Last-Event-ID");
    if (lastId != null && lastId.contains(":")) {
      int i = lastId.indexOf(':');
      epoch = lastId.substring(0, i);
      try { after = Long.parseLong(lastId.substring(i + 1)); } catch (NumberFormatException ignored) { /* keep query values */ }
    }

    Session session = registry.get(ctx.cookie(Api.COOKIE));
    if (session == null || epoch == null) {
      sendStale(client, epoch);
      return;
    }
    Timeline timeline = session.timeline();
    Timeline.Subscription sub = timeline.subscribe(epoch, after);
    if (sub.stale()) {
      sendStale(client, epoch);
      return;
    }
    Timeline.Subscriber s = sub.subscriber();
    client.onClose(() -> timeline.unsubscribe(s));
    String ep = timeline.epoch();
    try {
      for (TimelineEvent e : sub.backlog()) {
        if (!send(client, ep, e)) return;
        if (e.isTerminal()) { client.close(); return; }
      }
      while (!client.terminated()) {
        if (s.overflowed()) { client.close(); return; }
        TimelineEvent e = s.live().poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        if (e == null) {
          client.sendComment("hb");
          continue;
        }
        if (!send(client, ep, e)) return;
        if (e.isTerminal()) { client.close(); return; }
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } finally {
      timeline.unsubscribe(s);
    }
  }

  /** "after" is a non-negative integer when present. */
  public static long parseAfter(String v) {
    if (v == null) return 0;
    long n;
    try { n = Long.parseLong(v); } catch (NumberFormatException e) { throw new adr.domain.InvalidInput("after", "not an integer"); }
    if (n < 0) throw new adr.domain.InvalidInput("after", "negative");
    return n;
  }

  private static void sendStale(SseClient client, String epoch) {
    TimelineEvent e = TimelineEvent.of(null, ActorType.system, "session_registry", "session", "session.stale",
        "This session is no longer current. Reloading.", Map.of("epoch", epoch == null ? "" : epoch), Labels.PLATFORM);
    try { client.sendEvent(e.kind(), Json.write(Json.SNAKE, e)); } catch (RuntimeException ignored) { /* client gone */ }
    client.close();
  }

  /** Event IDs are epoch:seq, so a reconnect always names the timeline it last saw. */
  private static boolean send(SseClient client, String epoch, TimelineEvent e) {
    if (client.terminated()) return false;
    try {
      String id = e.seq() == null ? null : epoch + ":" + e.seq();
      client.sendEvent(e.kind(), Json.write(Json.SNAKE, e), id);
      return true;
    } catch (RuntimeException ex) {
      return false;
    }
  }
}
