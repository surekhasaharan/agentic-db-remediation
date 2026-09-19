package adr.app;

import adr.TestSession;
import adr.domain.Json;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The one HTTP-level test: both health routes serve the same document. */
class ApiTest {

  @Test
  void healthRoutesReturnTheSameDocument() throws Exception {
    TestSession t = new TestSession();
    Map<String, Object> health = Map.of("status", "ok", "recordings_hash", "abc123", "golden_flow", Map.of("no_faults", "ok"));
    Javalin app = new Api(t.registry, t.commands, health).build().start(0);
    try {
      HttpClient client = HttpClient.newHttpClient();
      String base = "http://localhost:" + app.port();
      HttpResponse<String> a = client.send(HttpRequest.newBuilder(URI.create(base + "/api/health")).build(), HttpResponse.BodyHandlers.ofString());
      HttpResponse<String> b = client.send(HttpRequest.newBuilder(URI.create(base + "/healthz")).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, a.statusCode());
      assertEquals(200, b.statusCode());
      assertEquals(a.body(), b.body(), "both routes serve the same document");
      assertEquals(Json.write(Json.SNAKE, health), a.body());
      assertTrue(a.headers().firstValue("content-type").orElse("").startsWith("application/json"));
    } finally {
      app.stop();
    }
  }
}
