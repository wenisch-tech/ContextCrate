package tech.wenisch.contextcrate.graph;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.CrateIndexGeneration;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStatus;
import tech.wenisch.contextcrate.repository.CrateIndexGenerationRepository;
import tech.wenisch.contextcrate.repository.PipelineWorkItemRepository;

/**
 * Builds knowledge graphs in the background. A crate's graph is built when it is marked out of date (by extraction during
 * indexing, or the rebuild button) and its indexing is idle: no pending or running work items and no index rebuild.
 * Builds run on their own threads, in parallel to normal operation; the database claim makes sure only one instance builds
 * a crate at a time.
 */
@Component
public class GraphBuildJob {
  private static final Logger log = LoggerFactory.getLogger(GraphBuildJob.class);
  static final Duration STALE = Duration.ofHours(2);
  static final List<WorkStatus> BUSY = List.of(WorkStatus.PENDING, WorkStatus.PROCESSING, WorkStatus.RETRY_WAITING);
  private final GraphStore store;
  private final GraphBuildService builder;
  private final RagSettingsService settings;
  private final PipelineWorkItemRepository work;
  private final CrateIndexGenerationRepository generations;
  private final ContextCrateProperties properties;
  private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "graph-build"); t.setDaemon(true); return t; });

  public GraphBuildJob(GraphStore store, GraphBuildService builder, RagSettingsService settings, PipelineWorkItemRepository work,
      CrateIndexGenerationRepository generations, ContextCrateProperties properties) {
    this.store = store; this.builder = builder; this.settings = settings; this.work = work; this.generations = generations; this.properties = properties;
  }

  @jakarta.annotation.PreDestroy
  void shutdown() { pool.shutdownNow(); }

  @Scheduled(fixedDelayString = "${contextcrate.graph.build-poll-ms:15000}", initialDelayString = "${contextcrate.graph.build-poll-ms:15000}")
  public void run() {
    String role = properties.role();
    if (!("all".equals(role) || "control-plane".equals(role))) return;
    for (UUID crateId : store.dirtyCrates()) {
      try {
        if (!ready(crateId)) continue;
        Instant now = Instant.now();
        if (!store.claim(crateId, now, now.minus(STALE))) continue;
        pool.submit(() -> { try { builder.build(crateId); } catch (RuntimeException e) { log.debug("Graph build failed. crateId={}", crateId, e); } });
      } catch (RuntimeException e) {
        log.warn("Scheduling the knowledge graph build failed. crateId={}: {}", crateId, e.toString());
      }
    }
  }

  /** Extraction on and indexing idle. */
  boolean ready(UUID crateId) {
    if (!settings.current(crateId).isGraphExtractionEnabled()) return false;
    if (work.countByCrateIdAndStatusIn(crateId, BUSY) > 0) return false;
    return generations.findByCrateIdAndStatus(crateId, CrateIndexGeneration.Status.BUILDING).isEmpty();
  }
}
