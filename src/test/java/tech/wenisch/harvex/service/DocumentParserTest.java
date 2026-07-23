package tech.wenisch.harvex.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.wenisch.harvex.crawl.UrlPolicy;
import tech.wenisch.harvex.domain.*;
import tech.wenisch.harvex.domain.CrawlConfiguration.RenderMode;
import tech.wenisch.harvex.domain.PipelineTypes.WorkStage;
import tech.wenisch.harvex.queue.*;
import tech.wenisch.harvex.repository.*;
import tech.wenisch.harvex.storage.ArtifactStore;

@ExtendWith(MockitoExtension.class)
class DocumentParserTest {
  @Mock FetchRecordRepository fetches;
  @Mock CrawlRunRepository runs;
  @Mock NormalizedDocumentRepository documents;
  @Mock DocumentChunkRepository chunks;
  @Mock FrontierEntryRepository frontier;
  @Mock ArtifactStore artifacts;
  @Mock PipelineQueue queue;
    @Mock ExtractionService extraction;

  @Test
  void persistsAlreadyRenderedAutoFetchInsteadOfRequestingBrowserAgain() throws Exception {
    var mapper = new ObjectMapper();
    var codec = new ConfigurationCodec(mapper);
    var parser =
        new DocumentParser(
            fetches,
            runs,
            documents,
            chunks,
            frontier,
            artifacts,
            codec,
            new UrlPolicy(true),
            queue,
            extraction,
            mapper);
    UUID runId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    UUID fetchId = UUID.randomUUID();
    UUID frontierId = UUID.randomUUID();
    var config =
        new CrawlConfiguration(
            new CrawlConfiguration.Scope(
                "https://wenisch.tech/", Set.of("wenisch.tech"), List.of(), List.of(), 1, 10, true, false),
            CrawlConfiguration.Politeness.defaults(),
            new CrawlConfiguration.Reliability(3, 1000, 1_000_000, true, RenderMode.AUTO),
            new CrawlConfiguration.Output(30, "", List.of("script", "style"), 500, 50, "test"),
            CrawlConfiguration.LoginConfiguration.defaults());
    var run = new CrawlRun(runId, jobId, codec.write(config));
    var fetch = new FetchRecord(fetchId, runId, frontierId, "https://wenisch.tech/");
    fetch.success(
        "https://wenisch.tech/",
        200,
        "text/html; charset=UTF-8",
        "UTF-8",
        runId + "/" + fetchId + ".rendered.html",
        "sha",
        1,
        1);
    var entry = new FrontierEntry(frontierId, runId, "https://wenisch.tech/", "https://wenisch.tech/", 0);
    String html =
        "<html><head><title>Rendered</title><link rel='canonical' href='https://wenisch.tech/'></head>"
            + "<body><main><h1>Rendered</h1></main><script></script><script></script>"
            + "<script></script><script></script></body></html>";

    when(fetches.findById(fetchId)).thenReturn(Optional.of(fetch));
    when(runs.findById(runId)).thenReturn(Optional.of(run));
    when(artifacts.open(fetch.getArtifactKey()))
        .thenReturn(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    when(documents.findByRunIdAndCanonicalUrl(eq(runId), anyString())).thenReturn(Optional.empty());
    when(documents.save(any(NormalizedDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(frontier.findById(frontierId)).thenReturn(Optional.of(entry));

    parser.parse(new PipelinePayload(runId, fetchId));

    ArgumentCaptor<NormalizedDocument> document = ArgumentCaptor.forClass(NormalizedDocument.class);
    verify(documents).save(document.capture());
    assertThat(document.getValue().getBody()).isEqualTo("Rendered");
        verify(extraction).publish(document.getValue(), false);
    verify(queue, never()).publish(argThat(message -> message.stage() == WorkStage.BROWSER_FETCH));
    verify(queue).publish(argThat(message -> message.stage() == WorkStage.INDEX));
  }
}