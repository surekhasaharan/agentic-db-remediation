package adr.stores;

import adr.domain.ActorType;
import adr.domain.Labels;
import adr.domain.TimelineEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TimelineTest {

  static TimelineEvent ev(String kind) {
    return TimelineEvent.of(null, ActorType.system, "test", "session", kind, kind, Map.of(), Labels.PLATFORM);
  }

  /** I19: a subscriber joining at any moment with any after receives after+1 .. end, once, in order. */
  @Test
  void subscribeDuringAppendsSeesEverySeqOnce() throws Exception {
    Timeline t = new Timeline("e1");
    int total = 4000, subs = 50;
    CountDownLatch start = new CountDownLatch(1);
    Thread appender = new Thread(() -> {
      try { start.await(); } catch (InterruptedException e) { return; }
      for (int i = 0; i < total; i++) t.append(ev("x"));
    });
    List<Thread> readers = new ArrayList<>();
    List<List<Long>> seen = new ArrayList<>();
    Random rnd = new Random(1);
    for (int s = 0; s < subs; s++) {
      List<Long> got = new ArrayList<>();
      seen.add(got);
      long after = rnd.nextInt(total);
      int delay = rnd.nextInt(3);
      readers.add(new Thread(() -> {
        try { start.await(); Thread.sleep(delay); } catch (InterruptedException e) { return; }
        long last = after;
        Timeline.Subscription sub = t.subscribe("e1", after);
        for (TimelineEvent e : sub.backlog()) { got.add(e.seq()); last = e.seq(); }
        while (last < total) {
          // The page's protocol: a gap or an overflow means reconnect with the last seq applied.
          if (sub.subscriber().overflowed()) {
            t.unsubscribe(sub.subscriber());
            sub = t.subscribe("e1", last);
            for (TimelineEvent e : sub.backlog()) { got.add(e.seq()); last = e.seq(); }
            continue;
          }
          TimelineEvent e;
          try { e = sub.subscriber().live().poll(5, TimeUnit.SECONDS); } catch (InterruptedException ie) { return; }
          if (e == null) { if (sub.subscriber().overflowed()) continue; fail("starved at " + last); }
          if (e.seq() <= last) continue;            // already applied
          if (e.seq() != last + 1) {                 // gap: the queue dropped something
            t.unsubscribe(sub.subscriber());
            sub = t.subscribe("e1", last);
            for (TimelineEvent x : sub.backlog()) { got.add(x.seq()); last = x.seq(); }
            continue;
          }
          got.add(e.seq());
          last = e.seq();
        }
        got.add(0, after);
      }));
    }
    appender.start();
    readers.forEach(Thread::start);
    start.countDown();
    appender.join();
    for (Thread r : readers) r.join(10_000);
    for (List<Long> got : seen) {
      long after = got.get(0);
      List<Long> seqs = got.subList(1, got.size());
      assertEquals(total - after, seqs.size(), "count for after=" + after);
      for (int i = 0; i < seqs.size(); i++) assertEquals(after + 1 + i, seqs.get(i), "gap or duplicate at " + i);
    }
  }

  /** I19: a blocked sender never slows appendAll; it overflows, is dropped, and a reconnect replays the rest. */
  @Test
  void slowSubscriberDoesNotBlockAppend() {
    Timeline t = new Timeline("e1");
    Timeline.Subscription sub = t.subscribe("e1", 0);
    long t0 = System.nanoTime();
    for (int i = 0; i < Timeline.LIVE_QUEUE + 500; i++) t.append(ev("x"));
    long ms = (System.nanoTime() - t0) / 1_000_000;
    assertTrue(ms < 2000, "appendAll took " + ms + "ms");
    assertTrue(sub.subscriber().overflowed());
    // The sender drains what it has, closes, and the page reconnects with its last seq.
    long last = 0;
    TimelineEvent e;
    while ((e = sub.subscriber().live().poll()) != null) last = e.seq();
    assertEquals(Timeline.LIVE_QUEUE, last);
    t.unsubscribe(sub.subscriber());
    Timeline.Subscription again = t.subscribe("e1", last);
    assertEquals(500, again.backlog().size());
    assertEquals(last + 1, again.backlog().get(0).seq());
  }

  /** Fourth review round: the backlog never enters the live queue. */
  @Test
  void largeReplayDoesNotOverflowLiveQueue() {
    Timeline t = new Timeline("e1");
    for (int i = 0; i < 4000; i++) t.append(ev("x"));
    Timeline.Subscription sub = t.subscribe("e1", 0);
    assertEquals(4000, sub.backlog().size());
    for (int i = 0; i < 4000; i++) assertEquals(i + 1, sub.backlog().get(i).seq());
    assertFalse(sub.subscriber().overflowed());
    assertEquals(0, sub.subscriber().live().size());
    t.append(ev("y"));
    assertEquals(1, sub.subscriber().live().size());
    assertEquals(4001, sub.subscriber().live().peek().seq());
  }

  @Test
  void replayThenLiveIsGapless() throws Exception {
    Timeline t = new Timeline("e1");
    for (int i = 0; i < 3000; i++) t.append(ev("x"));
    Timeline.Subscription sub = t.subscribe("e1", 0);
    Thread appender = new Thread(() -> { for (int i = 0; i < 200; i++) t.append(ev("live")); });
    appender.start();
    List<Long> seqs = new ArrayList<>();
    for (TimelineEvent e : sub.backlog()) seqs.add(e.seq());
    appender.join();
    TimelineEvent e;
    while ((e = sub.subscriber().live().poll()) != null) seqs.add(e.seq());
    assertEquals(3200, seqs.size());
    for (int i = 0; i < seqs.size(); i++) assertEquals(i + 1, seqs.get(i));
  }

  /** I22 at the timeline level: closeWith delivers the terminal event last, then refuses everything. */
  @Test
  void resetHandsSubscribersToNewTimeline() {
    Timeline old = new Timeline("old");
    old.append(ev("x"));
    Timeline.Subscription sub = old.subscribe("old", 0);
    old.closeWith(ev("session.reset"));
    TimelineEvent last = null, e;
    while ((e = sub.subscriber().live().poll()) != null) last = e;
    assertNotNull(last);
    assertEquals("session.reset", last.kind());
    assertTrue(old.isClosed());
    assertThrows(IllegalStateException.class, () -> old.append(ev("late")));
    assertTrue(old.subscribe("old", 0).stale());
    Timeline fresh = new Timeline("new");
    assertTrue(fresh.subscribe("old", 0).stale(), "old epoch on the new timeline is stale");
    Timeline.Subscription s2 = fresh.subscribe("new", 0);
    assertFalse(s2.stale());
    fresh.append(ev("first"));
    assertEquals(1, s2.subscriber().live().peek().seq());
  }

  @Test
  void transientEventsHaveNoSeqAndAreNeverStored() {
    Timeline t = new Timeline("e1");
    Timeline.Subscription sub = t.subscribe("e1", 0);
    t.offerTransient(ev("gauges"));
    TimelineEvent g = sub.subscriber().live().poll();
    assertNotNull(g);
    assertNull(g.seq());
    assertEquals(0, t.size());
    assertEquals(0, t.subscribe("e1", 0).backlog().size());
  }
}
