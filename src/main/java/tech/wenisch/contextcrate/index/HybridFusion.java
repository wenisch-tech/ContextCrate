package tech.wenisch.contextcrate.index;

import java.util.*;
import tech.wenisch.contextcrate.index.SearchIndex.SearchHit;

/** Reciprocal-rank fusion of a keyword result list and a vector result list, merged by hit id. */
final class HybridFusion {
  private HybridFusion() {}

  static List<SearchHit> fuse(List<SearchHit> lexical, List<SearchHit> semantic, int rrfConstant, int limit) {
    Map<UUID, SearchHit> all = new LinkedHashMap<>();
    Map<UUID, Float> score = new HashMap<>();
    for (int i = 0; i < lexical.size(); i++) {
      var hit = lexical.get(i);
      all.put(hit.id(), hit);
      score.merge(hit.id(), 1f / (rrfConstant + i + 1), Float::sum);
    }
    for (int i = 0; i < semantic.size(); i++) {
      var hit = semantic.get(i);
      all.putIfAbsent(hit.id(), hit);
      score.merge(hit.id(), 1f / (rrfConstant + i + 1), Float::sum);
    }
    return all.values().stream()
        .map(h -> new SearchHit(h.id(), h.documentId(), h.runId(), h.kind(), h.title(), h.sourceUri(), h.chunkOrdinal(), h.snippet(), score.get(h.id()),
            scoreOf(lexical, h.id(), true), scoreOf(semantic, h.id(), false), null, h.content(), h.retrievalContent()))
        .sorted(Comparator.comparing(SearchHit::score).reversed().thenComparing(h -> h.id().toString()))
        .limit(limit).toList();
  }

  /** Weight of the vector leg when a request does not carry one: both legs count equally. */
  static final double DEFAULT_VECTOR_WEIGHT = 0.5;

  /**
   * Score fusion: each list is min-max normalized to 0..1 over its own hits, the final score is
   * {@code w * vector + (1 - w) * keyword}, and a chunk missing from one list scores 0 there. When all
   * scores of a list are equal (or it has one hit) its hits normalize to 1. The raw leg scores stay in
   * {@code lexicalScore} and {@code semanticScore}.
   */
  static List<SearchHit> fuseNormalized(List<SearchHit> lexical, List<SearchHit> semantic, Double vectorWeight, int limit) {
    double w = vectorWeight == null ? DEFAULT_VECTOR_WEIGHT : Math.max(0, Math.min(1, vectorWeight));
    Map<UUID, SearchHit> all = new LinkedHashMap<>();
    Map<UUID, Double> keyword = normalize(lexical), vector = normalize(semantic);
    lexical.forEach(h -> all.putIfAbsent(h.id(), h));
    semantic.forEach(h -> all.putIfAbsent(h.id(), h));
    Map<UUID, Float> lexicalRaw = new HashMap<>(), semanticRaw = new HashMap<>();
    lexical.forEach(h -> lexicalRaw.putIfAbsent(h.id(), h.lexicalScore() != null ? h.lexicalScore() : h.score()));
    semantic.forEach(h -> semanticRaw.putIfAbsent(h.id(), h.semanticScore() != null ? h.semanticScore() : h.score()));
    return all.values().stream()
        .map(h -> new SearchHit(h.id(), h.documentId(), h.runId(), h.kind(), h.title(), h.sourceUri(), h.chunkOrdinal(), h.snippet(),
            (float) (w * vector.getOrDefault(h.id(), 0.0) + (1 - w) * keyword.getOrDefault(h.id(), 0.0)),
            lexicalRaw.get(h.id()), semanticRaw.get(h.id()), null, h.content(), h.retrievalContent()))
        .sorted(Comparator.comparing(SearchHit::score).reversed().thenComparing(h -> h.id().toString()))
        .limit(limit).toList();
  }

  /** Min-max normalization of a hit list by hit id; the leg's own score is used (the hit's {@code score}). */
  static Map<UUID, Double> normalize(List<SearchHit> hits) {
    Map<UUID, Double> out = new HashMap<>();
    if (hits.isEmpty()) return out;
    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
    for (var h : hits) { min = Math.min(min, h.score()); max = Math.max(max, h.score()); }
    double range = max - min;
    for (var h : hits) out.putIfAbsent(h.id(), range <= 1e-12 ? 1.0 : (h.score() - min) / range);
    return out;
  }

  private static Float scoreOf(List<SearchHit> list, UUID id, boolean lexical) {
    return list.stream().filter(h -> h.id().equals(id)).map(h -> lexical ? h.lexicalScore() : h.semanticScore()).findFirst().orElse(null);
  }
}
