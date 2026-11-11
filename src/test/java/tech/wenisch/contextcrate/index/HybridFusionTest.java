package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.index.SearchIndex.SearchHit;

class HybridFusionTest {
  private static SearchHit keyword(UUID id, float score) {
    return new SearchHit(id, UUID.randomUUID(), null, "chunk", "t", "u", 0, "s", score, score, null);
  }

  private static SearchHit vector(UUID id, float score) {
    return new SearchHit(id, UUID.randomUUID(), null, "chunk", "t", "u", 0, "s", score, null, score);
  }

  private final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private final UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private final UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");

  @Test
  void eachListIsScaledToZeroToOneBeforeTheyAreCombined() {
    // BM25 scores live on a different scale than cosine similarities; after min-max both count equally.
    var lexical = List.of(keyword(a, 12f), keyword(b, 6f), keyword(c, 0f));
    var semantic = List.of(vector(c, 0.75f), vector(b, 0.5f), vector(a, 0.25f));

    var fused = HybridFusion.fuseNormalized(lexical, semantic, 0.5, 10);

    // a: 0.5*0 + 0.5*1 = 0.5; b: 0.5*0.5 + 0.5*0.5 = 0.5; c: 0.5*1 + 0 = 0.5 -> tie broken by id
    assertThat(fused).extracting(SearchHit::id).containsExactly(a, b, c);
    assertThat(fused).allSatisfy(h -> assertThat(h.score()).isBetween(0f, 1f).isCloseTo(0.5f, within(1e-6f)));
  }

  @Test
  void theWeightMovesTheRankingFromKeywordToVector() {
    var lexical = List.of(keyword(a, 10f), keyword(b, 1f));
    var semantic = List.of(vector(b, 0.9f), vector(a, 0.1f));

    assertThat(HybridFusion.fuseNormalized(lexical, semantic, 0.0, 10)).extracting(SearchHit::id).containsExactly(a, b);
    assertThat(HybridFusion.fuseNormalized(lexical, semantic, 1.0, 10)).extracting(SearchHit::id).containsExactly(b, a);
    var keywordOnly = HybridFusion.fuseNormalized(lexical, semantic, 0.0, 10);
    assertThat(keywordOnly.get(0).score()).isEqualTo(1f);
    assertThat(keywordOnly.get(1).score()).isEqualTo(0f);
    // a null weight means equal weights
    assertThat(HybridFusion.fuseNormalized(lexical, semantic, null, 10).get(0).score()).isCloseTo(0.5f, within(1e-6f));
  }

  @Test
  void aChunkMissingFromOneListScoresZeroThereAndKeepsRawScores() {
    var fused = HybridFusion.fuseNormalized(List.of(keyword(a, 8f), keyword(b, 4f)), List.of(vector(a, 0.6f)), 0.5, 10);

    assertThat(fused.get(0).id()).isEqualTo(a);
    assertThat(fused.get(0).score()).isCloseTo(1f, within(1e-6f)); // a is best in both lists
    assertThat(fused.get(0).lexicalScore()).isEqualTo(8f);
    assertThat(fused.get(0).semanticScore()).isEqualTo(0.6f);
    assertThat(fused.get(1).id()).isEqualTo(b);
    assertThat(fused.get(1).score()).isCloseTo(0f, within(1e-6f)); // lowest keyword score, not in the vector list
    assertThat(fused.get(1).semanticScore()).isNull();
  }

  @Test
  void equalScoresAndSingleHitsNormalizeToOne() {
    assertThat(HybridFusion.normalize(List.of(keyword(a, 3f), keyword(b, 3f)))).containsEntry(a, 1.0).containsEntry(b, 1.0);
    assertThat(HybridFusion.normalize(List.of(keyword(a, 0.2f)))).containsEntry(a, 1.0);
    assertThat(HybridFusion.normalize(List.of())).isEmpty();
  }

  @Test
  void theLimitAndEmptyListsAreRespected() {
    assertThat(HybridFusion.fuseNormalized(List.of(), List.of(), 0.5, 5)).isEmpty();
    var fused = HybridFusion.fuseNormalized(List.of(keyword(a, 3f), keyword(b, 2f), keyword(c, 1f)), List.of(), 0.5, 2);
    assertThat(fused).extracting(SearchHit::id).containsExactly(a, b);
    // only one leg available: it still ranks by its own normalized score
    assertThat(fused.get(0).score()).isCloseTo(0.5f, within(1e-6f));
  }
}
