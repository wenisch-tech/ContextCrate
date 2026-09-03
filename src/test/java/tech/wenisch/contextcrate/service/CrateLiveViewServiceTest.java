package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.PipelineTypes.*;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.*;

class CrateLiveViewServiceTest {
  @Test
  void buildsEfficientZeroSafeCrateSnapshot() {
    UUID crateId = UUID.randomUUID();
    var documents = mock(NormalizedDocumentRepository.class);
    var chunks = mock(DocumentChunkRepository.class);
    var sources = mock(SourceRepository.class);
    var jobs = mock(IngestionJobRepository.class);
    var runs = mock(IngestionRunRepository.class);
    var work = mock(PipelineWorkItemRepository.class);
    var frontier = mock(SourceItemRepository.class);
    var acquisitions = mock(AcquisitionRecordRepository.class);
    var index = mock(SearchIndex.class);
    when(documents.countByCrateIdAndCurrentVersionTrue(crateId)).thenReturn(42L);
    when(documents.countByCrateIdAndCurrentVersionTrueAndIndexedFalse(crateId)).thenReturn(2L);
    when(chunks.countByCrateId(crateId)).thenReturn(318L);
    when(sources.countByCrateId(crateId)).thenReturn(3L);
    when(jobs.countByCrateId(crateId)).thenReturn(5L);
    when(runs.countByCrateIdAndStatusIn(eq(crateId), anyCollection())).thenReturn(1L);
    when(work.countByCrateIdAndStatusIn(eq(crateId), anyCollection())).thenReturn(4L);
    when(work.countsByCrate(crateId)).thenReturn(List.of());
    when(runs.findTop20ByCrateIdOrderByStartedAtDesc(crateId)).thenReturn(List.of());
    when(index.health(crateId)).thenReturn(new SearchIndex.IndexHealth(crateId, "lucene", true, 42, "ready"));

    var snapshot = new CrateLiveViewService(documents, chunks, sources, jobs, runs, work,
        frontier, acquisitions, index).snapshot(crateId, null);

    assertThat(snapshot.metrics().documents()).isEqualTo(42);
    assertThat(snapshot.metrics().chunks()).isEqualTo(318);
    assertThat(snapshot.metrics().activeRuns()).isEqualTo(1);
    assertThat(snapshot.metrics().failedWork()).isEqualTo(4);
    assertThat(snapshot.metrics().unindexedDocuments()).isEqualTo(2);
    assertThat(snapshot.pipeline()).containsKeys(WorkStage.values());
    assertThat(snapshot.pipeline().get(WorkStage.INDEX)).containsEntry(WorkStatus.PROCESSING, 0L);
    assertThat(snapshot.run()).isNull();
    verify(documents, never()).findByCrateId(any());
    verify(chunks, never()).findByCrateId(any());
    verify(sources, never()).findByCrateIdOrderByCreatedAtDesc(any());
    verify(jobs, never()).findByCrateIdOrderByCreatedAtDesc(any());
  }
}
