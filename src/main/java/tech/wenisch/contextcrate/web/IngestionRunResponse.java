package tech.wenisch.contextcrate.web;

import java.time.Instant;
import java.util.UUID;
import tech.wenisch.contextcrate.domain.*;

public record IngestionRunResponse(
    UUID id,
    UUID crateId,
    UUID sourceId,
    UUID ingestionJobId,
    PipelineTypes.RunStatus status,
    SourceConfiguration sourceConfiguration,
    IngestionConfiguration jobConfiguration,
    boolean tokenConfigured,
    String resolvedRevision,
    Instant startedAt,
    Instant finishedAt) {

  public static IngestionRunResponse from(IngestionRun run, SourceConfiguration source,
      IngestionConfiguration job) {
    boolean tokenConfigured = job.git() != null
        && job.git().token() != null
        && !job.git().token().isBlank();
    return new IngestionRunResponse(run.getId(), run.getCrateId(), run.getSourceId(),
        run.getIngestionJobId(), run.getStatus(), source.withoutSecrets(), job.withoutSecrets(), tokenConfigured,
        run.getResolvedRevision(), run.getStartedAt(), run.getFinishedAt());
  }
}
