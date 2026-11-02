package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.evaluation.EvaluationCollector;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;

/** The evaluation hand-over sees the final answer and can never change or break it. */
class AnswerServiceEvaluationHookTest {
  private final UUID crateId = UUID.randomUUID();
  private final AnswerGenerationProvider provider = mock(AnswerGenerationProvider.class);
  private final EvaluationCollector collector = mock(EvaluationCollector.class);
  private AnswerService service;

  @BeforeEach
  void setUp() throws Exception {
    var properties = mock(ContextCrateProperties.class);
    when(properties.answering()).thenReturn(mock(ContextCrateProperties.Answering.class));
    doAnswer(invocation -> { ((Consumer<String>) invocation.getArgument(1)).accept("Generated answer."); return null; })
        .when(provider).stream(any(), any());
    service = new AnswerService(mock(SearchIndex.class), mock(DocumentChunkRepository.class), provider,
        mock(AuditLogRepository.class), properties, mock(RagSettingsService.class));
    service.setEvaluation(collector);
  }

  private AnswerService.Prepared prepared(boolean verification) {
    return new AnswerService.Prepared(crateId, "Question?", List.of(), "hybrid", List.of(), "alice", false, true, true, verification, "revise-once");
  }

  @Test
  void theFinalAnswerIsHandedOver() throws Exception {
    var prepared = prepared(false);

    var result = service.generate(prepared);

    assertThat(result.text()).isEqualTo("Generated answer.");
    verify(collector).offer(prepared, "Generated answer.");
  }

  @Test
  void aRevisedAnswerIsEvaluatedNotTheOriginalDraft() throws Exception {
    when(provider.complete(any())).thenReturn("no", "Revised answer.");
    var prepared = prepared(true);

    service.generate(prepared);

    verify(collector).offer(prepared, "Revised answer.");
  }

  @Test
  void aFailingCollectorDoesNotAffectTheAnswer() throws Exception {
    doThrow(new IllegalStateException("boom")).when(collector).offer(any(), any());

    var result = service.generate(prepared(false));

    assertThat(result.text()).isEqualTo("Generated answer.");
  }

  @Test
  void withoutACollectorNothingHappens() throws Exception {
    service.setEvaluation(null);
    assertThat(service.generate(prepared(false)).text()).isEqualTo("Generated answer.");
  }
}
