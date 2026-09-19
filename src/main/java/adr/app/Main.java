package adr.app;

import adr.stores.Seed;
import adr.workflow.Workflow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main {
  public static void main(String[] args) {
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

    // Start-up order: load and validate seeds, then (later phases) recordings and the golden flow, then bind.
    Seed seed = Seed.load();

    ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor();
    Workflow workflow = new Workflow(runs);
    SessionRegistry registry = new SessionRegistry((id, epoch, reg) -> Session.fromSeed(id, epoch, reg, seed));
    Commands commands = new Commands(registry, workflow);

    ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "housekeeping");
      t.setDaemon(true);
      return t;
    });
    housekeeping.scheduleAtFixedRate(() -> registry.sweep(Instant.now()), 1, 1, TimeUnit.MINUTES);

    Map<String, Object> health = new LinkedHashMap<>();
    health.put("status", "ok");
    new Api(registry, commands, health).build().start(port);
    System.out.println("adr-demo listening on http://localhost:" + port);
  }
}
