package tech.wenisch.contextcrate.graph;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.CrateIndexGeneration;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.repository.CrateIndexGenerationRepository;
import tech.wenisch.contextcrate.repository.PipelineWorkItemRepository;

class GraphBuildJobTest {
  private final UUID crate = UUID.randomUUID();
  private final GraphStore store = mock(GraphStore.class);
  private final GraphBuildService builder = mock(GraphBuildService.class);
  private final RagSettingsService settings = mock(RagSettingsService.class);
  private final PipelineWorkItemRepository work = mock(PipelineWorkItemRepository.class);
  private final CrateIndexGenerationRepository generations = mock(CrateIndexGenerationRepository.class);
  private final ContextCrateProperties properties = mock(ContextCrateProperties.class);
  private final GraphBuildJob job = new GraphBuildJob(store, builder, settings, work, generations, properties);

  GraphBuildJobTest() {
    when(properties.role()).thenReturn("all");
    when(store.dirtyCrates()).thenReturn(List.of(crate));
    var rag = new RagSettings(crate, false, true, true, true, false, false, "revise-once", "hybrid", 8);
    rag.updateGraphRag(true, false, "auto");
    when(settings.current(crate)).thenReturn(rag);
    when(generations.findByCrateIdAndStatus(crate, CrateIndexGeneration.Status.BUILDING)).thenReturn(List.of());
  }

  @Test
  void waitsWhileIndexingIsBusy() {
    when(work.countByCrateIdAndStatusIn(eq(crate), any())).thenReturn(3L);
    job.run();
    verify(store, never()).claim(any(), any(), any());
  }

  @Test
  void buildsOnceIndexingIsIdleAndTheClaimSucceeds() {
    when(work.countByCrateIdAndStatusIn(eq(crate), any())).thenReturn(0L);
    when(store.claim(eq(crate), any(), any())).thenReturn(true);
    job.run();
    verify(builder, timeout(2000)).build(crate);
  }

  @Test
  void anotherInstanceThatClaimedFirstWins() {
    when(work.countByCrateIdAndStatusIn(eq(crate), any())).thenReturn(0L);
    when(store.claim(eq(crate), any(), any())).thenReturn(false);
    job.run();
    verify(builder, after(200).never()).build(any());
  }

  @Test
  void workerNodesDoNotBuild() {
    when(properties.role()).thenReturn("worker");
    job.run();
    verifyNoInteractions(store);
  }
}
