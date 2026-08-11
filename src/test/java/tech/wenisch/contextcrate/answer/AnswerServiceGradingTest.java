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
import tech.wenisch.contextcrate.repository.RagSettingsRepository;

class AnswerServiceGradingTest {
  private final UUID crateId = UUID.randomUUID();
  private final SearchIndex index = mock(SearchIndex.class);
  private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private final RagSettingsService settings = mock(RagSettingsService.class);

  @Test
  void keepsOnlyChunksExplicitlyGradedRelevant() throws Exception {
    setup(true, false);
    when(provider.complete(any())).thenReturn("yes", "no");

    var prepared = service().prepare(new AnswerService.Request(crateId, "Where are backups documented?", null,
        "chunk", null, null, List.of()));

    assertThat(prepared.sources()).extracting(AnswerService.Source::content).containsExactly("backup chunk");
    verify(provider, times(2)).complete(any());
    var prompts = ArgumentCaptor.forClass(List.class);
    verify(provider, times(2)).complete(prompts.capture());
    assertThat(prompts.getAllValues().getFirst().toString())
        .contains("Where are backups documented?", "backup chunk", "Reply with exactly yes");
  }

  @Test
  void gradingIsSkippedWhenDisabled() throws Exception {
    setup(false, false);

    var prepared = service().prepare(new AnswerService.Request(crateId, "Where are backups documented?", null,
        "chunk", null, null, List.of()));

    assertThat(prepared.sources()).hasSize(2);
    verify(provider, never()).complete(any());
  }

  @Test
  void retainsChunkWhenGraderResponseIsAmbiguous() throws Exception {
    setup(true, false);
    when(provider.complete(any())).thenReturn("maybe", "no");

    var prepared = service().prepare(new AnswerService.Request(crateId, "Where are backups documented?", null,
        "chunk", null, null, List.of()));

    assertThat(prepared.sources()).extracting(AnswerService.Source::content).containsExactly("backup chunk");
  }

  @Test
  void strictGroundingHasNoSourcesWhenAllChunksAreRejected() throws Exception {
    setup(true, true);
    when(provider.complete(any())).thenReturn("no");

    var prepared = service().prepare(new AnswerService.Request(crateId, "Where are backups documented?", null,
        "chunk", null, null, List.of()));

    assertThat(prepared.sources()).isEmpty();
    assertThat(prepared.strictGrounding()).isTrue();
  }

  @Test
  void newCrateSettingsEnableGradingByDefault() {
    var repository = mock(RagSettingsRepository.class);
    var properties = mock(ContextCrateProperties.class);
    var answering = mock(ContextCrateProperties.Answering.class);
    when(properties.answering()).thenReturn(answering);
    when(answering.retrievalMode()).thenReturn("hybrid");
    when(answering.sourceLimit()).thenReturn(8);
    when(repository.findById(crateId)).thenReturn(java.util.Optional.empty());
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var value = new RagSettingsService(repository, properties).current(crateId);
    assertThat(value.isGradingEnabled()).isTrue();
    assertThat(value.isAnswerVerificationEnabled()).isTrue();
    assertThat(value.getAnswerVerificationFailureAction()).isEqualTo("revise-once");
  }

  private void setup(boolean grading, boolean strict) throws Exception {
    var first = new SearchIndex.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "chunk",
        "Backups", "https://example.test/backups", 0, "backup chunk", 1f);
    var second = new SearchIndex.SearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "chunk",
        "Other", "https://example.test/other", 1, "other chunk", .5f);
    when(index.search(any())).thenReturn(new SearchIndex.SearchResults("question", "hybrid", List.of(first, second)));
    when(chunks.findByIdAndCrateId(any(), eq(crateId))).thenReturn(java.util.Optional.empty());
    when(settings.current(crateId)).thenReturn(new RagSettings(crateId, strict, true, true, true, grading, true, "revise-once", "hybrid", 8));
  }

  private AnswerService service() {
    var properties = mock(ContextCrateProperties.class);
    var answering = mock(ContextCrateProperties.Answering.class);
    when(properties.answering()).thenReturn(answering);
    when(answering.contextTokenBudget()).thenReturn(4_000);
    when(answering.maxHistoryMessages()).thenReturn(10);
    return new AnswerService(index, chunks, provider, mock(AuditLogRepository.class), properties, settings);
  }
}
