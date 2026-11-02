package tech.wenisch.contextcrate.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.EvaluationCase;
import tech.wenisch.contextcrate.domain.EvaluationSettings;
import tech.wenisch.contextcrate.repository.EvaluationCaseRepository;
import tech.wenisch.contextcrate.repository.EvaluationSettingsRepository;

class EvaluationSweeperTest {
  private final UUID crateId = UUID.randomUUID();
  private final EvaluationCaseRepository cases = mock(EvaluationCaseRepository.class);
  private final EvaluationSettingsService settings = mock(EvaluationSettingsService.class);
  private final EvaluationSettingsRepository settingsRepository = mock(EvaluationSettingsRepository.class);
  private final EvaluationSweeper sweeper = new EvaluationSweeper(cases, settings, settingsRepository);

  @Test
  void deletesSnapshotsThatWereNeverEvaluated() {
    var stale = new EvaluationCase(UUID.randomUUID(), crateId, EvaluationCase.Source.LIVE, null, "q", "a", "{}");
    when(cases.findByStatusAndCreatedAtBefore(eq(EvaluationCase.Status.PENDING), any(Instant.class))).thenReturn(List.of(stale));
    when(cases.findByQuestionIsNotNullAndStatusNot(any())).thenReturn(List.of());
    when(settingsRepository.findAll()).thenReturn(List.of());

    sweeper.sweep();

    verify(cases).deleteAll(List.of(stale));
  }

  @Test
  void removesTextFromFinishedCasesWhenLoggingIsOff() {
    var finished = new EvaluationCase(UUID.randomUUID(), crateId, EvaluationCase.Source.LIVE, null, "secret", "secret", "{}");
    finished.done("m", "{}", null);
    var config = new EvaluationSettings(crateId); // logging off by default
    when(cases.findByStatusAndCreatedAtBefore(any(), any())).thenReturn(List.of());
    when(cases.findByQuestionIsNotNullAndStatusNot(EvaluationCase.Status.PENDING)).thenReturn(List.of(finished));
    when(settings.current(crateId)).thenReturn(config);
    when(settingsRepository.findAll()).thenReturn(List.of());

    sweeper.sweep();

    assertThat(finished.hasText()).isFalse();
    verify(cases).save(finished);
  }

  @Test
  void keepsTextWhileLoggingIsOnAndAppliesTheRetentionPeriod() {
    var logged = new EvaluationCase(UUID.randomUUID(), crateId, EvaluationCase.Source.LIVE, null, "kept", "kept", "{}");
    logged.done("m", "{}", null);
    var config = new EvaluationSettings(crateId);
    config.update(true, 1.0, 5, 20, true, true, true, true, true, 7);
    when(cases.findByStatusAndCreatedAtBefore(any(), any())).thenReturn(List.of());
    when(cases.findByQuestionIsNotNullAndStatusNot(any())).thenReturn(List.of(logged));
    when(settings.current(crateId)).thenReturn(config);
    when(settingsRepository.findAll()).thenReturn(List.of(config));

    sweeper.sweep();

    assertThat(logged.getQuestion()).isEqualTo("kept");
    verify(cases).deleteOlderThan(eq(crateId), any(Instant.class));
  }
}
