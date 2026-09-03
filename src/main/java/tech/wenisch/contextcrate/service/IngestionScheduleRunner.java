package tech.wenisch.contextcrate.service;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.IngestionJobMode;
import tech.wenisch.contextcrate.domain.PipelineTypes.RunStatus;
import tech.wenisch.contextcrate.repository.IngestionRunRepository;

@Component
public class IngestionScheduleRunner {
  private final IngestionService ingestion;
  private final IngestionRunRepository runs;
  private final SystemSettingsService settings;
  private final ContextCrateProperties properties;
  private final Clock clock;

  @Autowired
  public IngestionScheduleRunner(IngestionService ingestion, IngestionRunRepository runs,
      SystemSettingsService settings, ContextCrateProperties properties) {
    this(ingestion, runs, settings, properties, Clock.systemUTC());
  }

  IngestionScheduleRunner(IngestionService ingestion, IngestionRunRepository runs,
      SystemSettingsService settings, ContextCrateProperties properties, Clock clock) {
    this.ingestion = ingestion; this.runs = runs; this.settings = settings;
    this.properties = properties; this.clock = clock;
  }

  @Scheduled(cron = "0 * * * * *")
  public void startDueJobs() {
    if (!("all".equals(properties.role()) || "control-plane".equals(properties.role()))) return;
    ZoneId zone = settings.timeZone();
    var now = clock.instant().atZone(zone).withSecond(0).withNano(0);
    for (var job : ingestion.scheduledJobs()) {
      if (!job.isEnabled() || !IngestionSchedule.matches(job.getCronExpression(), now)) continue;
      if (runs.existsByIngestionJobIdAndStatus(job.getId(), RunStatus.RUNNING)) continue;
      try {
        ingestion.start(job.getCrateId(), job.getSourceId(), job.getId());
      } catch (IllegalStateException ignored) {
        // A source can be disabled between schedule evaluation and start; skip this occurrence.
      }
    }
  }
}
