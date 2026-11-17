package tech.wenisch.contextcrate.feedback;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.repository.AnswerFeedbackRepository;
import tech.wenisch.contextcrate.repository.RagSettingsRepository;

/** Periodically indexes approved feedback per crate (interval is a crate setting) and removes expired, unrated links. */
@Component
public class FeedbackEnrichmentJob {
  private static final Logger log = LoggerFactory.getLogger(FeedbackEnrichmentJob.class);
  private final RagSettingsRepository settings;
  private final FeedbackEnrichmentService enrichment;
  private final AnswerFeedbackRepository feedback;
  private final ContextCrateProperties properties;

  public FeedbackEnrichmentJob(RagSettingsRepository settings, FeedbackEnrichmentService enrichment, AnswerFeedbackRepository feedback, ContextCrateProperties properties) {
    this.settings = settings; this.enrichment = enrichment; this.feedback = feedback; this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${contextcrate.feedback.enrichment-poll-ms:60000}", initialDelayString = "${contextcrate.feedback.enrichment-poll-ms:60000}")
  public void run() {
    if (!("all".equals(properties.role()) || "control-plane".equals(properties.role()))) return;
    Instant now = Instant.now();
    try { feedback.deleteExpiredUnrated(now); } catch (RuntimeException e) { log.warn("Feedback cleanup failed: {}", e.toString()); }
    for (var crate : settings.findByFeedbackEnrichmentEnabledTrue()) {
      try {
        Instant due = now.minus(Duration.ofHours(crate.getFeedbackEnrichmentIntervalHours()));
        if (settings.claimFeedbackEnrichment(crate.getCrateId(), now, due) == 0) continue;
        int created = enrichment.enrich(crate.getCrateId());
        if (created > 0) log.info("Indexed {} rated answers. crateId={}", created, crate.getCrateId());
      } catch (RuntimeException e) {
        log.warn("Feedback enrichment failed. crateId={}: {}", crate.getCrateId(), e.toString());
      }
    }
  }
}
