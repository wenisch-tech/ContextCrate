package tech.wenisch.contextcrate.service;

import static tech.wenisch.contextcrate.domain.PipelineTypes.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.web.CrateLiveSnapshot;

@Service
public class CrateLiveViewService {
  private static final Logger log = LoggerFactory.getLogger(CrateLiveViewService.class);
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final SourceRepository sources;
  private final IngestionJobRepository jobs;
  private final IngestionRunRepository runs;
  private final PipelineWorkItemRepository work;
  private final SourceItemRepository frontier;
  private final AcquisitionRecordRepository acquisitions;
  private final SearchIndex index;
  private final Map<UUID, CachedAnalytics> analyticsCache = new java.util.concurrent.ConcurrentHashMap<>();

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
    var analytics = analytics(crateId);
    long version = Objects.hash(metrics, health, pipeline, summaries, detail, analytics);
    return new CrateLiveSnapshot(crateId, Instant.now(), version, metrics, health, pipeline,
        summaries, detail, analytics);
  }

  private CrateLiveSnapshot.DashboardAnalytics analytics(UUID crateId) {
    Instant now = Instant.now();
    CachedAnalytics cached = analyticsCache.get(crateId);
    if (cached != null && cached.expiresAt().isAfter(now)) return cached.value();
    CrateLiveSnapshot.DashboardAnalytics value;
    try {
      value = loadAnalytics(crateId, now);
    } catch (RuntimeException exception) {
      // Historical charts are supplementary. A migration edge case or temporary database issue
      // must never prevent users from opening the operational overview.
      log.warn("Could not load dashboard history for crate {}", crateId, exception);
      value = emptyAnalytics(now);
    }
    analyticsCache.put(crateId, new CachedAnalytics(now.plusSeconds(30), value));
    return value;
  }

  private CrateLiveSnapshot.DashboardAnalytics emptyAnalytics(Instant now) {
    var empty = activity(0, List.of(), now.truncatedTo(ChronoUnit.HOURS));
    return new CrateLiveSnapshot.DashboardAnalytics(empty, empty, empty);
  }

  private CrateLiveSnapshot.DashboardAnalytics loadAnalytics(UUID crateId, Instant now) {
    // End at the beginning of the current hour so both periods contain exactly 24 complete hours.
    Instant currentHour = now.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
    Instant since = currentHour.minus(47, ChronoUnit.HOURS);
    var created = documents.findByCrateIdAndCurrentVersionTrueAndCreatedAtGreaterThanEqual(crateId, since);
    var indexed = documents.findByCrateIdAndIndexedAtGreaterThanEqual(crateId, since);
    var monitored = runs.findByCrateIdAndStartedAtGreaterThanEqual(crateId, since);
    return new CrateLiveSnapshot.DashboardAnalytics(
        activity(documents.countByCrateIdAndCurrentVersionTrue(crateId), created.stream()
            .map(NormalizedDocument::getCreatedAt).toList(), currentHour),
        sourceActivity(sources.countByCrateIdAndEnabledTrue(crateId), monitored, currentHour),
        activity(0, indexed.stream().map(NormalizedDocument::getIndexedAt).toList(), currentHour));
  }

  private CrateLiveSnapshot.Activity activity(long total, List<Instant> events, Instant currentHour) {
    long[] buckets = new long[48];
    Instant firstHour = currentHour.minus(47, ChronoUnit.HOURS);
    for (Instant event : events) addToBucket(buckets, firstHour, event);
    return toActivity(total, buckets, currentHour);
  }

  private CrateLiveSnapshot.Activity sourceActivity(long total, List<IngestionRun> runs,
      Instant currentHour) {
    @SuppressWarnings("unchecked") Set<UUID>[] buckets = new Set[48];
    for (int i = 0; i < buckets.length; i++) buckets[i] = new HashSet<>();
    Instant firstHour = currentHour.minus(47, ChronoUnit.HOURS);
    for (IngestionRun run : runs) {
      long index = ChronoUnit.HOURS.between(firstHour, run.getStartedAt().truncatedTo(ChronoUnit.HOURS));
      if (index >= 0 && index < buckets.length) buckets[(int) index].add(run.getSourceId());
    }
    long[] values = Arrays.stream(buckets).mapToLong(Set::size).toArray();
    return toActivity(total, values, currentHour);
  }

  private static void addToBucket(long[] buckets, Instant firstHour, Instant event) {
    if (event == null) return;
    long index = ChronoUnit.HOURS.between(firstHour, event.truncatedTo(ChronoUnit.HOURS));
    if (index >= 0 && index < buckets.length) buckets[(int) index]++;
  }

  private static CrateLiveSnapshot.Activity toActivity(long total, long[] buckets,
      Instant currentHour) {
    long previous = Arrays.stream(buckets, 0, 24).sum();
    long current = Arrays.stream(buckets, 24, 48).sum();
    long maximum = Math.max(1, Arrays.stream(buckets, 24, 48).max().orElse(0));
    List<CrateLiveSnapshot.HourlyPoint> hourly = new ArrayList<>();
    for (int i = 24; i < 48; i++) {
      Instant hour = currentHour.minus(47 - i, ChronoUnit.HOURS);
      hourly.add(new CrateLiveSnapshot.HourlyPoint(hour.toString().substring(11, 16), buckets[i],
          buckets[i] == 0 ? 3 : Math.max(8, (int) Math.round(buckets[i] * 100d / maximum))));
    }
    int change = previous == 0 ? (current == 0 ? 0 : 100) : (int) Math.round((current - previous) * 100d / previous);
    return new CrateLiveSnapshot.Activity(total, current, previous, change, hourly);
  }

  private record CachedAnalytics(Instant expiresAt, CrateLiveSnapshot.DashboardAnalytics value) {}

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
