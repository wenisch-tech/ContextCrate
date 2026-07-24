package tech.wenisch.contextcrate.crawl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.wenisch.contextcrate.crawl.CrawlerAuthenticationService.CrawlerResponse;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.queue.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.*;
import tech.wenisch.contextcrate.storage.ArtifactStore;

@ExtendWith(MockitoExtension.class)
class HttpCrawlerTest {
  @Mock SourceItemRepository items;
  @Mock IngestionRunRepository runs;
  @Mock IngestionService ingestion;
  @Mock UrlPolicy urls;
  @Mock RobotsService robots;
  @Mock HostPoliteness politeness;
  @Mock ArtifactStore artifacts;
  @Mock AcquisitionRecordRepository acquisitions;
  @Mock PipelineQueue queue;
  @Mock CrawlerAuthenticationService authentication;
  private HttpCrawler crawler;
  private UUID runId;
  private UUID itemId;
  private SourceItem item;
  private IngestionRun run;
  private CrawlConfiguration config;

  @BeforeEach
  void setUp() {
    crawler = new HttpCrawler(items, runs, ingestion, urls, robots, politeness, artifacts,
        acquisitions, queue, authentication);
    runId = UUID.randomUUID(); itemId = UUID.randomUUID();
    item = new SourceItem(itemId, runId, "https://example.com", "https://example.com/", 0);
    item.status(PipelineTypes.FrontierStatus.QUEUED);
    UUID crateId = UUID.randomUUID(); item.assignCrate(crateId);
    run = new IngestionRun(runId, crateId, UUID.randomUUID(), UUID.randomUUID(), "{}", "{}");
    config = new CrawlConfiguration(null,
        new CrawlConfiguration.Politeness("ContextCrateBot", "", false, 1, 0, 5000),
        null, null, null);
    when(items.findById(itemId)).thenReturn(Optional.of(item));
    when(runs.findById(runId)).thenReturn(Optional.of(run));
    lenient().when(ingestion.effectiveWeb(run)).thenReturn(config);
  }

  @Test
  void successfulFetchPersistsAndQueuesParsing() throws Exception {
    byte[] html = "<html><body>ok</body></html>".getBytes(StandardCharsets.UTF_8);
    when(authentication.get(runId, item.getLocator(), config)).thenReturn(new CrawlerResponse(
        200, item.getLocator(), HttpHeaders.of(
            Map.of("Content-Type", List.of("text/html; charset=UTF-8")), (n, v) -> true),
        new ByteArrayInputStream(html)));
    when(artifacts.put(anyString(), any(), anyLong()))
        .thenReturn(new ArtifactStore.ArtifactMetadata("key", "sha", html.length));
    crawler.fetch(new PipelinePayload(run.getCrateId(), runId, itemId), false);
    ArgumentCaptor<AcquisitionRecord> record = ArgumentCaptor.forClass(AcquisitionRecord.class);
    verify(acquisitions).save(record.capture());
    assertThat(record.getValue().getOutcome()).isEqualTo(PipelineTypes.FetchOutcome.SUCCEEDED);
    assertThat(item.getStatus()).isEqualTo(PipelineTypes.FrontierStatus.FETCHED);
    verify(queue).publish(any(PipelineMessage.class));
  }

  @Test
  void authenticationFailureIsRecorded() throws Exception {
    when(authentication.get(runId, item.getLocator(), config))
        .thenThrow(new IllegalStateException("Authentication failed"));
    assertThatThrownBy(() -> crawler.fetch(
        new PipelinePayload(run.getCrateId(), runId, itemId), false))
        .isInstanceOf(IllegalStateException.class);
    ArgumentCaptor<AcquisitionRecord> record = ArgumentCaptor.forClass(AcquisitionRecord.class);
    verify(acquisitions).save(record.capture());
    assertThat(record.getValue().getErrorMessage()).isEqualTo("Authentication failed");
    assertThat(item.getStatus()).isEqualTo(PipelineTypes.FrontierStatus.FAILED);
  }

  @Test
  void stoppedRunDoesNotFetch() throws Exception {
    run.status(PipelineTypes.RunStatus.CANCELLED);
    crawler.fetch(new PipelinePayload(run.getCrateId(), runId, itemId), false);
    verify(authentication).clear(runId);
    verify(authentication, never()).get(any(), anyString(), any());
  }
}
