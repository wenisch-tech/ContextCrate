package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.wenisch.contextcrate.crawl.UrlPolicy;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.queue.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.storage.ArtifactStore;

@ExtendWith(MockitoExtension.class)
class DocumentParserTest {
  @Mock AcquisitionRecordRepository acquisitions;
  @Mock IngestionRunRepository runs;
  @Mock NormalizedDocumentRepository documents;
  @Mock DocumentChunkRepository chunks;
  @Mock SourceItemRepository items;
  @Mock ArtifactStore artifacts;
  @Mock IngestionService ingestion;
  @Mock PipelineQueue queue;
  @Mock ExtractionService extraction;
  @Mock RuntimeProviderSettings providers;

  @Test
  void markdownCreatesReadableDocumentAndHeadingAwareChunks() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    DocumentParser parser = new DocumentParser(acquisitions, runs, documents, chunks, items,
        artifacts, ingestion, new UrlPolicy(true), queue, extraction, mapper, providers);
    UUID crateId = UUID.randomUUID(), runId = UUID.randomUUID(), acquisitionId = UUID.randomUUID();
    IngestionRun run = new IngestionRun(runId, crateId, UUID.randomUUID(), UUID.randomUUID(),
        "{}", "{}");
    run.resolvedRevision("abc123");
    AcquisitionRecord acquisition = new AcquisitionRecord(acquisitionId, runId, UUID.randomUUID(),
        "docs/readme.md");
    acquisition.assignCrate(crateId);
    acquisition.success("git+https://example.com/repo.git@abc123/docs/readme.md", 200,
        "text/markdown; charset=UTF-8", "UTF-8", "artifact.md", "sha", 100, 1);
    String markdown = "# Product docs\n\nThis is **readable** text.\n\n## Setup\n\nRun `mvn test`.";
    when(acquisitions.findById(acquisitionId)).thenReturn(Optional.of(acquisition));
    when(runs.findById(runId)).thenReturn(Optional.of(run));
    when(ingestion.connector(run)).thenReturn(ConnectorType.GIT);
    when(ingestion.jobConfiguration(run)).thenReturn(IngestionConfiguration.git(
        new IngestionConfiguration.Git("", null, null, List.of("**"), List.of(), 100, 1000,
            new CrawlConfiguration.Output(30, "", List.of(), 200, 20, "default"))));
    when(artifacts.open("artifact.md")).thenReturn(new ByteArrayInputStream(
        markdown.getBytes(StandardCharsets.UTF_8)));
    when(documents.findByRunIdAndSourceUri(eq(runId), anyString())).thenReturn(Optional.empty());
    when(documents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(providers.effectiveEmbedding(crateId)).thenReturn(new RuntimeProviderSettings.Embedding(
        true, "local", "model", "revision", "url", java.nio.file.Path.of("models"), null,
        null, null, null, 384, 8000));

    parser.parse(new PipelinePayload(crateId, runId, acquisitionId));

    ArgumentCaptor<NormalizedDocument> document = ArgumentCaptor.forClass(NormalizedDocument.class);
    verify(documents).save(document.capture());
    assertThat(document.getValue().getTitle()).isEqualTo("Product docs");
    assertThat(document.getValue().getBody()).contains("readable").doesNotContain("**");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DocumentChunk>> created = ArgumentCaptor.forClass(List.class);
    verify(chunks).saveAll(created.capture());
    assertThat(created.getValue()).extracting(DocumentChunk::getHeading)
        .contains("Product docs", "Setup");
  }
}
