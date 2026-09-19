package adr.app;

import adr.workflow.Workflow;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main {
  public static void main(String[] args) {
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

    StartupChecks.Result boot;
    try {
      boot = StartupChecks.run();
    } catch (RuntimeException e) {
      System.err.println("start-up check failed: " + e.getMessage());
      System.exit(2);
      return;
    }

    ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor();
    Workflow workflow = new Workflow(runs, boot.policy(), boot.model());
    SessionRegistry registry = new SessionRegistry((id, epoch, reg) -> Session.fromSeed(id, epoch, reg, boot.seed()));
    Commands commands = new Commands(registry, workflow);

    ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "housekeeping");
      t.setDaemon(true);
      return t;
    });
    housekeeping.scheduleAtFixedRate(() -> registry.sweep(Instant.now()), 1, 1, TimeUnit.MINUTES);
    adr.workflow.DriftPoller poller = new adr.workflow.DriftPoller(registry::live, workflow);
    ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "drift-poller");
      t.setDaemon(true);
      return t;
    });
    polling.scheduleAtFixedRate(poller::tick, adr.workflow.DriftPoller.INTERVAL_MS, adr.workflow.DriftPoller.INTERVAL_MS, TimeUnit.MILLISECONDS);

    new Api(registry, commands, boot.health()).build().start(port);
    System.out.println("adr-demo listening on http://localhost:" + port + " (recordings " + boot.recordingsHash().substring(0, 12) + ")");
  }
}
