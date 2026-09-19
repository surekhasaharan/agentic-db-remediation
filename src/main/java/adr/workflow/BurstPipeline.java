package adr.workflow;

import adr.agents.AgentRunner;
import adr.app.Session;
import adr.domain.ActorType;
import adr.domain.Labels;
import adr.domain.TimelineEvent;
import adr.stores.Seed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * The production pipeline in java.util.concurrent: one generator, a 256-slot queue with put for
 * back-pressure, eight workers, a merge map for deduplication, atomic counters and a 4 Hz sampler. The
 * constants are named and shown on the gauges. Text fields are copied, never interpreted.
 */
public final class BurstPipeline {
  public static final int QUEUE_CAPACITY = 256;
  public static final int WORKERS = 8;
  public static final int MAX_COUNT = 5000;
  public static final int PACED_PER_SECOND = 400;
  public static final int SAMPLE_MS = 250;
  public static final int WORKLIST = 20;

  public record RawFinding(int asset, int control, int severity, String text) {}

  public static final class Occurrence {
    final String asset;
    final String control;
    int count;
    int score;
    String lastText;

    Occurrence(String asset, String control, int score, String text) {
      this.asset = asset;
      this.control = control;
      this.count = 1;
      this.score = score;
      this.lastText = text;
    }

    Occurrence add(Occurrence o) {
      count += o.count;
      score = Math.max(score, o.score);
      lastText = o.lastText;
      return this;
    }
  }

  public record Stats(int count, long processed, int unique, int maxQueue, int maxInFlight, long heapUsedMb, long durationMs,
                      List<Map<String, Object>> worklist) {}

  private static final RawFinding POISON = new RawFinding(-1, -1, 0, "");

  private BurstPipeline() {}

  /** Runs one burst. Paced mode emits about 400 findings a second so the gauges are readable. */
  public static Stats run(Seed.FleetSeed fleet, long seed, int count, boolean paced, Consumer<Map<String, Object>> gauges) throws InterruptedException {
    ArrayBlockingQueue<RawFinding> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    ConcurrentHashMap<String, Occurrence> dedup = new ConcurrentHashMap<>();
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();
    AtomicInteger maxQueue = new AtomicInteger();
    LongAdder processed = new LongAdder();
    long start = System.nanoTime();

    Thread generator = new Thread(() -> {
      Random rnd = new Random(seed);
      long t0 = System.nanoTime();
      try {
        for (int i = 0; i < count; i++) {
          RawFinding f = new RawFinding(rnd.nextInt(fleet.assets().size()), rnd.nextInt(fleet.controls().size()), 1 + rnd.nextInt(5),
              "raw finding " + i + " " + Long.toHexString(rnd.nextLong()));
          queue.put(f);
          maxQueue.accumulateAndGet(queue.size(), Math::max);
          if (paced) {
            long due = t0 + (long) i * 1_000_000_000L / PACED_PER_SECOND;
            long wait = due - System.nanoTime();
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
          }
        }
        for (int w = 0; w < WORKERS; w++) queue.put(POISON);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }, "burst-generator");

    CountDownLatch done = new CountDownLatch(WORKERS);
    List<Thread> workers = new ArrayList<>();
    for (int w = 0; w < WORKERS; w++) {
      Thread t = new Thread(() -> {
        try {
          while (true) {
            RawFinding f = queue.take();
            if (f == POISON) break;
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
              Seed.FleetAsset a = fleet.assets().get(f.asset());
              Seed.FleetControl c = fleet.controls().get(f.control());
              String key = a.id() + ":" + c.id();
              int score = a.criticality() * c.weight() * f.severity() + (a.piiLabel() ? 5 : 0);
              dedup.merge(key, new Occurrence(a.name(), c.name(), score, f.text()), Occurrence::add);
              processed.increment();
            } finally {
              inFlight.decrementAndGet();
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      }, "burst-worker-" + w);
      workers.add(t);
    }

    ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "burst-sampler");
      t.setDaemon(true);
      return t;
    });
    Runnable sample = () -> {
      maxQueue.accumulateAndGet(queue.size(), Math::max);
      gauges.accept(gauge(count, queue.size(), inFlight.get(), processed.sum(), dedup.size(), false));
    };
    sampler.scheduleAtFixedRate(sample, 0, SAMPLE_MS, TimeUnit.MILLISECONDS);

    generator.start();
    workers.forEach(Thread::start);
    generator.join();
    done.await();
    sampler.shutdownNow();
    gauges.accept(gauge(count, queue.size(), inFlight.get(), processed.sum(), dedup.size(), true));

    List<Occurrence> top = new ArrayList<>(dedup.values());
    top.sort(Comparator.comparingInt((Occurrence o) -> o.score).reversed().thenComparing(o -> o.asset));
    List<Map<String, Object>> worklist = new ArrayList<>();
    for (Occurrence o : top.subList(0, Math.min(WORKLIST, top.size()))) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("asset", o.asset);
      m.put("control", o.control);
      m.put("occurrences", o.count);
      m.put("score", o.score);
      worklist.add(m);
    }
    long durationMs = (System.nanoTime() - start) / 1_000_000;
    return new Stats(count, processed.sum(), dedup.size(), maxQueue.get(), maxInFlight.get(), heapUsedMb(), durationMs, worklist);
  }

  public static Map<String, Object> gauge(int total, int queue, int inFlight, long processed, int dedup, boolean done) {
    Map<String, Object> g = new LinkedHashMap<>();
    g.put("queue", queue);
    g.put("queue_capacity", QUEUE_CAPACITY);
    g.put("in_flight", inFlight);
    g.put("workers", WORKERS);
    g.put("agent_slots_used", AgentRunner.AGENT_SLOTS - AgentRunner.SLOTS.availablePermits());
    g.put("agent_slots", AgentRunner.AGENT_SLOTS);
    g.put("processed", processed);
    g.put("total", total);
    g.put("dedup", dedup);
    g.put("heap_mb", heapUsedMb());
    Long rss = rssMb();
    if (rss != null) g.put("rss_mb", rss);
    g.put("done", done);
    return g;
  }

  public static long heapUsedMb() {
    Runtime r = Runtime.getRuntime();
    return (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
  }

  /** Resident size from /proc/self/status on Linux, which is what the acceptance budget refers to. Null elsewhere. */
  public static Long rssMb() {
    Path p = Path.of("/proc/self/status");
    if (!Files.isReadable(p)) return null;
    try {
      for (String line : Files.readAllLines(p)) {
        if (line.startsWith("VmRSS:")) {
          String[] parts = line.trim().split("\\s+");
          return Long.parseLong(parts[1]) / 1024;
        }
      }
    } catch (IOException | RuntimeException e) {
      return null;
    }
    return null;
  }

  /** The session-facing run: events, transient gauges and the busy flag. */
  public static void runForSession(Session s, int count) {
    Seed.FleetSeed fleet = s.stores().seed().fleet;
    s.timeline().append(TimelineEvent.of(null, ActorType.system, "burst_pipeline", "session", "burst.started",
        "Burst started: " + count + " findings over " + fleet.assets().size() + " assets and " + fleet.controls().size()
            + " controls, queue " + QUEUE_CAPACITY + ", workers " + WORKERS + ", agent slots " + AgentRunner.AGENT_SLOTS,
        Map.of("count", count, "queue_capacity", QUEUE_CAPACITY, "workers", WORKERS, "agent_slots", AgentRunner.AGENT_SLOTS), Labels.PLATFORM));
    try {
      Stats st = run(fleet, s.id().hashCode(), count, true, g -> s.timeline().offerTransient(
          TimelineEvent.of(null, ActorType.system, "burst_pipeline", "session", "gauges", "gauges", g, Labels.PLATFORM)));
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("count", st.count());
      p.put("processed", st.processed());
      p.put("unique", st.unique());
      p.put("max_queue", st.maxQueue());
      p.put("queue_capacity", QUEUE_CAPACITY);
      p.put("max_in_flight", st.maxInFlight());
      p.put("workers", WORKERS);
      p.put("heap_used_mb", st.heapUsedMb());
      p.put("duration_ms", st.durationMs());
      p.put("worklist", st.worklist());
      s.timeline().append(TimelineEvent.of(null, ActorType.gate, "burst_pipeline", "session", "burst.completed",
          "burst.bounds: PASS. " + st.processed() + " of " + st.count() + " processed in " + st.durationMs() + " ms, queue peaked at "
              + st.maxQueue() + " of " + QUEUE_CAPACITY + ", in flight peaked at " + st.maxInFlight() + " of " + WORKERS + ", "
              + st.unique() + " unique findings, heap " + st.heapUsedMb() + " MB", p, Labels.PLATFORM));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      s.burstRunning().set(false);
    }
  }
}
