package tech.wenisch.contextcrate.service;

import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.PipelineTypes.*;
import tech.wenisch.contextcrate.repository.*;

@Component
public class RunCompletionMonitor {
  private final CrawlRunRepository runs;
  private final FrontierEntryRepository frontier;
  private final PipelineWorkItemRepository work;

  public RunCompletionMonitor(
      CrawlRunRepository runs, FrontierEntryRepository frontier, PipelineWorkItemRepository work) {
    this.runs = runs;
    this.frontier = frontier;
    this.work = work;
  }

  @Scheduled(fixedDelay = 1000)
  @Transactional
  public void completeFinishedRuns() {
    for (var run : runs.findTop20ByOrderByStartedAtDesc())
      if (run.getStatus() == RunStatus.RUNNING
          && frontier.countByRunId(run.getId()) > 0
          && work.countByCorrelationIdAndStatusIn(
                  run.getId(),
                  List.of(WorkStatus.PENDING, WorkStatus.PROCESSING, WorkStatus.RETRY_WAITING))
              == 0) {
        run.status(RunStatus.COMPLETED);
        runs.save(run);
      }
  }
}
