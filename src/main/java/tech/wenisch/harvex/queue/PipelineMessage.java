package tech.wenisch.harvex.queue;

import java.time.Instant;
import java.util.UUID;
import tech.wenisch.harvex.domain.PipelineTypes.WorkStage;

public record PipelineMessage(
    UUID id,
    int schemaVersion,
    WorkStage stage,
    String payload,
    UUID correlationId,
    String idempotencyKey,
    int priority,
    int attempts,
    Instant createdAt) {
  public static PipelineMessage create(
      WorkStage stage, String payload, UUID correlationId, String idempotencyKey, int priority) {
    return new PipelineMessage(
        UUID.randomUUID(),
        1,
        stage,
        payload,
        correlationId,
        idempotencyKey,
        priority,
        0,
        Instant.now());
  }
}
