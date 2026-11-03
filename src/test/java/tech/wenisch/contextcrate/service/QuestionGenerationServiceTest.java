package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.answer.AnswerGenerationProvider;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.domain.ChunkQuestion;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.repository.ChunkQuestionRepository;
import tech.wenisch.contextcrate.storage.Hashing;

class QuestionGenerationServiceTest {
  private final UUID crateId = UUID.randomUUID();
  private final AnswerGenerationProvider llm = mock(AnswerGenerationProvider.class);
  private final ChunkQuestionRepository repository = mock(ChunkQuestionRepository.class);
  private final RuntimeProviderSettings providers = mock(RuntimeProviderSettings.class);
  private final List<ChunkQuestion> stored = new ArrayList<>();
  private final QuestionGenerationService service = new QuestionGenerationService(llm, repository, providers);

  QuestionGenerationServiceTest() throws Exception {
    when(providers.effectiveAnswer()).thenReturn(new RuntimeProviderSettings.Answering(true, "http://llm", "model-a", "", "", 30, 0.2, 800));
    when(llm.available()).thenReturn(true);
    when(repository.findByChunkIdOrderByOrdinal(any())).thenAnswer(i -> stored.stream().filter(q -> q.getChunkId().equals(i.getArgument(0))).toList());
    doAnswer(i -> { stored.removeIf(q -> q.getChunkId().equals(i.getArgument(0))); return null; }).when(repository).deleteByChunkId(any());
    when(repository.saveAll(any())).thenAnswer(i -> { Iterable<ChunkQuestion> rows = i.getArgument(0); rows.forEach(stored::add); return List.copyOf(stored); });
  }

  private DocumentChunk chunk(String text) {
    var c = new DocumentChunk(UUID.randomUUID(), UUID.randomUUID(), 0, "Guide", text, Hashing.sha256(text));
    c.assignCrate(crateId);
    return c;
  }

  private static final String TEXT = "Engineers hand the pager over every Monday morning at nine o'clock.";

  @Test
  void parsesDeduplicatesAndCapsAtTheRequestedNumber() throws Exception {
    when(llm.complete(any(), anyDouble())).thenReturn("```json\n{\"questions\":[\"When is the handover?\",\"when is the handover?\",\"\",\"Who holds the pager?\",\"What happens on Monday?\",\"Why nine o'clock?\"]}\n```");
    var chunk = chunk(TEXT);

    var result = service.questionsFor(crateId, List.of(chunk), 3);

    assertThat(result.get(chunk.getId())).containsExactly("When is the handover?", "Who holds the pager?", "What happens on Monday?");
    assertThat(stored).hasSize(3);
  }

  @Test
  void aSecondRunIsServedFromTheCacheAndAChangedCountRegenerates() throws Exception {
    when(llm.complete(any(), anyDouble())).thenReturn("{\"questions\":[\"One?\",\"Two?\",\"Three?\",\"Four?\"]}");
    var chunk = chunk(TEXT);

    service.questionsFor(crateId, List.of(chunk), 3);
    var again = service.questionsFor(crateId, List.of(chunk), 3);
    verify(llm, times(1)).complete(any(), anyDouble());
    assertThat(again.get(chunk.getId())).containsExactly("One?", "Two?", "Three?");

    service.questionsFor(crateId, List.of(chunk), 4);
    verify(llm, times(2)).complete(any(), anyDouble());
    assertThat(stored).hasSize(4);
  }

  @Test
  void failuresLeaveTheChunkWithoutQuestionsAndCacheNothing() throws Exception {
    when(llm.complete(any(), anyDouble())).thenReturn("not json at all").thenThrow(new IllegalStateException("down"));
    var first = chunk(TEXT);
    var second = chunk(TEXT + " More text.");

    var result = service.questionsFor(crateId, List.of(first, second), 3);

    assertThat(result.get(first.getId()).size() + result.get(second.getId()).size()).isZero();
    assertThat(stored).isEmpty();
  }

  @Test
  void shortAndImageChunksAreSkippedWithoutCallingTheModel() throws Exception {
    var tiny = chunk("Too short.");
    var image = chunk("Image file diagram.png with a long enough description to pass the length check.");
    image.describe("image", null, null, null, null);

    var result = service.questionsFor(crateId, List.of(tiny, image), 5);

    assertThat(result.get(tiny.getId())).isEmpty();
    assertThat(result.get(image.getId())).isEmpty();
    verify(llm, never()).complete(any(), anyDouble());
  }

  @Test
  void anUnavailableModelIsFailOpen() throws Exception {
    when(llm.available()).thenReturn(false);
    var chunk = chunk(TEXT);
    assertThat(service.questionsFor(crateId, List.of(chunk), 5).get(chunk.getId())).isEmpty();
  }

  @Test
  void settingsAcceptThreeToFiftyQuestions() {
    var repo = mock(tech.wenisch.contextcrate.repository.RagSettingsRepository.class);
    var stored = new tech.wenisch.contextcrate.domain.RagSettings(crateId, false, true, true, true, true, true, "revise-once", "hybrid", 8);
    when(repo.findById(crateId)).thenReturn(Optional.of(stored));
    when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    var properties = mock(tech.wenisch.contextcrate.config.ContextCrateProperties.class);
    when(properties.answering()).thenReturn(mock(tech.wenisch.contextcrate.config.ContextCrateProperties.Answering.class));
    var settings = new tech.wenisch.contextcrate.answer.RagSettingsService(repo, properties);

    assertThat(stored.getEnrichmentQuestions()).isEqualTo(5);
    assertThat(stored.isEnrichmentEnabled()).isFalse();
    assertThat(settings.updateEnrichment(crateId, true, 50).getEnrichmentQuestions()).isEqualTo(50);
    assertThat(settings.updateEnrichment(crateId, true, 3).getEnrichmentQuestions()).isEqualTo(3);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings.updateEnrichment(crateId, true, 2)).isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings.updateEnrichment(crateId, true, 51)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("3 and 50");
  }
}
