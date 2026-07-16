package tech.wenisch.harvex.service;

import java.util.UUID;

public record PipelinePayload(UUID runId, UUID entityId) {}
