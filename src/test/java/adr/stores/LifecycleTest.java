package adr.stores;

import adr.TestSession;
import adr.app.Commands;
import adr.domain.FindingState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleTest {

  static final List<String> LIFECYCLE_COMMANDS = List.of("start_analysis", "request_approval", "approve", "reject", "trigger_drift");

  static Map<String, Object> argsFor(TestSession t, String type, String findingId) {
    return switch (type) {
      case "start_analysis", "trigger_drift" -> t.args("finding_id", findingId);
      default -> t.args("finding_id", findingId, "plan_hash", "0".repeat(64));
    };
  }

  /** I14: each command in each wrong state is refused with 409 and no state change. */
  @Test
  void illegalCommandsAreRefused() {
    for (FindingState state : FindingState.values()) {
      for (String cmd : LIFECYCLE_COMMANDS) {
        Commands.Type type = Commands.Type.valueOf(cmd);
        if (Commands.LEGAL_IN.get(type).contains(state)) continue;
        TestSession t = new TestSession();
        t.driveTo(state);
        assertEquals(state, t.state());
        long seqBefore = t.session.timeline().lastSeq();
        Commands.Result r = t.send(cmd, cmd.equals("approve") ? "dba" : "requester", argsFor(t, cmd, t.findingId().toString()));
        assertEquals(409, r.status(), cmd + " in " + state);
        assertEquals("ILLEGAL_STATE", r.code(), cmd + " in " + state);
        assertEquals(state, t.state(), "state changed by " + cmd + " in " + state);
        assertEquals(seqBefore + 1, t.session.timeline().lastSeq(), "one gate refusal event expected");
        assertEquals("gate.checked", t.session.timeline().read(seqBefore).get(0).kind());
      }
    }
  }

  @Test
  void lifecycleCommandsRequireFindingId() {
    TestSession other = new TestSession();
    for (String cmd : LIFECYCLE_COMMANDS) {
      TestSession t = new TestSession();
      // absent
      Commands.Result r = t.send(cmd, "requester", t.args("plan_hash", "0".repeat(64)));
      assertEquals(400, r.status(), cmd + " absent");
      assertEquals("args.finding_id", ((Map<?, ?>) r.body().get("error")).get("field"));
      // malformed
      r = t.send(cmd, "requester", argsFor(t, cmd, "not-a-uuid"));
      assertEquals(400, r.status(), cmd + " malformed");
      assertEquals("args.finding_id", ((Map<?, ?>) r.body().get("error")).get("field"));
      // another session's finding: the seeded id is shared, so use a fresh random one and a foreign child id
      String foreign = UUID.randomUUID().toString();
      r = t.send(cmd, "requester", argsFor(t, cmd, foreign));
      assertEquals(400, r.status(), cmd + " foreign");
      assertEquals("args.finding_id", ((Map<?, ?>) r.body().get("error")).get("field"));
      assertEquals(FindingState.OPEN, t.state());
    }
    assertEquals(FindingState.OPEN, other.state());
  }

  /** I21: casState is not public, and a source scan finds its only call site in Lifecycle. */
  @Test
  void onlyLifecycleMovesFindings() throws Exception {
    Method cas = Findings.class.getDeclaredMethod("casState", UUID.class, FindingState.class, FindingState.class);
    assertFalse(Modifier.isPublic(cas.getModifiers()), "casState must not be public");
    assertFalse(Modifier.isProtected(cas.getModifiers()));
    Path root = Path.of(System.getProperty("basedir", "."), "src", "main", "java");
    assertTrue(Files.isDirectory(root), "source root not found at " + root.toAbsolutePath());
    try (Stream<Path> files = Files.walk(root)) {
      List<Path> callers = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
        try {
          String src = Files.readString(p);
          String name = p.getFileName().toString();
          if (name.equals("Findings.java")) return src.indexOf("casState(") != src.lastIndexOf("casState("); // declaration only
          return src.contains("casState(");
        } catch (Exception e) { throw new RuntimeException(e); }
      }).toList();
      assertEquals(1, callers.size(), "call sites: " + callers);
      assertEquals("Lifecycle.java", callers.get(0).getFileName().toString());
      assertEquals("stores", callers.get(0).getParent().getFileName().toString());
    }
  }

  @Test
  void legalTableMatchesTheDesign() {
    assertTrue(Lifecycle.legal(FindingState.OPEN, FindingState.ANALYSING));
    assertTrue(Lifecycle.legal(FindingState.REMEDIATING, FindingState.ANALYSING));
    assertTrue(Lifecycle.legal(FindingState.DRIFT_DETECTED, FindingState.CLOSED));
    assertFalse(Lifecycle.legal(FindingState.OPEN, FindingState.PLAN_READY));
    assertFalse(Lifecycle.legal(FindingState.CLOSED, FindingState.OPEN));
    assertFalse(Lifecycle.legal(FindingState.REOPENED, FindingState.ANALYSING));
    assertThrows(IllegalArgumentException.class, () -> {
      TestSession t = new TestSession();
      t.force(t.findingId(), FindingState.OPEN, FindingState.CLOSED);
    });
  }
}
