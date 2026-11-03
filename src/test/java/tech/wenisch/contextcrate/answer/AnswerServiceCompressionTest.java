package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;
import tech.wenisch.contextcrate.storage.Hashing;

class AnswerServiceCompressionTest {
  private final UUID crateId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();
  private final SearchIndex index = mock(SearchIndex.class);
  private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private final RagSettingsService settings = mock(RagSettingsService.class);

  private AnswerService service(boolean compression) throws Exception {
    var properties = mock(ContextCrateProperties.class);
    var answering = mock(ContextCrateProperties.Answering.class);
    when(properties.answering()).thenReturn(answering);
    when(answering.contextTokenBudget()).thenReturn(4_000);
    when(answering.maxHistoryMessages()).thenReturn(10);
    var chunk = new DocumentChunk(UUID.nameUUIDFromBytes("c".getBytes()), documentId, 0, null,
        "Some intro. The rotation is weekly. Filler text follows.", Hashing.sha256("c")).overlap(0);
    chunk.assignCrate(crateId);
    when(chunks.findByIdAndCrateId(any(), any())).thenReturn(Optional.of(chunk));
    var value = new RagSettings(crateId, false, true, true, true, false, false, "revise-once", "lexical", 8);
    value.updateContextCompression(compression);
    when(settings.current(crateId)).thenReturn(value);
    when(provider.available()).thenReturn(true);
    when(provider.complete(any())).thenReturn("{\"relevant\":[2]}");
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "lexical", List.of(
        new SearchIndex.SearchHit(chunk.getId(), documentId, UUID.randomUUID(), "chunk", "Title", "https://example.test/doc", 0, "snippet", 1f))));
    var service = new AnswerService(index, chunks, provider, mock(AuditLogRepository.class), properties, settings);
    service.setCompression(new ContextCompressionService(provider));
    return service;
  }

  private AnswerService.Request request() {
    return new AnswerService.Request(crateId, "How often is the rotation?", null, "chunk", "lexical", null, List.of());
  }

  @Test
  void whenOffTheChunkIsUsedAsRetrieved() throws Exception {
    var prepared = service(false).prepare(request());
    assertThat(prepared.sources()).singleElement().satisfies(s -> assertThat(s.content()).contains("Some intro", "Filler text"));
    verify(provider, never()).complete(any());
  }

  @Test
  void whenOnOnlyTheRelevantSentenceReachesTheAnswerModel() throws Exception {
    var prepared = service(true).prepare(request());
    assertThat(prepared.sources()).singleElement().satisfies(s -> {
      assertThat(s.content()).isEqualTo("The rotation is weekly.");
      assertThat(s.snippet()).isEqualTo("snippet");
    });
  }
}
