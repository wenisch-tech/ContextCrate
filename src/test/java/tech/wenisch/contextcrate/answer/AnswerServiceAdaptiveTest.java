package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.wenisch.contextcrate.answer.AdaptiveRetrievalService.Category;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;

class AnswerServiceAdaptiveTest {
  private final UUID crateId = UUID.randomUUID();
  private final SearchIndex index = mock(SearchIndex.class);
  private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private final RagSettingsService settings = mock(RagSettingsService.class);
  private final QueryTransformationService transformation = mock(QueryTransformationService.class);
  private final AdaptiveRetrievalService adaptive = mock(AdaptiveRetrievalService.class);

  private static SearchIndex.SearchHit hit(String content) {
    return new SearchIndex.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "chunk", "T", "https://example.test/" + content, 0, content, 1f);
  }

  private AnswerService service() {
    var properties = mock(ContextCrateProperties.class);
    var answering = mock(ContextCrateProperties.Answering.class);
    when(properties.answering()).thenReturn(answering);
    when(answering.contextTokenBudget()).thenReturn(4_000);
    when(answering.maxHistoryMessages()).thenReturn(10);
    when(chunks.findByIdAndCrateId(any(), eq(crateId))).thenReturn(java.util.Optional.empty());
    when(provider.available()).thenReturn(true);
    var service = new AnswerService(index, chunks, provider, mock(AuditLogRepository.class), properties, settings);
    service.setQueryTransformation(transformation);
    service.setAdaptive(adaptive);
    return service;
  }

  private void settings(boolean adaptiveEnabled, boolean transformationEnabled) {
    var value = new RagSettings(crateId, false, true, true, true, false, false, "revise-once", "hybrid", 3);
    value.updateAdaptiveRetrieval(adaptiveEnabled);
    value.updateQueryTransformation(transformationEnabled, "rewrite");
    when(settings.current(crateId)).thenReturn(value);
  }

  private AnswerService.Prepared ask(AnswerService service) throws Exception {
    return service.prepare(new AnswerService.Request(crateId, "Original question", null, "chunk", null, null, List.of()));
  }

  @Test
  void disabledAdaptiveRetrievalNeverCallsTheClassifier() throws Exception {
    settings(false, false);
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "hybrid", List.of(hit("a"))));
    var prepared = ask(service());
    verifyNoInteractions(adaptive);
    verify(index, times(1)).search(any());
    assertThat(prepared.transformations()).isEmpty();
  }

  @Test
  void factualRewritesFetchesDoubleTheCandidatesAndKeepsTheSelectedTopK() throws Exception {
    settings(true, true);
    when(adaptive.classify(eq(crateId), eq("Original question"), any())).thenReturn(Category.FACTUAL);
    when(transformation.plan(eq(crateId), eq("Original question"), any(), eq("rewrite")))
        .thenReturn(new QueryTransformationService.Plan(List.of("precise question"), List.of("rewrite")));
    var hits = List.of(hit("h0"), hit("h1"), hit("h2"), hit("h3"), hit("h4"), hit("h5"));
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("precise question", "hybrid", hits));
    when(adaptive.select(eq(crateId), eq(Category.FACTUAL), eq("Original question"), any(), eq(3))).thenReturn(List.of(4, 1, 2));

    var prepared = ask(service());

    var request = ArgumentCaptor.forClass(SearchIndex.SearchRequest.class);
    verify(index).search(request.capture());
    assertThat(request.getValue().query()).isEqualTo("precise question");
    assertThat(request.getValue().limit()).isEqualTo(6);
    assertThat(prepared.sources()).extracting(AnswerService.Source::chunkId).containsExactly(hits.get(4).id(), hits.get(1).id(), hits.get(2).id());
    assertThat(prepared.question()).isEqualTo("Original question");
    assertThat(prepared.transformations()).containsExactly("adaptive:factual", "rewrite");
  }

  @Test
  void analyticalSearchesEverySubQuestionAndLetsTheModelPickFromThePool() throws Exception {
    settings(true, true);
    when(adaptive.classify(eq(crateId), any(), any())).thenReturn(Category.ANALYTICAL);
    when(adaptive.decompose(eq(crateId), eq("Original question"), any())).thenReturn(List.of("Original question", "angle A", "angle B"));
    var a = hit("a"); var b = hit("b"); var shared = hit("shared"); var c = hit("c");
    when(index.search(argThat(r -> r != null && r.query().equals("Original question")))).thenReturn(new SearchIndex.SearchResults("q", "hybrid", List.of(shared, a)));
    when(index.search(argThat(r -> r != null && r.query().equals("angle A")))).thenReturn(new SearchIndex.SearchResults("q", "hybrid", List.of(shared, b)));
    when(index.search(argThat(r -> r != null && r.query().equals("angle B")))).thenReturn(new SearchIndex.SearchResults("q", "hybrid", List.of(c)));
    var pool = ArgumentCaptor.forClass(List.class);
    when(adaptive.select(eq(crateId), eq(Category.ANALYTICAL), any(), pool.capture(), eq(3))).thenReturn(List.of(3, 0, 1));

    var prepared = ask(service());

    verify(index, times(3)).search(any());
    verifyNoInteractions(transformation);
    assertThat(pool.getValue()).hasSize(4); // deduplicated pool of all three lists
    assertThat(prepared.sources()).hasSize(3);
    assertThat(prepared.searchQueries()).containsExactly("Original question", "angle A", "angle B");
    assertThat(prepared.transformations()).containsExactly("adaptive:analytical");
  }

  @Test
  void opinionSearchesEveryPerspectiveQuery() throws Exception {
    settings(true, false);
    when(adaptive.classify(eq(crateId), any(), any())).thenReturn(Category.OPINION);
    when(adaptive.perspectives(eq(crateId), eq("Original question"), any())).thenReturn(List.of("Original question", "Original question pro", "Original question con"));
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "hybrid", List.of(hit("x"))));
    when(adaptive.select(any(), any(), any(), any(), anyInt())).thenReturn(List.of(0));

    var prepared = ask(service());

    verify(index, times(3)).search(any());
    assertThat(prepared.transformations()).containsExactly("adaptive:opinion");
  }

  @Test
  void unknownCategoryRunsTheNormalPipelineIncludingQueryTransformation() throws Exception {
    settings(true, true);
    when(adaptive.classify(eq(crateId), any(), any())).thenReturn(null);
    when(transformation.plan(eq(crateId), eq("Original question"), any(), eq("rewrite")))
        .thenReturn(new QueryTransformationService.Plan(List.of("rewritten"), List.of("rewrite")));
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "hybrid", List.of(hit("a"))));

    var prepared = ask(service());

    verify(adaptive, never()).select(any(), any(), any(), any(), anyInt());
    assertThat(prepared.transformations()).containsExactly("rewrite");
    assertThat(prepared.searchQueries()).containsExactly("rewritten");
  }
}
