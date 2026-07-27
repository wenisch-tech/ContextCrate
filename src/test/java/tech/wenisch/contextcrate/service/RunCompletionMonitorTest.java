package tech.wenisch.contextcrate.service;

import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.IngestionRun;
import tech.wenisch.contextcrate.domain.PipelineTypes.RunStatus;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStatus;
import tech.wenisch.contextcrate.repository.IngestionRunRepository;
import tech.wenisch.contextcrate.repository.PipelineWorkItemRepository;
import tech.wenisch.contextcrate.repository.SourceItemRepository;

class RunCompletionMonitorTest {
  @Test
  void marksRunFailedWhenIndexingWorkWasDeadLettered() {
    IngestionRunRepository runs = mock(IngestionRunRepository.class);
    SourceItemRepository frontier = mock(SourceItemRepository.class);
    PipelineWorkItemRepository work = mock(PipelineWorkItemRepository.class);
    IngestionRun run = run();
    when(runs.findTop20ByOrderByStartedAtDesc()).thenReturn(List.of(run));
    when(frontier.countByRunId(run.getId())).thenReturn(1L);
    when(work.countByCorrelationIdAndStatus(run.getId(), WorkStatus.DEAD_LETTERED)).thenReturn(1L);

    new RunCompletionMonitor(runs, frontier, work).completeFinishedRuns();

    org.assertj.core.api.Assertions.assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    verify(runs).save(run);
    verify(work, never()).countByCorrelationIdAndStatusIn(any(), anyList());
  }

  @Test
  void marksRunCompletedOnlyWhenNoWorkIsStillActiveOrDeadLettered() {
    IngestionRunRepository runs = mock(IngestionRunRepository.class);
    SourceItemRepository frontier = mock(SourceItemRepository.class);
    PipelineWorkItemRepository work = mock(PipelineWorkItemRepository.class);
    IngestionRun run = run();
    when(runs.findTop20ByOrderByStartedAtDesc()).thenReturn(List.of(run));
    when(frontier.countByRunId(run.getId())).thenReturn(1L);
    when(work.countByCorrelationIdAndStatus(run.getId(), WorkStatus.DEAD_LETTERED)).thenReturn(0L);
    when(work.countByCorrelationIdAndStatusIn(eq(run.getId()), anyList())).thenReturn(0L);

    new RunCompletionMonitor(runs, frontier, work).completeFinishedRuns();

    org.assertj.core.api.Assertions.assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
    verify(runs).save(run);
  }

  private static IngestionRun run() {
    UUID crateId = UUID.randomUUID();
    return new IngestionRun(UUID.randomUUID(), crateId, UUID.randomUUID(), UUID.randomUUID(), "{}", "{}");
  }
}
