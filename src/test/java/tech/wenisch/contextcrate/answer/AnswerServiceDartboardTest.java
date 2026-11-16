package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;

class AnswerServiceDartboardTest {
  private final UUID crateId = UUID.randomUUID();
  private final SearchIndex index = mock(SearchIndex.class);
  private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
  private final RagSettingsService settings = mock(RagSettingsService.class);
  private final EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
  private final List<SearchIndex.SearchHit> candidates = new ArrayList<>();

  private AnswerService service() throws Exception {
    var properties = mock(ContextCrateProperties.class);
    var answering = mock(ContextCrateProperties.Answering.class);
    when(properties.answering()).thenReturn(answering);
    when(answering.contextTokenBudget()).thenReturn(4_000);
    when(answering.maxHistoryMessages()).thenReturn(10);
    when(chunks.findByIdAndCrateId(any(), eq(crateId))).thenReturn(java.util.Optional.empty());
    for (int i = 0; i < 12; i++) {
      String text = "chunk " + i;
      candidates.add(new SearchIndex.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "chunk", "T", "u" + i, i, text, 1f, null, null, null, text, text));
    }
    when(index.search(any())).thenAnswer(i -> new SearchIndex.SearchResults("q", "hybrid", candidates.stream().limit(((SearchIndex.SearchRequest) i.getArgument(0)).limit()).toList()));
    when(embeddings.available()).thenReturn(true);
    when(embeddings.embedQuery(any())).thenReturn(new float[] {1, 0});
    when(embeddings.embedDocuments(any())).thenAnswer(i -> ((List<String>) i.getArgument(0)).stream().map(t -> new float[] {1, 0.01f * t.length()}).toList());
    var provider = mock(AnswerGenerationProvider.class);
    when(provider.available()).thenReturn(true);
    var service = new AnswerService(index, chunks, provider, mock(AuditLogRepository.class), properties, settings);
    service.setDartboard(new DartboardService(embeddings));
    return service;
  }

  private void settings(boolean dartboard, String mode, int sourceLimit) {
    var value = new RagSettings(crateId, false, true, true, true, false, false, "revise-once", mode, sourceLimit);
    value.updateDartboard(dartboard, 3, 1.0, 1.0, 0.1);
    when(settings.current(crateId)).thenReturn(value);
  }

  private AnswerService.Request request() {
    return new AnswerService.Request(crateId, "Question?", null, "chunk", null, null, List.of());
  }

  @Test
  void retrievesOversampledCandidatesAndAnswersWithTheSelectedK() throws Exception {
    settings(true, "hybrid", 4);

    var prepared = service().prepare(request());

    var searches = ArgumentCaptor.forClass(SearchIndex.SearchRequest.class);
    verify(index).search(searches.capture());
    assertThat(searches.getValue().limit()).isEqualTo(12);
    assertThat(prepared.sources()).hasSize(4);
  }

  @Test
  void doesNotOversampleWhenDisabled() throws Exception {
    settings(false, "hybrid", 4);
    service().prepare(request());
    var searches = ArgumentCaptor.forClass(SearchIndex.SearchRequest.class);
    verify(index).search(searches.capture());
    assertThat(searches.getValue().limit()).isEqualTo(4);
    verify(embeddings, never()).embedDocuments(any());
  }

  @Test
  void doesNotOversampleInLexicalMode() throws Exception {
    settings(true, "lexical", 4);
    service().prepare(request());
    var searches = ArgumentCaptor.forClass(SearchIndex.SearchRequest.class);
    verify(index).search(searches.capture());
    assertThat(searches.getValue().limit()).isEqualTo(4);
    verify(embeddings, never()).embedDocuments(any());
  }

  @Test
  void fallsBackToTheTopKWhenEmbeddingFails() throws Exception {
    settings(true, "hybrid", 4);
    var service = service();
    when(embeddings.embedQuery(any())).thenThrow(new IllegalStateException("down"));

    var prepared = service.prepare(request());

    assertThat(prepared.sources()).hasSize(4);
    assertThat(prepared.sources()).extracting(AnswerService.Source::chunkId).containsExactlyElementsOf(candidates.subList(0, 4).stream().map(SearchIndex.SearchHit::id).toList());
  }
}
