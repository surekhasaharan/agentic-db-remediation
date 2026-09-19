package adr.app;

import adr.domain.InvalidInput;
import adr.domain.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import io.javalin.http.staticfiles.Location;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Six endpoints, one event envelope, one page. */
public final class Api {
  public static final String COOKIE = "adr_session";

  private final SessionRegistry registry;
  private final Commands commands;
  private final Map<String, Object> health;
  private final String indexHtml;

  public Api(SessionRegistry registry, Commands commands, Map<String, Object> health) {
    this.registry = registry;
    this.commands = commands;
    this.health = health;
    this.indexHtml = resource("/public/index.html");
  }

  public Javalin build() {
    Javalin app = Javalin.create(cfg -> {
      cfg.showJavalinBanner = false;
      cfg.useVirtualThreads = true;
      cfg.http.defaultContentType = "application/json";
      cfg.staticFiles.add(s -> {
        s.hostedPath = "/static";
        s.directory = "/public/static";
        s.location = Location.CLASSPATH;
      });
    });

    app.get("/", ctx -> {
      sessionFor(ctx);
      ctx.html(indexHtml);
    });
    app.get("/healthz", ctx -> ctx.result(Json.write(Json.SNAKE, health)));
    app.get("/api/state", ctx -> {
      Session s = sessionFor(ctx);
      ctx.result(Json.write(Json.SNAKE, Snapshot.of(s)));
    });
    app.post("/api/commands", ctx -> {
      Session s = sessionFor(ctx);
      Commands.Result r = commands.handle(s, ctx.body(), ctx.header("Idempotency-Key"));
      ctx.status(r.status()).result(Json.write(Json.SNAKE, r.body()));
    });
    // The stream commits its headers as soon as it opens, so query validation happens before it.
    app.before("/api/events", ctx -> {
      try {
        Sse.parseAfter(ctx.queryParam("after"));
      } catch (InvalidInput e) {
        ctx.status(400).result(Json.write(Json.SNAKE, Map.of("error",
            Map.of("code", "INVALID_INPUT", "field", e.field(), "problem", e.problem()))));
        ctx.skipRemainingHandlers();
      }
    });
    app.sse("/api/events", client -> Sse.handle(client, registry));
    app.get("/api/evidence.json", ctx -> {
      Session s = sessionFor(ctx);
      ctx.header("Content-Disposition", "attachment; filename=\"evidence.json\"");
      ctx.result(Json.write(Json.SNAKE, EvidenceExport.of(s)));
    });
    return app;
  }

  Session sessionFor(Context ctx) {
    Session s = registry.get(ctx.cookie(COOKIE));
    if (s == null) {
      s = registry.mint();
      Cookie c = new Cookie(COOKIE, s.id(), "/", -1, false, 0, true, "", "", SameSite.STRICT);
      ctx.cookie(c);
    }
    return s;
  }

  static String resource(String path) {
    try (InputStream in = Api.class.getResourceAsStream(path)) {
      if (in == null) throw new IllegalStateException("missing resource " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
