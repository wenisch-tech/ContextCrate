package tech.wenisch.contextcrate.service;

import static tech.wenisch.contextcrate.domain.PipelineTypes.*;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.web.CrateLiveSnapshot;

@Service
public class CrateLiveViewService {
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final SourceRepository sources;
  private final IngestionJobRepository jobs;
  private final IngestionRunRepository runs;
  private final PipelineWorkItemRepository work;
  private final SourceItemRepository frontier;
  private final AcquisitionRecordRepository acquisitions;
  private final SearchIndex index;

  public CrateLiveViewService(NormalizedDocumentRepository documents,
      DocumentChunkRepository chunks, SourceRepository sources, IngestionJobRepository jobs,
      IngestionRunRepository runs, PipelineWorkItemRepository work,
      SourceItemRepository frontier, AcquisitionRecordRepository acquisitions, SearchIndex index) {
    this.documents = documents; this.chunks = chunks; this.sources = sources; this.jobs = jobs;
    this.runs = runs; this.work = work; this.frontier = frontier;
    this.acquisitions = acquisitions; this.index = index;
  }

  @Transactional(readOnly = true)
  public CrateLiveSnapshot snapshot(UUID crateId, UUID runId) {
    var metrics = new CrateLiveSnapshot.Metrics(
        documents.countByCrateIdAndCurrentVersionTrue(crateId), chunks.countByCrateId(crateId),
        sources.countByCrateId(crateId), jobs.countByCrateId(crateId),
        runs.countByCrateIdAndStatusIn(crateId, List.of(RunStatus.RUNNING, RunStatus.PAUSED)),
        work.countByCrateIdAndStatusIn(crateId, List.of(WorkStatus.FAILED, WorkStatus.DEAD_LETTERED)),
        documents.countByCrateIdAndCurrentVersionTrueAndIndexedFalse(crateId));

    Map<WorkStage, Map<WorkStatus, Long>> pipeline = new EnumMap<>(WorkStage.class);
    for (WorkStage stage : WorkStage.values()) {
      Map<WorkStatus, Long> statuses = new EnumMap<>(WorkStatus.class);
      for (WorkStatus status : WorkStatus.values()) statuses.put(status, 0L);
      pipeline.put(stage, statuses);
    }
    for (var count : work.countsByCrate(crateId))
      pipeline.get(count.getStage()).put(count.getStatus(), count.getTotal());

    List<IngestionRun> recentRuns = runs.findTop20ByCrateIdOrderByStartedAtDesc(crateId);
    Map<UUID, String> sourceNames = new HashMap<>();
    sources.findAllById(recentRuns.stream().map(IngestionRun::getSourceId).distinct().toList())
        .forEach(value -> sourceNames.put(value.getId(), value.getName()));
    Map<UUID, String> jobNames = new HashMap<>();
    jobs.findAllById(recentRuns.stream().map(IngestionRun::getIngestionJobId).distinct().toList())
        .forEach(value -> jobNames.put(value.getId(), value.getName()));
    Map<UUID, List<PipelineWorkItem>> workByRun = recentRuns.isEmpty() ? Map.of() : work
        .findByCrateIdAndCorrelationIdInOrderByUpdatedAtDesc(crateId,
            recentRuns.stream().map(IngestionRun::getId).toList()).stream()
        .collect(java.util.stream.Collectors.groupingBy(PipelineWorkItem::getCorrelationId));
    List<CrateLiveSnapshot.RunSummary> summaries = recentRuns.stream()
        .map(value -> summary(value, sourceNames, jobNames,
            workByRun.getOrDefault(value.getId(), List.of()))).toList();

    CrateLiveSnapshot.RunDetail detail = runId == null ? null
        : detail(crateId, runId, sourceNames, jobNames, workByRun.get(runId));
    var health = index.health(crateId);
    long version = Objects.hash(metrics, health, pipeline, summaries, detail);
    return new CrateLiveSnapshot(crateId, Instant.now(), version, metrics, health, pipeline,
        summaries, detail);
  }

  private CrateLiveSnapshot.RunDetail detail(UUID crateId, UUID runId,
      Map<UUID, String> sourceNames, Map<UUID, String> jobNames,
      List<PipelineWorkItem> knownWork) {
    IngestionRun value = runs.findByIdAndCrateId(runId, crateId).orElseThrow();
    var fetched = acquisitions.findTop100ByRunIdOrderByFetchedAtDesc(runId).stream()
        .map(item -> new CrateLiveSnapshot.Acquisition(item.getId(), item.getRequestedLocator(),
            item.getOutcome(), item.getStatusCode(), item.getDurationMs(), item.getFetchedAt(),
            item.getErrorMessage())).toList();
    List<PipelineWorkItem> rawWork = knownWork == null
        ? work.findTop100ByCorrelationIdOrderByUpdatedAtDesc(runId) : knownWork.stream().limit(100).toList();
    var items = rawWork.stream()
        .map(item -> new CrateLiveSnapshot.WorkItem(item.getId(), item.getStage(), item.getStatus(),
            item.getAttempts(), item.getUpdatedAt(), item.getLastError())).toList();
    return new CrateLiveSnapshot.RunDetail(summary(value, sourceNames, jobNames, rawWork),
        frontier.countByRunId(runId), frontier.countByRunIdAndStatus(runId, FrontierStatus.FETCHED),
        frontier.countByRunIdAndStatus(runId, FrontierStatus.FAILED), fetched, items);
  }

  private CrateLiveSnapshot.RunSummary summary(IngestionRun value,
      Map<UUID, String> sourceNames, Map<UUID, String> jobNames,
      List<PipelineWorkItem> runWork) {
    WorkStage stage = runWork.stream()
        .filter(item -> item.getStatus() == WorkStatus.PROCESSING
            || item.getStatus() == WorkStatus.RETRY_WAITING || item.getStatus() == WorkStatus.PENDING)
        .map(PipelineWorkItem::getStage).findFirst().orElse(null);
    return new CrateLiveSnapshot.RunSummary(value.getId(), value.getSourceId(),
        sourceNames.getOrDefault(value.getSourceId(), "Unknown source"),
        jobNames.getOrDefault(value.getIngestionJobId(), "Unknown job"), value.getStatus(), stage,
        value.getStartedAt(), value.getFinishedAt());
  }
}
