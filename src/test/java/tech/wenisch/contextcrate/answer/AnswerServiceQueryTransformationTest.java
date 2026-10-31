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

class AnswerServiceQueryTransformationTest {
  private final UUID crateId = UUID.randomUUID();
  private final SearchIndex index = mock(SearchIndex.class);
  private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private final RagSettingsService settings = mock(RagSettingsService.class);
  private final QueryTransformationService transformation = mock(QueryTransformationService.class);

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
    return service;
  }

  private void settings(boolean enabled, boolean grading) {
    var value = new RagSettings(crateId, false, true, true, true, grading, false, "revise-once", "hybrid", 8);
    value.updateQueryTransformation(enabled, "rewrite,sub-queries");
    when(settings.current(crateId)).thenReturn(value);
  }

  @Test
  void searchesOncePerQueryFusesTheListsAndKeepsTheOriginalQuestion() throws Exception {
    settings(true, true);
    var shared = hit("shared"); var onlyA = hit("only-a"); var onlyB = hit("only-b");
    when(transformation.plan(eq(crateId), eq("Original question"), any(), eq("rewrite,sub-queries")))
        .thenReturn(new QueryTransformationService.Plan(List.of("query A", "query B"), List.of("rewrite", "sub-queries")));
    when(index.search(argThat(r -> r != null && r.query().equals("query A"))))
        .thenReturn(new SearchIndex.SearchResults("query A", "hybrid", List.of(onlyA, shared)));
    when(index.search(argThat(r -> r != null && r.query().equals("query B"))))
        .thenReturn(new SearchIndex.SearchResults("query B", "hybrid", List.of(onlyB, shared)));
    when(provider.complete(any())).thenReturn("yes");

    var prepared = service().prepare(new AnswerService.Request(crateId, "Original question", null, "chunk", null, null, List.of()));

    verify(index, times(2)).search(any());
    // The chunk found by both queries outranks chunks found by only one.
    assertThat(prepared.sources().getFirst().chunkId()).isEqualTo(shared.id());
    assertThat(prepared.sources()).hasSize(3);
    assertThat(prepared.question()).isEqualTo("Original question");
    assertThat(prepared.searchQueries()).containsExactly("query A", "query B");
    assertThat(prepared.transformations()).containsExactly("rewrite", "sub-queries");
    // Grading judges relevance against what the user actually asked.
    var prompts = ArgumentCaptor.forClass(List.class);
    verify(provider, atLeastOnce()).complete(prompts.capture());
    assertThat(prompts.getAllValues().getFirst().toString()).contains("Original question").doesNotContain("query A");
  }

  @Test
  void disabledTransformationSearchesExactlyOnceWithTheQuestion() throws Exception {
    settings(false, false);
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "hybrid", List.of(hit("a"))));

    var prepared = service().prepare(new AnswerService.Request(crateId, "Original question", null, "chunk", null, null, List.of()));

    verify(index, times(1)).search(argThat(r -> r.query().equals("Original question")));
    verifyNoInteractions(transformation);
    assertThat(prepared.transformations()).isEmpty();
  }

  @Test
  void aTransformationThatChangedNothingKeepsASingleSearchWithTheQuestion() throws Exception {
    settings(true, false);
    when(transformation.plan(any(), any(), any(), any()))
        .thenReturn(new QueryTransformationService.Plan(List.of("Original question"), List.of()));
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "hybrid", List.of(hit("a"))));

    service().prepare(new AnswerService.Request(crateId, "Original question", null, "chunk", null, null, List.of()));

    verify(index, times(1)).search(any());
  }
}
