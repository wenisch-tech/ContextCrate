package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.domain.SectionSummary;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;
import tech.wenisch.contextcrate.repository.SectionSummaryRepository;

class AnswerServiceHierarchicalTest {
  private final UUID crateId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();
  private final UUID sectionId = UUID.randomUUID();
  private final SearchIndex index = mock(SearchIndex.class);
  private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
  private final SectionSummaryRepository sections = mock(SectionSummaryRepository.class);
  private final RagSettingsService settings = mock(RagSettingsService.class);
  private final DocumentChunk inSection = new DocumentChunk(UUID.randomUUID(), documentId, 3, "Ops > Pager", "pager text", "h");

  private static SearchIndex.SearchHit hit(UUID id, String kind) {
    return new SearchIndex.SearchHit(id, UUID.randomUUID(), UUID.randomUUID(), kind, "T", "https://example.test/x", 0, "text", 1f);
  }

  private AnswerService service(boolean hierarchical) throws Exception {
    var properties = mock(ContextCrateProperties.class);
    var answering = mock(ContextCrateProperties.Answering.class);
    when(properties.answering()).thenReturn(answering);
    when(answering.contextTokenBudget()).thenReturn(4_000);
    when(answering.maxHistoryMessages()).thenReturn(10);
    when(chunks.findByIdAndCrateId(any(), eq(crateId))).thenReturn(java.util.Optional.empty());
    when(chunks.findByDocumentIdAndCrateIdAndOrdinalBetweenOrderByOrdinal(documentId, crateId, 2, 4)).thenReturn(List.of(inSection));
    when(sections.findByCrateIdAndIdIn(eq(crateId), any())).thenReturn(List.of(
        new SectionSummary(sectionId, crateId, documentId, "Ops > Pager", 2, 4, null, null, "f", "m", "summary")));
    var rag = new RagSettings(crateId, false, true, true, true, false, false, "revise-once", "hybrid", 8);
    rag.updateHierarchical(hierarchical, 3);
    when(settings.current(crateId)).thenReturn(rag);
    var provider = mock(AnswerGenerationProvider.class);
    when(provider.available()).thenReturn(true);
    var service = new AnswerService(index, chunks, provider, mock(AuditLogRepository.class), properties, settings);
    service.setHierarchical(new HierarchicalRetrievalService(index, sections, chunks));
    return service;
  }

  private AnswerService.Request request() {
    return new AnswerService.Request(crateId, "Who is on call?", null, "chunk", null, null, List.of());
  }

  @Test
  void searchesSummariesFirstThenOnlyTheChunksOfTheFoundSections() throws Exception {
    when(index.search(any())).thenAnswer(i -> {
      SearchIndex.SearchRequest r = i.getArgument(0);
      return new SearchIndex.SearchResults("q", "hybrid", List.of("summary".equals(r.kind()) ? hit(sectionId, "summary") : hit(inSection.getId(), "chunk")));
    });

    var prepared = service(true).prepare(request());

    var searches = ArgumentCaptor.forClass(SearchIndex.SearchRequest.class);
    verify(index, times(2)).search(searches.capture());
    assertThat(searches.getAllValues().get(0).kind()).isEqualTo("summary");
    assertThat(searches.getAllValues().get(0).limit()).isEqualTo(3);
    assertThat(searches.getAllValues().get(0).chunkIds()).isNull();
    assertThat(searches.getAllValues().get(1).kind()).isEqualTo("chunk");
    assertThat(searches.getAllValues().get(1).chunkIds()).containsExactly(inSection.getId());
    assertThat(prepared.sources()).extracting(AnswerService.Source::chunkId).containsExactly(inSection.getId());
  }

  @Test
  void searchesAllChunksWhenNoSectionIsFound() throws Exception {
    when(index.search(any())).thenAnswer(i -> {
      SearchIndex.SearchRequest r = i.getArgument(0);
      return new SearchIndex.SearchResults("q", "hybrid", "summary".equals(r.kind()) ? List.of() : List.of(hit(UUID.randomUUID(), "chunk")));
    });

    var prepared = service(true).prepare(request());

    var searches = ArgumentCaptor.forClass(SearchIndex.SearchRequest.class);
    verify(index, times(2)).search(searches.capture());
    assertThat(searches.getAllValues().get(1).chunkIds()).isNull();
    assertThat(prepared.sources()).hasSize(1);
  }

  @Test
  void searchesAllChunksWhenTheNarrowedSearchFindsNothing() throws Exception {
    when(index.search(any())).thenAnswer(i -> {
      SearchIndex.SearchRequest r = i.getArgument(0);
      if ("summary".equals(r.kind())) return new SearchIndex.SearchResults("q", "hybrid", List.of(hit(sectionId, "summary")));
      return new SearchIndex.SearchResults("q", "hybrid", r.chunkIds() != null ? List.of() : List.of(hit(UUID.randomUUID(), "chunk")));
    });

    var prepared = service(true).prepare(request());

    verify(index, times(3)).search(any());
    assertThat(prepared.sources()).hasSize(1);
  }

  @Test
  void failedSummarySearchFallsBackToTheNormalSearch() throws Exception {
    when(index.search(any())).thenAnswer(i -> {
      SearchIndex.SearchRequest r = i.getArgument(0);
      if ("summary".equals(r.kind())) throw new java.io.IOException("down");
      return new SearchIndex.SearchResults("q", "hybrid", List.of(hit(UUID.randomUUID(), "chunk")));
    });

    assertThat(service(true).prepare(request()).sources()).hasSize(1);
  }

  @Test
  void doesNothingWhenDisabled() throws Exception {
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("q", "hybrid", List.of(hit(UUID.randomUUID(), "chunk"))));

    service(false).prepare(request());

    var searches = ArgumentCaptor.forClass(SearchIndex.SearchRequest.class);
    verify(index).search(searches.capture());
    assertThat(searches.getValue().kind()).isEqualTo("chunk");
    assertThat(searches.getValue().chunkIds()).isNull();
    verifyNoInteractions(sections);
  }
}
