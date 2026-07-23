package tech.wenisch.contextcrate.queue;

import java.time.Instant;
import java.util.UUID;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;

public record PipelineMessage(
    UUID id,
    UUID crateId,
    int schemaVersion,
    WorkStage stage,
    String payload,
    UUID correlationId,
    String idempotencyKey,
    int priority,
    int attempts,
    Instant createdAt) {
  public PipelineMessage(
      UUID id, int schemaVersion, WorkStage stage, String payload, UUID correlationId,
      String idempotencyKey, int priority, int attempts, Instant createdAt) {
    this(id, tech.wenisch.contextcrate.domain.CrateIds.LEGACY, schemaVersion, stage, payload,
        correlationId, idempotencyKey, priority, attempts, createdAt);
  }

  public static PipelineMessage create(
      WorkStage stage, String payload, UUID correlationId, String idempotencyKey, int priority) {
    return create(tech.wenisch.contextcrate.domain.CrateIds.LEGACY, stage, payload, correlationId,
        idempotencyKey, priority);
  }

  public static PipelineMessage create(
      UUID crateId, WorkStage stage, String payload, UUID correlationId,
      String idempotencyKey, int priority) {
    return new PipelineMessage(
        UUID.randomUUID(),
        crateId,
        2,
        stage,
        payload,
        correlationId,
        idempotencyKey,
        priority,
        0,
        Instant.now());
  }
}
