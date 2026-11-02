package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;

class AnswerServiceHydeTest {
  private final UUID crateId = UUID.randomUUID();
  private final SearchIndex index = mock(SearchIndex.class);
  private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private final RagSettingsService settings = mock(RagSettingsService.class);
  private final HydeService hyde = mock(HydeService.class);
  private final QueryTransformationService transformation = mock(QueryTransformationService.class);

  private static SearchIndex.SearchHit hit(String content) {
    return new SearchIndex.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "chunk", "T", "https://example.test/" + content, 0, content, 1f);
  }

  private AnswerService service() throws Exception {
    var properties = mock(ContextCrateProperties.class);
    var answering = mock(ContextCrateProperties.Answering.class);
    when(properties.answering()).thenReturn(answering);
    when(answering.contextTokenBudget()).thenReturn(4_000);
    when(answering.maxHistoryMessages()).thenReturn(10);
    when(chunks.findByIdAndCrateId(any(), eq(crateId))).thenReturn(java.util.Optional.empty());
    when(provider.available()).thenReturn(true);
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "hybrid", List.of(hit("real chunk"))));
    var service = new AnswerService(index, chunks, provider, mock(AuditLogRepository.class), properties, settings);
    service.setHyde(hyde);
    service.setQueryTransformation(transformation);
    return service;
  }

  private void settings(boolean hydeEnabled, String mode) {
    var value = new RagSettings(crateId, false, true, true, true, false, false, "revise-once", mode, 8);
    value.updateHyde(hydeEnabled);
    when(settings.current(crateId)).thenReturn(value);
  }

  private AnswerService.Request request() {
    return new AnswerService.Request(crateId, "Original question", null, "chunk", null, null, List.of());
  }

  @Test
  void embedsTheHypotheticalAnswerButKeepsTheQuestionEverywhereElse() throws Exception {
    settings(true, "hybrid");
    when(hyde.generate(crateId, "Original question")).thenReturn("A hypothetical answer.");

    var prepared = service().prepare(request());

    var searches = ArgumentCaptor.forClass(SearchIndex.SearchRequest.class);
    verify(index).search(searches.capture());
    assertThat(searches.getValue().query()).isEqualTo("Original question");
    assertThat(searches.getValue().vectorQuery()).isEqualTo("A hypothetical answer.");
    assertThat(searches.getValue().mode()).isEqualTo("hybrid");
    // Only the real chunk is a source; the hypothetical text appears nowhere in the result.
    assertThat(prepared.question()).isEqualTo("Original question");
    assertThat(prepared.sources()).extracting(AnswerService.Source::content).containsExactly("real chunk");
    assertThat(prepared.searchQueries()).containsExactly("Original question");
    assertThat(prepared.hydeQueries()).isEqualTo(1);
    assertThat(prepared.toString()).doesNotContain("A hypothetical answer.");
  }

  @Test
  void generationFailureFallsBackToTheQuestion() throws Exception {
    settings(true, "hybrid");
    when(hyde.generate(any(), any())).thenReturn(null);

    var prepared = service().prepare(request());

    var searches = ArgumentCaptor.forClass(SearchIndex.SearchRequest.class);
    verify(index).search(searches.capture());
    assertThat(searches.getValue().vectorQuery()).isNull();
    assertThat(prepared.hydeQueries()).isZero();
  }

  @Test
  void lexicalModeSkipsHydeWithoutCallingTheModel() throws Exception {
    settings(true, "lexical");

    service().prepare(request());

    verify(hyde, never()).generate(any(), any());
    verify(hyde).skipped(crateId, "lexical-mode");
    var searches = ArgumentCaptor.forClass(SearchIndex.SearchRequest.class);
    verify(index).search(searches.capture());
    assertThat(searches.getValue().vectorQuery()).isNull();
  }

  @Test
  void unavailableEmbeddingsSkipHydeWithoutCallingTheModel() throws Exception {
    settings(true, "hybrid");
    var embeddings = mock(tech.wenisch.contextcrate.embedding.EmbeddingProvider.class);
    when(embeddings.available()).thenReturn(false);
    var service = service();
    service.setEmbeddings(embeddings);

    service.prepare(request());

    verify(hyde, never()).generate(any(), any());
    verify(hyde).skipped(crateId, "embeddings-unavailable");
  }

  @Test
  void disabledSettingNeverCallsHyde() throws Exception {
    settings(false, "hybrid");

    service().prepare(request());

    verifyNoInteractions(hyde);
  }

  @Test
  void eachTransformedQueryGetsItsOwnHypotheticalAnswer() throws Exception {
    var value = new RagSettings(crateId, false, true, true, true, false, false, "revise-once", "hybrid", 8);
    value.updateHyde(true);
    value.updateQueryTransformation(true, "sub-queries");
    when(settings.current(crateId)).thenReturn(value);
    when(transformation.plan(eq(crateId), eq("Original question"), any(), eq("sub-queries")))
        .thenReturn(new QueryTransformationService.Plan(List.of("part one", "part two"), List.of("sub-queries")));
    when(hyde.generate(crateId, "part one")).thenReturn("answer one");
    when(hyde.generate(crateId, "part two")).thenReturn(null);

    var prepared = service().prepare(request());

    var searches = ArgumentCaptor.forClass(SearchIndex.SearchRequest.class);
    verify(index, times(2)).search(searches.capture());
    assertThat(searches.getAllValues()).extracting(SearchIndex.SearchRequest::query).containsExactly("part one", "part two");
    assertThat(searches.getAllValues()).extracting(SearchIndex.SearchRequest::vectorQuery).containsExactly("answer one", null);
    assertThat(prepared.hydeQueries()).isEqualTo(1);
  }
}
