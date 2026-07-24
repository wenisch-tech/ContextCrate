package tech.wenisch.contextcrate.service;

import java.util.UUID;

public record PipelinePayload(UUID crateId, UUID runId, UUID entityId) {
  public PipelinePayload(UUID runId, UUID entityId) {
    this(null, runId, entityId);
  }
}
