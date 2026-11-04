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

  private static Float scoreOf(List<SearchHit> list, UUID id, boolean lexical) {
    return list.stream().filter(h -> h.id().equals(id)).map(h -> lexical ? h.lexicalScore() : h.semanticScore()).findFirst().orElse(null);
  }
}
