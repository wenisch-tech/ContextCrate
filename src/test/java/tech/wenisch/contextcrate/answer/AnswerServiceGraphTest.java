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
import tech.wenisch.contextcrate.graph.GraphSearchService;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;

class AnswerServiceGraphTest {
  private final UUID crateId = UUID.randomUUID();
  private final SearchIndex index = mock(SearchIndex.class);
  private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private final RagSettingsService settings = mock(RagSettingsService.class);
  private final GraphSearchService graph = mock(GraphSearchService.class);
  private final AdaptiveRetrievalService adaptive = mock(AdaptiveRetrievalService.class);

  private static SearchIndex.SearchHit hit(UUID id) {
    return new SearchIndex.SearchHit(id, UUID.randomUUID(), UUID.randomUUID(), "chunk", "T", "https://example.test/" + id, 0, "content " + id, 1f);
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
    service.setGraphSearch(graph);
    service.setAdaptive(adaptive);
    return service;
  }

  private void settings(boolean retrieval) {
    var value = new RagSettings(crateId, true, true, true, true, false, false, "revise-once", "hybrid", 4);
    value.updateGraphRag(true, retrieval, "auto");
    value.updateAdaptiveRetrieval(true);
    when(settings.current(crateId)).thenReturn(value);
  }

  private AnswerService.Prepared ask(AnswerService service) throws Exception {
    return service.prepare(new AnswerService.Request(crateId, "How is X related to Y?", null, "chunk", null, null, List.of()));
  }

  @Test
  void localGraphSearchReplacesNormalRetrievalAndKeepsTheGraphOrder() throws Exception {
    settings(true);
    UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    when(graph.search(eq(crateId), eq("How is X related to Y?"), any(), eq("auto"), eq(4), any()))
        .thenReturn(new GraphSearchService.Result("local", List.of(first, second), new AnswerService.GraphContext("local", "ENTITIES:\n- X (PRODUCT): x\n", 1, 0)));
    // The index returns the chosen chunks in its own order; the graph order wins.
    when(index.search(argThat(r -> r != null && r.chunkIds() != null))).thenReturn(new SearchIndex.SearchResults("q", "hybrid", List.of(hit(second), hit(first))));

    var service = service();
    var prepared = ask(service);

    assertThat(prepared.sources()).extracting(AnswerService.Source::chunkId).containsExactly(first, second);
    assertThat(prepared.transformations()).containsExactly("graph:local");
    assertThat(prepared.graph().text()).contains("X (PRODUCT)");
    verifyNoInteractions(adaptive);
    verify(index, times(1)).search(any());

    when(provider.complete(any())).thenReturn("ok");
    var messages = ArgumentCaptor.forClass(List.class);
    doNothing().when(provider).stream(messages.capture(), any());
    service.generate(prepared);
    assertThat(messages.getValue().toString()).contains("UNTRUSTED KNOWLEDGE GRAPH CONTEXT").contains("X (PRODUCT)").contains("[SOURCE 1]");
  }

  @Test
  void globalGraphSearchHasNoSourcesButStillHasContext() throws Exception {
    settings(true);
    when(graph.search(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(new GraphSearchService.Result("global", List.of(), new AnswerService.GraphContext("global", "- [relevance 80] The main theme is storage.\n", 0, 3)));
    var prepared = ask(service());
    assertThat(prepared.sources()).isEmpty();
    assertThat(prepared.hasContext()).isTrue();
    assertThat(prepared.transformations()).containsExactly("graph:global");
    verify(index, never()).search(any());
  }

  @Test
  void nothingFromTheGraphRunsTheNormalPipeline() throws Exception {
    settings(true);
    when(graph.search(any(), any(), any(), any(), anyInt(), any())).thenReturn(null);
    when(adaptive.classify(any(), any(), any())).thenReturn(null);
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "hybrid", List.of(hit(UUID.randomUUID()))));
    var prepared = ask(service());
    assertThat(prepared.sources()).hasSize(1);
    assertThat(prepared.graph()).isNull();
    verify(adaptive).classify(any(), any(), any());
  }

  @Test
  void graphRetrievalOffNeverSearchesTheGraph() throws Exception {
    settings(false);
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "hybrid", List.of(hit(UUID.randomUUID()))));
    ask(service());
    verifyNoInteractions(graph);
  }
}
