package tech.wenisch.contextcrate.service;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.crawl.HttpCrawler;
import tech.wenisch.contextcrate.queue.PipelineQueue;

class PipelineCoordinatorRoleTest {
  @Test
  void controlPlaneDoesNotClaimWorkerStages() {
    PipelineQueue queue = mock(PipelineQueue.class);
    ContextCrateProperties properties =
        new ContextCrateProperties(
            "distributed",
            "control-plane",
            null,
            null,
            null,
            null,
            new ContextCrateProperties.Worker(1, 30, 3),
            null);
    PipelineCoordinator coordinator =
        new PipelineCoordinator(
            queue,
            new ObjectMapper(),
            mock(HttpCrawler.class),
            mock(DocumentParser.class),
            mock(ExtractionService.class),
            mock(DocumentIndexer.class),
            properties,
            new SimpleMeterRegistry());

    coordinator.poll();

    verify(queue, never()).claim(any());
  }
}
