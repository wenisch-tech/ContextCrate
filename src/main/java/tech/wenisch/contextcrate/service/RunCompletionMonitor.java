package tech.wenisch.contextcrate.service;

import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.PipelineTypes.*;
import tech.wenisch.contextcrate.repository.*;

@Component
public class RunCompletionMonitor {
  private final IngestionRunRepository runs;
  private final SourceItemRepository frontier;
  private final PipelineWorkItemRepository work;

  public RunCompletionMonitor(
      IngestionRunRepository runs, SourceItemRepository frontier, PipelineWorkItemRepository work) {
    this.runs = runs;
    this.frontier = frontier;
    this.work = work;
  }

  private tech.wenisch.contextcrate.metrics.CrateMetrics cm = tech.wenisch.contextcrate.metrics.CrateMetrics.noop();

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setMetrics(tech.wenisch.contextcrate.metrics.CrateMetrics metrics) {
    this.cm = metrics;
  }

  private void finished(tech.wenisch.contextcrate.domain.IngestionRun run, String status) {
    cm.count("ingestion.runs", run.getCrateId(), "status", status);
    if (run.getStartedAt() != null)
      cm.time("ingestion.run.duration", run.getCrateId(),
          java.time.Duration.between(run.getStartedAt(), java.time.Instant.now()).toNanos(),
          "status", status);
  }

  @Scheduled(fixedDelay = 1000)
  @Transactional
  public void completeFinishedRuns() {
    for (var run : runs.findTop20ByOrderByStartedAtDesc())
      if (run.getStatus() == RunStatus.RUNNING && frontier.countByRunId(run.getId()) > 0) {
        if (work.countByCorrelationIdAndStatus(run.getId(), WorkStatus.DEAD_LETTERED) > 0) {
          // A dead-lettered parse/index task used to be treated as "no remaining work", causing
          // a run with unindexed documents to be shown as successfully completed.
          run.status(RunStatus.FAILED);
          runs.save(run);
          finished(run, "FAILED");
        } else if (work.countByCorrelationIdAndStatusIn(
                run.getId(),
                List.of(WorkStatus.PENDING, WorkStatus.PROCESSING, WorkStatus.RETRY_WAITING))
            == 0) {
          run.status(RunStatus.COMPLETED);
          runs.save(run);
          finished(run, "COMPLETED");
        }
      }
  }
}
