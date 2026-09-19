package adr.workflow;

import adr.app.Session;
import adr.broker.RunContext;
import adr.domain.ActorType;
import adr.domain.Evidence;
import adr.domain.Labels;
import adr.domain.TimelineEvent;
import adr.stores.Corpus;
import adr.stores.Findings;
import adr.stores.Seed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic retrieval between analysis phases A and B. The mandatory set is every outcome of the same
 * finding type; each becomes evidence under the reserved alias retrieval:<doc_id>. Text is copied, never
 * interpreted: the poisoned document reaches the page as text only.
 */
public final class Retrieval {
  private Retrieval() {}

  public static List<Seed.CorpusDoc> run(RunContext ctx) {
    Session s = ctx.session;
    Findings.Entry f = s.stores().findings().get(ctx.findingId);
    List<Seed.CorpusDoc> mandatory = s.stores().corpus().mandatory(f.finding.findingType());
    List<Map<String, Object>> items = new ArrayList<>();
    for (Seed.CorpusDoc d : mandatory) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("doc_id", d.id());
      data.put("kind", d.kind());
      data.put("title", d.title());
      data.put("finding_type", d.findingType());
      data.put("asset_family", d.assetFamily());
      data.put("disposition", d.disposition());
      data.put("failed_scenario", d.failedScenario());
      data.put("required_privileges", d.requiredPrivileges().stream()
          .map(p -> Map.<String, Object>of("privilege", p.privilege(), "kind", p.kind(), "object", p.object())).toList());
      data.put("body", d.body());
      data.put("mandatory", true);
      Evidence ev = s.stores().evidence().add("retrieval", Labels.CORPUS, data, ctx.runId);
      ctx.bind("retrieval:" + d.id(), ev.id());
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("doc_id", d.id());
      item.put("title", d.title());
      item.put("disposition", d.disposition());
      item.put("failed_scenario", d.failedScenario());
      item.put("evidence_id", ev.id());
      items.add(item);
    }
    List<Map<String, Object>> hits = new ArrayList<>();
    for (Corpus.Hit h : s.stores().corpus().search(List.of("owner", "membership", "right-size"), f.finding.findingType(), "orders", 5)) {
      hits.add(Map.of("doc_id", h.doc().id(), "title", h.doc().title(), "score", h.score()));
    }
    ctx.mandatoryDocs = mandatory;
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("mandatory", items);
    p.put("search_hits", hits);
    p.put("corpus_size", s.stores().corpus().all().size());
    long failed = mandatory.stream().filter(Seed.CorpusDoc::isFailedOutcome).count();
    s.timeline().append(TimelineEvent.of(ctx.findingId, ActorType.gate, "retrieval", "analyse", "retrieval.completed",
        "retrieval.mandatory_outcomes: " + mandatory.size() + " outcomes of this finding type, " + failed + " rolled back. The agent must address each.",
        p, Labels.CORPUS));
    return mandatory;
  }
}
