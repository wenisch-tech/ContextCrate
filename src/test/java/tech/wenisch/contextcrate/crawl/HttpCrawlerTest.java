package tech.wenisch.contextcrate.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.wenisch.contextcrate.crawl.CrawlerAuthenticationService.CrawlerResponse;
import tech.wenisch.contextcrate.domain.CrawlConfiguration;
import tech.wenisch.contextcrate.domain.CrawlRun;
import tech.wenisch.contextcrate.domain.FetchRecord;
import tech.wenisch.contextcrate.domain.FrontierEntry;
import tech.wenisch.contextcrate.domain.PipelineTypes.FetchOutcome;
import tech.wenisch.contextcrate.domain.PipelineTypes.FrontierStatus;
import tech.wenisch.contextcrate.domain.PipelineTypes.RunStatus;
import tech.wenisch.contextcrate.queue.PipelineMessage;
import tech.wenisch.contextcrate.queue.PipelineQueue;
import tech.wenisch.contextcrate.repository.CrawlRunRepository;
import tech.wenisch.contextcrate.repository.FetchRecordRepository;
import tech.wenisch.contextcrate.repository.FrontierEntryRepository;
import tech.wenisch.contextcrate.service.ConfigurationCodec;
import tech.wenisch.contextcrate.service.PipelinePayload;
import tech.wenisch.contextcrate.storage.ArtifactStore;

@ExtendWith(MockitoExtension.class)
class HttpCrawlerTest {
  @Mock FrontierEntryRepository frontier;
  @Mock CrawlRunRepository runs;
  @Mock ConfigurationCodec codec;
  @Mock UrlPolicy urls;
  @Mock RobotsService robots;
  @Mock HostPoliteness politeness;
  @Mock ArtifactStore artifacts;
  @Mock FetchRecordRepository fetches;
  @Mock PipelineQueue queue;
  @Mock CrawlerAuthenticationService authentication;

  private HttpCrawler crawler;
  private UUID runId;
  private UUID entryId;
  private FrontierEntry entry;
  private CrawlRun run;
  private CrawlConfiguration config;

  @BeforeEach
  void setUp() {
    crawler =
        new HttpCrawler(
            frontier,
            runs,
            codec,
            urls,
            robots,
            politeness,
            artifacts,
            fetches,
            queue,
            authentication);
    runId = UUID.randomUUID();
    entryId = UUID.randomUUID();
    entry = new FrontierEntry(entryId, runId, "https://example.com", "https://example.com/", 0);
    entry.status(FrontierStatus.QUEUED);
    run = new CrawlRun(runId, UUID.randomUUID(), "{}");
    config =
        new CrawlConfiguration(
            null,
            new CrawlConfiguration.Politeness("ContextCrateBot", "", false, 1, 0, 5000),
            null,
            null,
            null);
    when(frontier.findById(entryId)).thenReturn(Optional.of(entry));
    when(runs.findById(runId)).thenReturn(Optional.of(run));
  }

  @Test
  void successfulFetchPersistsAndQueuesParsing() throws Exception {
    byte[] html = "<html><body>ok</body></html>".getBytes(StandardCharsets.UTF_8);
    when(codec.read("{}")).thenReturn(config);
    when(authentication.get(runId, entry.getUrl(), config))
        .thenReturn(
            new CrawlerResponse(
                200,
                entry.getUrl(),
                HttpHeaders.of(
                    Map.of("Content-Type", List.of("text/html; charset=UTF-8")),
                    (name, value) -> true),
                new ByteArrayInputStream(html)));
    when(artifacts.put(anyString(), any(), anyLong()))
        .thenReturn(new ArtifactStore.ArtifactMetadata("key", "sha", html.length));

    crawler.fetch(new PipelinePayload(runId, entryId), false);

    ArgumentCaptor<FetchRecord> record = ArgumentCaptor.forClass(FetchRecord.class);
    verify(fetches).save(record.capture());
    assertThat(record.getValue().getOutcome()).isEqualTo(FetchOutcome.SUCCEEDED);
    assertThat(entry.getStatus()).isEqualTo(FrontierStatus.FETCHED);
    verify(queue).publish(any(PipelineMessage.class));
  }

  @Test
  void authenticationFailureIsRecordedWithoutLosingRetrySignal() throws Exception {
    when(codec.read("{}")).thenReturn(config);
    when(authentication.get(runId, entry.getUrl(), config))
        .thenThrow(new IllegalStateException("Authentication failed"));

    assertThatThrownBy(() -> crawler.fetch(new PipelinePayload(runId, entryId), false))
        .isInstanceOf(IllegalStateException.class);

    ArgumentCaptor<FetchRecord> record = ArgumentCaptor.forClass(FetchRecord.class);
    verify(fetches).save(record.capture());
    assertThat(record.getValue().getOutcome()).isEqualTo(FetchOutcome.FAILED);
    assertThat(record.getValue().getErrorMessage()).isEqualTo("Authentication failed");
    assertThat(entry.getStatus()).isEqualTo(FrontierStatus.FAILED);
  }

  @Test
  void stoppedRunDoesNotFetch() throws Exception {
    run.status(RunStatus.CANCELLED);
    crawler.fetch(new PipelinePayload(runId, entryId), false);
    verify(authentication).clear(runId);
    verify(authentication, never()).get(any(), anyString(), any());
    verify(fetches, never()).save(any());
  }
}
