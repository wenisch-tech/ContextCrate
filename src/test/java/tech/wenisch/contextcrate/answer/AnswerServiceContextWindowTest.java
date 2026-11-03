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

class AnswerServiceContextWindowTest {
  private final UUID crateId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();
  private final SearchIndex index = mock(SearchIndex.class);
  private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private final RagSettingsService settings = mock(RagSettingsService.class);
  private final ContextWindowService windows = new ContextWindowService(chunks);

  private DocumentChunk chunk(int ordinal) {
    var c = new DocumentChunk(UUID.nameUUIDFromBytes(("c" + ordinal).getBytes()), documentId, ordinal, null, "chunk-" + ordinal, Hashing.sha256("c" + ordinal)).overlap(0);
    c.assignCrate(crateId);
    return c;
  }

  private AnswerService service(int radius, boolean grading) throws Exception {
    var properties = mock(ContextCrateProperties.class);
    var answering = mock(ContextCrateProperties.Answering.class);
    when(properties.answering()).thenReturn(answering);
    when(answering.contextTokenBudget()).thenReturn(4_000);
    when(answering.maxHistoryMessages()).thenReturn(10);
    var all = java.util.stream.IntStream.range(0, 10).mapToObj(this::chunk).toList();
    when(chunks.findByIdAndCrateId(any(), any())).thenAnswer(i -> all.stream().filter(c -> c.getId().equals(i.getArgument(0))).findFirst());
    when(chunks.findByDocumentIdAndCrateIdAndOrdinalBetweenOrderByOrdinal(any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenAnswer(i -> all.stream().filter(c -> c.getOrdinal() >= (int) i.getArgument(2) && c.getOrdinal() <= (int) i.getArgument(3)).toList());
    var value = new RagSettings(crateId, false, true, true, true, grading, false, "revise-once", "lexical", 8);
    value.updateContextWindow(radius);
    when(settings.current(crateId)).thenReturn(value);
    when(provider.available()).thenReturn(true);
    when(provider.complete(any())).thenReturn("yes");
    var service = new AnswerService(index, chunks, provider, mock(AuditLogRepository.class), properties, settings);
    service.setContextWindow(windows);
    return service;
  }

  private SearchIndex.SearchHit hit(int ordinal) {
    var c = chunk(ordinal);
    return new SearchIndex.SearchHit(c.getId(), documentId, UUID.randomUUID(), "chunk", "Title", "https://example.test/doc", ordinal, "snippet-" + ordinal, 1f);
  }

  private AnswerService.Request request() {
    return new AnswerService.Request(crateId, "question", null, "chunk", "lexical", null, List.of());
  }

  @Test
  void withoutAWindowTheMatchedChunksAreUsedAsTheyAre() throws Exception {
    var service = service(0, false);
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "lexical", List.of(hit(5))));

    var prepared = service.prepare(request());

    assertThat(prepared.sources()).singleElement().satisfies(s -> assertThat(s.content()).isEqualTo("chunk-5"));
  }

  @Test
  void aWindowOfOneSendsThreeChunksInIndexOrderAndMergesNeighbouringMatches() throws Exception {
    var service = service(1, false);
    // Hits ranked: 5, 9, 4. Window of 1: 3-6 (merged from 5 and 4) and 8-9 (clamped at the end).
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "lexical", List.of(hit(5), hit(9), hit(4))));

    var prepared = service.prepare(request());

    assertThat(prepared.sources()).hasSize(2);
    assertThat(prepared.sources().get(0).content()).isEqualTo("chunk-3\n\nchunk-4\n\nchunk-5\n\nchunk-6");
    assertThat(prepared.sources().get(0).citation()).isEqualTo(1);
    assertThat(prepared.sources().get(0).chunkId()).isEqualTo(chunk(5).getId()); // the best ranked match
    assertThat(prepared.sources().get(1).content()).isEqualTo("chunk-8\n\nchunk-9");
    assertThat(prepared.sources().get(1).citation()).isEqualTo(2);
  }

  @Test
  void gradingJudgesTheMatchedChunkNotTheExpandedWindow() throws Exception {
    var service = service(1, true);
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "lexical", List.of(hit(5))));

    var prepared = service.prepare(request());

    var prompts = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(provider).complete(prompts.capture());
    assertThat(prompts.getValue().toString()).contains("chunk-5").doesNotContain("chunk-4").doesNotContain("chunk-6");
    assertThat(prepared.sources().getFirst().content()).contains("chunk-4", "chunk-6");
  }

  @Test
  void aFailureWhileLoadingNeighboursKeepsTheMatchedChunks() throws Exception {
    var service = service(1, false);
    when(chunks.findByDocumentIdAndCrateIdAndOrdinalBetweenOrderByOrdinal(any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenThrow(new IllegalStateException("database down"));
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "lexical", List.of(hit(5))));

    var prepared = service.prepare(request());

    assertThat(prepared.sources()).singleElement().satisfies(s -> assertThat(s.content()).isEqualTo("chunk-5"));
  }
}
