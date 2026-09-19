package adr.stores;

import adr.domain.TimelineEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Append-only session timeline. Two properties hold: a subscriber can never miss an event, because the
 * backlog copy and the registration share one critical section with appendAll; and no I/O happens under
 * the lock, because appendAll only enqueues and every socket write happens on the subscriber's thread.
 */
public final class Timeline {
  public static final int LIVE_QUEUE = 1024;
  public static final int CAP = 5000;

  private final Object lock = new Object();
  private final String epoch;
  private final List<TimelineEvent> log = new ArrayList<>();
  private final List<Subscriber> subscribers = new ArrayList<>();
  private long lastSeq = 0;
  private boolean closed = false;

  public Timeline(String epoch) { this.epoch = epoch; }

  public static final class Subscriber {
    final LinkedBlockingQueue<TimelineEvent> live = new LinkedBlockingQueue<>(LIVE_QUEUE);
    volatile boolean overflowed = false;

    public LinkedBlockingQueue<TimelineEvent> live() { return live; }
    public boolean overflowed() { return overflowed; }
  }

  public record Subscription(boolean stale, List<TimelineEvent> backlog, Subscriber subscriber) {
    static Subscription staleSubscription() { return new Subscription(true, List.of(), null); }
  }

  public Subscription subscribe(String epoch, long after) {
    synchronized (lock) {
      if (closed || !this.epoch.equals(epoch)) return Subscription.staleSubscription();
      List<TimelineEvent> backlog = new ArrayList<>();
      for (TimelineEvent e : log) if (e.seq() > after) backlog.add(e);
      Subscriber sub = new Subscriber();
      subscribers.add(sub);
      return new Subscription(false, List.copyOf(backlog), sub);
    }
  }

  public void unsubscribe(Subscriber s) {
    synchronized (lock) { subscribers.remove(s); }
  }

  public TimelineEvent append(TimelineEvent e) {
    return appendAll(List.of(e)).get(0);
  }

  /** Assigns consecutive seq numbers under one acquisition, so a transition and its effects are one group. */
  public List<TimelineEvent> appendAll(List<TimelineEvent> events) {
    synchronized (lock) {
      if (closed) throw new IllegalStateException("timeline closed");
      return appendLocked(events);
    }
  }

  private List<TimelineEvent> appendLocked(List<TimelineEvent> events) {
    List<TimelineEvent> out = new ArrayList<>(events.size());
    for (TimelineEvent e : events) {
      TimelineEvent numbered = e.withSeq(++lastSeq);
      log.add(numbered);
      out.add(numbered);
    }
    for (Subscriber s : subscribers) {
      for (TimelineEvent e : out) if (!s.live.offer(e)) s.overflowed = true;
    }
    return out;
  }

  /** Transient events have no seq, are never stored, and are skipped when a queue is more than half full. */
  public void offerTransient(TimelineEvent e) {
    synchronized (lock) {
      if (closed) return;
      for (Subscriber s : subscribers) {
        if (s.live.size() <= LIVE_QUEUE / 2) s.live.offer(e);
      }
    }
  }

  /** Writes the final event, then closes. No later append or subscribe succeeds. */
  public void closeWith(TimelineEvent terminal) {
    synchronized (lock) {
      if (closed) return;
      appendLocked(List.of(terminal));
      closed = true;
    }
  }

  public List<TimelineEvent> read(long after) {
    synchronized (lock) {
      List<TimelineEvent> out = new ArrayList<>();
      for (TimelineEvent e : log) if (e.seq() > after) out.add(e);
      return out;
    }
  }

  public List<TimelineEvent> all() { return read(0); }
  public String epoch() { return epoch; }
  public long lastSeq() { synchronized (lock) { return lastSeq; } }
  public int size() { synchronized (lock) { return log.size(); } }
  public boolean isClosed() { synchronized (lock) { return closed; } }
  public int subscriberCount() { synchronized (lock) { return subscribers.size(); } }
  public boolean atCap() { synchronized (lock) { return log.size() >= CAP; } }
}
