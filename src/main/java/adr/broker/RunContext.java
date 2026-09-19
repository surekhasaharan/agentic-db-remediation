package adr.broker;

import adr.app.Session;
import adr.domain.AgentId;
import adr.stores.Seed;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One agent run: which agent and phase, for which finding and operation, and the evidence it produced.
 * Aliases exist only for recordings; guards work on evidence ids. Analysis phases A and B share one context
 * so that the reserved retrieval aliases and the citation guard span the whole stage.
 */
public final class RunContext {
  public final String runId = UUID.randomUUID().toString().substring(0, 8);
  public final Session session;
  public final UUID findingId;
  public final AgentId agent;
  public volatile String phase;
  public volatile UUID operationId;
  public final Map<String, String> aliases = new ConcurrentHashMap<>();
  public final Set<String> producedEvidence = ConcurrentHashMap.newKeySet();
  public final List<ToolResult> results = new CopyOnWriteArrayList<>();
  public volatile List<Seed.CorpusDoc> mandatoryDocs = List.of();
  public volatile int step = 0;

  public RunContext(Session session, UUID findingId, AgentId agent, String phase) {
    this.session = session;
    this.findingId = findingId;
    this.agent = agent;
    this.phase = phase;
  }

  public void bind(String alias, String evidenceId) {
    if (alias != null) aliases.put(alias, evidenceId);
    producedEvidence.add(evidenceId);
  }

  public String stage() {
    return switch (agent) {
      case analysis -> "analyse";
      case remediation -> "remediate";
      case verification -> "verify";
      case supervision -> "supervise";
    };
  }
}
