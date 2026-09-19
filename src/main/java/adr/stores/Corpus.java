package adr.stores;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keyword and metadata scoring over a small corpus. The shared documents are immutable; the session overlay
 * receives outcomes written back at close. Text is scored, never interpreted.
 */
public final class Corpus {
  public record Hit(Seed.CorpusDoc doc, int score) {}

  private final List<Seed.CorpusDoc> shared;
  private final List<Seed.CorpusDoc> overlay = new CopyOnWriteArrayList<>();

  public Corpus(Seed.CorpusSeed seed) { this.shared = seed.documents(); }

  public List<Seed.CorpusDoc> all() {
    List<Seed.CorpusDoc> out = new ArrayList<>(shared);
    out.addAll(overlay);
    return out;
  }

  public Seed.CorpusDoc get(String id) {
    return all().stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
  }

  public void addOutcome(Seed.CorpusDoc d) { overlay.add(d); }
  public List<Seed.CorpusDoc> overlay() { return List.copyOf(overlay); }

  /** The mandatory retrieval set: every outcome of the same finding type, whatever its disposition. */
  public List<Seed.CorpusDoc> mandatory(String findingType) {
    return all().stream().filter(d -> "outcome".equals(d.kind()) && d.findingType().equals(findingType)).toList();
  }

  public List<Hit> search(List<String> terms, String findingType, String assetFamily, int limit) {
    List<Hit> hits = new ArrayList<>();
    for (Seed.CorpusDoc d : all()) {
      int score = 0;
      String hay = (d.title() + " " + String.join(" ", d.tags()) + " " + d.body()).toLowerCase(Locale.ROOT);
      for (String t : terms) {
        String lt = t.toLowerCase(Locale.ROOT);
        if (d.tags().stream().anyMatch(x -> x.equalsIgnoreCase(lt))) score += 3;
        if (d.title().toLowerCase(Locale.ROOT).contains(lt)) score += 2;
        if (hay.contains(lt)) score += 1;
      }
      if (findingType != null && findingType.equals(d.findingType())) score += 5;
      if (assetFamily != null && assetFamily.equals(d.assetFamily())) score += 2;
      if (score > 0) hits.add(new Hit(d, score));
    }
    hits.sort((a, b) -> b.score() != a.score() ? Integer.compare(b.score(), a.score()) : a.doc().id().compareTo(b.doc().id()));
    return hits.size() > limit ? hits.subList(0, limit) : hits;
  }
}
