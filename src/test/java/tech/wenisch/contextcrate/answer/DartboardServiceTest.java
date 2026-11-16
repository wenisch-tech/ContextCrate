package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.index.SearchIndex;

class DartboardServiceTest {
  private static float[] v(double angleDegrees) {
    double r = Math.toRadians(angleDegrees);
    return new float[] {(float) Math.cos(r), (float) Math.sin(r)};
  }

  private static final float[] QUERY = v(0);

  @Test
  void firstPickIsTheCandidateClosestToTheQuery() {
    var chosen = DartboardService.select(QUERY, List.of(v(40), v(5), v(20)), 1, 1, 1, 0.1);
    assertThat(chosen).containsExactly(1);
  }

  @Test
  void aNearDuplicateIsSkippedForADifferentButStillRelevantChunk() {
    // 0: best, 1: duplicate of 0 (slightly worse), 2: different direction, a bit less relevant
    var candidates = List.of(v(10), v(11), v(-30));
    assertThat(DartboardService.select(QUERY, candidates, 2, 1, 1, 0.1)).containsExactly(0, 2);
    // without the diversity term the duplicate wins the second slot
    assertThat(DartboardService.select(QUERY, candidates, 2, 1, 0, 0.1)).containsExactly(0, 1);
  }

  @Test
  void relevanceOnlyReproducesTheRelevanceOrderAndDiversityOnlySpreadsOut() {
    var candidates = List.of(v(5), v(10), v(15), v(-60));
    assertThat(DartboardService.select(QUERY, candidates, 4, 1, 0, 0.1)).containsExactly(0, 1, 2, 3);
    // first pick is still the closest one; then the most distant chunk
    assertThat(DartboardService.select(QUERY, candidates, 2, 0, 1, 0.1)).containsExactly(0, 3);
  }

  @Test
  void sigmaChangesTheSelectionBecauseItSmoothsOverAllChosenChunks() {
    // chosen after two picks: 0 (best) and 1 (far away on the other side).
    // Candidate 2 is far from 0 but sits on top of 1; candidate 3 is moderately far from both.
    var candidates = List.of(v(2), v(-80), v(-79), v(-38));
    var small = DartboardService.select(QUERY, candidates, 3, 0, 1, 0.05);
    var large = DartboardService.select(QUERY, candidates, 3, 0, 1, 1.0);
    assertThat(small).hasSize(3).startsWith(0);
    assertThat(large).hasSize(3).startsWith(0);
    assertThat(small.get(2)).isNotEqualTo(large.get(2));
  }

  @Test
  void returnsAllCandidatesWhenKIsLargeAndNothingWhenKIsZero() {
    var candidates = List.of(v(5), v(10));
    assertThat(DartboardService.select(QUERY, candidates, 10, 1, 1, 0.1)).containsExactlyInAnyOrder(0, 1);
    assertThat(DartboardService.select(QUERY, candidates, 0, 1, 1, 0.1)).isEmpty();
    assertThat(DartboardService.select(QUERY, List.of(), 3, 1, 1, 0.1)).isEmpty();
  }

  @Test
  void staysFiniteWithTinySigmaIdenticalAndZeroVectors() {
    var candidates = List.of(v(5), v(5), new float[] {0, 0}, v(5));
    var chosen = DartboardService.select(QUERY, candidates, 4, 1, 1, 0.01);
    assertThat(chosen).containsExactlyInAnyOrder(0, 1, 2, 3);
  }

  @Test
  void rejectsVectorsOfDifferentLength() {
    assertThatThrownBy(() -> DartboardService.select(QUERY, List.of(new float[] {1, 0, 0}), 1, 1, 1, 0.1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void candidateLimitIsOversampledButCappedAt100() {
    assertThat(DartboardService.candidateLimit(5, 3)).isEqualTo(15);
    assertThat(DartboardService.candidateLimit(50, 10)).isEqualTo(100);
    assertThat(DartboardService.candidateLimit(150, 3)).isEqualTo(150);
  }

  // --- apply(): embeddings and fail-open behaviour ---

  private final UUID crateId = UUID.randomUUID();
  private final EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
  private final DartboardService service = new DartboardService(embeddings);

  private static SearchIndex.SearchHit hit(String text) {
    return new SearchIndex.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "chunk", "T", "u", 0, text, 1f, null, null, null, text, text);
  }

  private RagSettings policy() {
    var value = new RagSettings(crateId, false, true, true, true, false, false, "revise-once", "hybrid", 8);
    value.updateDartboard(true, 3, 1.0, 1.0, 0.1);
    return value;
  }

  @Test
  void reordersAndCutsTheCandidatesWithTheirOwnEmbeddings() throws Exception {
    var a = hit("a"); var b = hit("b"); var c = hit("c");
    when(embeddings.available()).thenReturn(true);
    when(embeddings.embedQuery("q")).thenReturn(QUERY);
    when(embeddings.embedDocuments(List.of("a", "b", "c"))).thenReturn(List.of(v(10), v(11), v(-30)));

    var result = service.apply(crateId, "q", new SearchIndex.SearchResults("q", "hybrid", List.of(a, b, c)), 2, policy());

    assertThat(result.hits()).containsExactly(a, c);
    assertThat(result.mode()).isEqualTo("hybrid");
  }

  @Test
  void keepsTheRetrievalOrderWhenEmbeddingsFailOrAreUnavailable() throws Exception {
    var hits = List.of(hit("a"), hit("b"), hit("c"));
    var input = new SearchIndex.SearchResults("q", "hybrid", hits);
    when(embeddings.available()).thenReturn(false);
    assertThat(service.apply(crateId, "q", input, 2, policy()).hits()).containsExactlyElementsOf(hits.subList(0, 2));

    when(embeddings.available()).thenReturn(true);
    when(embeddings.embedQuery(any())).thenThrow(new IllegalStateException("down"));
    assertThat(service.apply(crateId, "q", input, 2, policy()).hits()).containsExactlyElementsOf(hits.subList(0, 2));

    doReturn(QUERY).when(embeddings).embedQuery(any());
    doReturn(List.of(v(1))).when(embeddings).embedDocuments(any());
    assertThat(service.apply(crateId, "q", input, 2, policy()).hits()).containsExactlyElementsOf(hits.subList(0, 2));
  }
}
