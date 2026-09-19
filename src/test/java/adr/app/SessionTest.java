package adr.app;

import adr.TestSession;
import adr.domain.ActorType;
import adr.domain.FindingState;
import adr.domain.Json;
import adr.domain.Labels;
import adr.domain.TimelineEvent;
import adr.stores.Timeline;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionTest {

  static TimelineEvent filler() {
    return TimelineEvent.of(null, ActorType.system, "test", "session", "system.notice", "filler", Map.of(), Labels.PLATFORM);
  }

  /** I17: reset is refused on the server while busy, and can_reset always matches. */
  @Test
  void resetRefusedWhileBusy() {
    for (FindingState state : FindingState.values()) {
      TestSession t = new TestSession();
      t.driveTo(state);
      Map<String, Object> snap = Snapshot.of(t.session);
      assertEquals(state.isStable(), snap.get("can_reset"), "can_reset in " + state);
      Commands.Result r = t.send("reset", "requester", Map.of());
      if (state.isStable()) {
        assertEquals(200, r.status(), "reset in " + state);
        assertEquals(true, r.body().get("reset"));
      } else {
        assertEquals(409, r.status(), "reset in " + state);
        assertEquals("BUSY", r.code());
        assertEquals(state, t.state());
      }
    }
    TestSession t = new TestSession();
    t.session.burstRunning().set(true);
    assertEquals(409, t.send("reset", "requester", Map.of()).status());
    assertEquals(false, Snapshot.of(t.session).get("can_reset"));
    t.session.burstRunning().set(false);
    t.session.activeRuns().incrementAndGet();
    assertEquals(409, t.send("reset", "requester", Map.of()).status());
    t.session.activeRuns().decrementAndGet();
    assertEquals(200, t.send("reset", "requester", Map.of()).status());
  }

  /** I22 at the session level. */
  @Test
  void resetHandsSubscribersToNewTimeline() {
    TestSession t = new TestSession();
    Session old = t.session;
    Timeline.Subscription sub = old.timeline().subscribe(old.epoch(), 0);
    Commands.Result r = t.send("reset", "requester", Map.of());
    assertEquals(200, r.status());
    String newEpoch = (String) r.body().get("epoch");
    TimelineEvent last = null, e;
    while ((e = sub.subscriber().live().poll()) != null) last = e;
    assertNotNull(last);
    assertEquals("session.reset", last.kind());
    assertEquals(newEpoch, last.payload().get("new_epoch"));
    assertTrue(old.timeline().subscribe(old.epoch(), 0).stale());
    assertFalse(old.isCurrent());
    Session fresh = t.registry.get(old.id());
    assertNotSame(old, fresh);
    assertEquals(newEpoch, fresh.epoch());
    assertTrue(fresh.timeline().subscribe(old.epoch(), 0).stale(), "old epoch is stale on the new timeline");
    Timeline.Subscription s2 = fresh.timeline().subscribe(newEpoch, 0);
    assertFalse(s2.stale());
    assertEquals(1, s2.backlog().get(0).seq());
    assertThrows(IllegalStateException.class, () -> old.timeline().append(filler()));
  }

  @Test
  void timelineCapRefusesFurtherCommands() {
    TestSession t = new TestSession();
    Timeline tl = t.session.timeline();
    while (tl.size() < Timeline.CAP) tl.append(filler());
    assertTrue(tl.atCap());
    int before = tl.size();
    Commands.Result r = t.send("start_analysis", "requester", t.args("finding_id", t.findingId().toString()));
    assertEquals(409, r.status());
    assertEquals("SESSION_FULL", r.code());
    assertEquals(FindingState.OPEN, t.state());
    assertEquals(before + 1, tl.size(), "the refusal the viewer just caused is visible");
    assertEquals("gate.checked", tl.read(before).get(0).kind());
    // work admitted before the cap still appends: appendAll never refuses on an open timeline
    tl.append(filler());
    // the first 100 refusals after the cap are recorded; after that HTTP only
    int sizeBefore = tl.size();
    for (int i = 0; i < 120; i++) t.send("arm_chaos", "requester", t.args("switch", "nope"));
    assertEquals(121, t.session.counters().refusals.get());
    assertEquals(21, t.session.counters().refusalsNotRecorded.get(), "later refusals are HTTP-only");
    assertEquals(sizeBefore + 99, tl.size(), "one refusal was already recorded after the cap; 99 more, the last saying no more");
    assertTrue(tl.all().get(tl.size() - 1).summary().contains("Further refusals will not be recorded"));
    Map<String, Object> counters = Snapshot.of(t.session);
    assertTrue(((Map<?, ?>) counters.get("counters")).containsKey("refusals_not_recorded"));
    // reset still works, and session.reset still appends and closes
    Commands.Result reset = t.send("reset", "requester", Map.of());
    assertEquals(200, reset.status());
    assertTrue(tl.isClosed());
    assertEquals("session.reset", tl.all().get(tl.size() - 1).kind());
  }

  @Test
  void sessionsAreIsolated() {
    TestSession a = new TestSession();
    TestSession b = new TestSession();
    assertNotSame(a.session.stores(), b.session.stores());
    a.send("start_analysis", "requester", a.args("finding_id", a.findingId().toString()));
    assertEquals(FindingState.PLAN_READY, a.state());
    assertEquals(FindingState.OPEN, b.state());
    assertNotEquals(a.session.timeline().lastSeq(), b.session.timeline().lastSeq());
    b.session.stores().audit().append(new adr.stores.Seed.AuditRow(1, "dba_oncall", "GRANT x TO y", "test", "T-1"));
    assertEquals(2, a.session.stores().audit().all().size());
    assertEquals(3, b.session.stores().audit().all().size());
  }

  @Test
  void noDurableFieldAndPersistenceIsLabelled() {
    TestSession t = new TestSession();
    String json = Json.write(Json.SNAKE, t.session.timeline().all().get(0));
    assertFalse(json.contains("durable"));
    assertTrue(json.contains("\"seq\":1"));
    Map<String, Object> state = Snapshot.of(t.session);
    assertEquals(Labels.PERSISTENCE, ((Map<?, ?>) state.get("labels")).get("persistence"));
    Map<String, Object> export = EvidenceExport.of(t.session);
    assertEquals("in_memory_session", export.get("persistence"));
    assertFalse(Json.write(Json.SNAKE, export).contains("\"durable\""));
    @SuppressWarnings("unchecked") List<TimelineEvent> events = (List<TimelineEvent>) export.get("timeline");
    for (TimelineEvent e : events) assertNotNull(e.seq(), "exported events all have a seq");
  }

  @Test
  void idempotencyKeyDeduplicatesRetries() {
    TestSession t = new TestSession();
    String body = Json.write(Json.SNAKE, Map.of("type", "start_analysis", "persona", "requester",
        "args", Map.of("finding_id", t.findingId().toString())));
    Commands.Result r1 = t.commands.handle(t.session, body, "key-1");
    Commands.Result r2 = t.commands.handle(t.session, body, "key-1");
    assertEquals(202, r1.status());
    assertSame(r1, r2);
    assertEquals(FindingState.PLAN_READY, t.state());
  }
}
