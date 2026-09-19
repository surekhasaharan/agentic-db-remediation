package adr.workflow;

import adr.TestSession;
import adr.domain.TimelineEvent;
import adr.stores.Seed;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BurstTest {

  /** I12: queue never above 256, in flight never above 8, 5,000 accounted for, heap flat across three bursts. */
  @Test
  void burstIsBounded() throws Exception {
    Seed.FleetSeed fleet = Seed.load().fleet;
    long[] heaps = new long[3];
    for (int i = 0; i < 3; i++) {
      List<Map<String, Object>> gauges = new ArrayList<>();
      BurstPipeline.Stats st = BurstPipeline.run(fleet, 42 + i, 5000, false, gauges::add);
      assertEquals(5000, st.processed(), "all findings accounted for");
      assertTrue(st.maxQueue() <= BurstPipeline.QUEUE_CAPACITY, "queue peaked at " + st.maxQueue());
      assertTrue(st.maxInFlight() <= BurstPipeline.WORKERS, "in flight peaked at " + st.maxInFlight());
      assertTrue(st.unique() <= fleet.assets().size() * fleet.controls().size());
      assertTrue(st.unique() > 100);
      assertEquals(Math.min(20, st.unique()), st.worklist().size());
      for (Map<String, Object> g : gauges) {
        assertTrue((Integer) g.get("queue") <= BurstPipeline.QUEUE_CAPACITY);
        assertTrue((Integer) g.get("in_flight") <= BurstPipeline.WORKERS);
        assertEquals(BurstPipeline.QUEUE_CAPACITY, g.get("queue_capacity"));
        assertEquals(BurstPipeline.WORKERS, g.get("workers"));
        assertEquals(2, g.get("agent_slots"));
      }
      assertEquals(true, gauges.get(gauges.size() - 1).get("done"));
      System.gc();
      Thread.sleep(50);
      heaps[i] = BurstPipeline.heapUsedMb();
    }
    long base = Math.max(heaps[0], 8);
    assertTrue(heaps[2] <= base * 1.10 + 2, "heap after three bursts " + heaps[2] + " MB vs " + heaps[0] + " MB after the first");
  }

  @Test
  void burstCommandRunsOncePerSessionAndIsTriageOnly() {
    TestSession t = new TestSession();
    t.session.burstRunning().set(true);
    assertEquals("BURST_RUNNING", t.send("start_burst", "requester", t.args("count", 100)).code());
    t.session.burstRunning().set(false);
    assertEquals(202, t.send("start_burst", "requester", t.args("count", 300)).status());
    assertFalse(t.session.burstRunning().get(), "the direct executor ran it to completion");
    List<TimelineEvent> done = t.events("burst.completed");
    assertEquals(1, done.size());
    assertEquals(300L, ((Number) done.get(0).payload().get("processed")).longValue());
    assertTrue(t.events("burst.started").get(0).summary().contains("queue 256, workers 8, agent slots 2"));
    // Burst findings never become lifecycle findings: the session still has exactly one.
    assertEquals(1, t.session.stores().findings().all().size());
    assertEquals(400, t.send("start_burst", "requester", t.args("count", 0)).status());
    assertEquals(400, t.send("start_burst", "requester", t.args("count", 5001)).status());
  }
}
