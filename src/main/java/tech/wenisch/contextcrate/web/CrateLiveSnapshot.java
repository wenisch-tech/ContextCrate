package tech.wenisch.contextcrate.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tech.wenisch.contextcrate.domain.PipelineTypes.*;
import tech.wenisch.contextcrate.index.SearchIndex;

public record CrateLiveSnapshot(
    UUID crateId,
    Instant generatedAt,
    long version,
    Metrics metrics,
    SearchIndex.IndexHealth indexHealth,
    Map<WorkStage, Map<WorkStatus, Long>> pipeline,
    List<RunSummary> runs,
    RunDetail run) {

  public record Metrics(long documents, long chunks, long sources, long jobs, long activeRuns,
      long failedWork, long unindexedDocuments) {}

  public record RunSummary(UUID id, UUID sourceId, String source, String job, RunStatus status,
      WorkStage stage, Instant startedAt, Instant finishedAt) {}

  public record RunDetail(RunSummary summary, long discovered, long fetched, long failed,
      List<Acquisition> acquisitions, List<WorkItem> work) {}

  public record Acquisition(UUID id, String locator, FetchOutcome outcome, Integer statusCode,
      Long durationMs, Instant fetchedAt, String error) {}

  public record WorkItem(UUID id, WorkStage stage, WorkStatus status, int attempts,
      Instant updatedAt, String error) {}
}
