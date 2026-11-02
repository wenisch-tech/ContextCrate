package tech.wenisch.contextcrate.evaluation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.EvaluationCase;
import tech.wenisch.contextcrate.repository.EvaluationCaseRepository;

/**
 * Keeps the promise that text only stays as long as it is needed: snapshots that were never
 * evaluated are deleted after an hour, finished cases lose their text when logging is off, and
 * every case is deleted after the crate's retention period.
 */
@Component
public class EvaluationSweeper {
  private static final Logger log = LoggerFactory.getLogger(EvaluationSweeper.class);
  static final Duration PENDING_TTL = Duration.ofHours(1);

  private final EvaluationCaseRepository cases;
  private final EvaluationSettingsService settings;
  private final tech.wenisch.contextcrate.repository.EvaluationSettingsRepository settingsRepository;

  public EvaluationSweeper(EvaluationCaseRepository cases, EvaluationSettingsService settings,
      tech.wenisch.contextcrate.repository.EvaluationSettingsRepository settingsRepository) {
    this.cases = cases; this.settings = settings; this.settingsRepository = settingsRepository;
  }

  @Scheduled(fixedDelayString = "${contextcrate.evaluation.sweep-interval-ms:300000}", initialDelayString = "${contextcrate.evaluation.sweep-interval-ms:300000}")
  @Transactional
  public void sweep() {
    try {
      Instant now = Instant.now();
      List<EvaluationCase> stale = cases.findByStatusAndCreatedAtBefore(EvaluationCase.Status.PENDING, now.minus(PENDING_TTL));
      if (!stale.isEmpty()) cases.deleteAll(stale);
      // Finished cases that still carry text although logging is off (for example after the switch was turned off).
      for (EvaluationCase c : cases.findByQuestionIsNotNullAndStatusNot(EvaluationCase.Status.PENDING)) {
        if (!settings.current(c.getCrateId()).isLogQuestions()) { c.scrub(); cases.save(c); }
      }
      // The retention period applies to whole cases, text and scores alike.
      for (var config : settingsRepository.findAll())
        cases.deleteOlderThan(config.getCrateId(), now.minus(Duration.ofDays(config.getRetentionDays())));
    } catch (RuntimeException e) {
      log.warn("Evaluation sweep failed: {}", e.getMessage());
    }
  }
}
