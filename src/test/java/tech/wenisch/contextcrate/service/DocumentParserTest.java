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
            new CrawlConfiguration.Output(30, "", List.of(), 200, 20, "default"), false)));
    when(artifacts.open("artifact.md")).thenReturn(new ByteArrayInputStream(
        markdown.getBytes(StandardCharsets.UTF_8)));
    when(documents.findTopByCrateIdAndSourceIdAndIdentityUriOrderByVersionNumberDesc(
        any(), any(), anyString())).thenReturn(Optional.empty());
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
        .contains("Product docs", "Product docs > Setup");
  }

  @Test
  void changedGitContentCreatesNextVersionButUnchangedContentDoesNot() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    DocumentParser parser = new DocumentParser(acquisitions, runs, documents, chunks, items,
        artifacts, ingestion, new UrlPolicy(true), queue, extraction, mapper, providers);
    UUID crateId = UUID.randomUUID(), runId = UUID.randomUUID(), acquisitionId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    IngestionRun run = new IngestionRun(runId, crateId, sourceId, UUID.randomUUID(), "{}", "{}");
    AcquisitionRecord acquisition = new AcquisitionRecord(acquisitionId, runId, UUID.randomUUID(),
        "docs/readme.md");
    acquisition.assignCrate(crateId);
    acquisition.success("git+https://example.com/repo.git@abc/docs/readme.md", 200,
        "text/markdown", "UTF-8", "artifact.md", "sha", 100, 1);
    String original = "# Product\n\nOriginal document content that is long enough to normalize.";
    String changed = "# Product\n\nChanged document content that is long enough to normalize.";
    when(acquisitions.findById(acquisitionId)).thenReturn(Optional.of(acquisition));
    when(runs.findById(runId)).thenReturn(Optional.of(run));
    when(ingestion.connector(run)).thenReturn(ConnectorType.GIT);
    when(ingestion.jobConfiguration(run)).thenReturn(IngestionConfiguration.git(
        new IngestionConfiguration.Git("", null, null, List.of("**"), List.of(), 100, 1000,
            new CrawlConfiguration.Output(30, "", List.of(), 200, 20, "default"), false)));
    when(artifacts.open("artifact.md")).thenReturn(
        new ByteArrayInputStream(original.getBytes(StandardCharsets.UTF_8)),
        new ByteArrayInputStream(original.getBytes(StandardCharsets.UTF_8)),
        new ByteArrayInputStream(changed.getBytes(StandardCharsets.UTF_8)));
    when(providers.effectiveEmbedding(crateId)).thenReturn(new RuntimeProviderSettings.Embedding(
        true, "local", "model", "revision", "url", java.nio.file.Path.of("models"), null,
        null, null, null, 384, 8000));
    when(documents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    when(documents.findTopByCrateIdAndSourceIdAndIdentityUriOrderByVersionNumberDesc(
        crateId, sourceId, "docs/readme.md")).thenReturn(Optional.empty());
    parser.parse(new PipelinePayload(crateId, runId, acquisitionId));
    ArgumentCaptor<NormalizedDocument> captured = ArgumentCaptor.forClass(NormalizedDocument.class);
    verify(documents).save(captured.capture());
    NormalizedDocument first = captured.getValue();
    assertThat(first.getVersionNumber()).isEqualTo(1);

    when(documents.findTopByCrateIdAndSourceIdAndIdentityUriOrderByVersionNumberDesc(
        crateId, sourceId, "docs/readme.md")).thenReturn(Optional.of(first));
    parser.parse(new PipelinePayload(crateId, runId, acquisitionId));
    verify(documents, times(1)).save(any());

    parser.parse(new PipelinePayload(crateId, runId, acquisitionId));
    verify(documents, times(3)).save(captured.capture());
    NormalizedDocument second = captured.getValue();
    assertThat(second.getVersionNumber()).isEqualTo(2);
    assertThat(second.getIdentityUri()).isEqualTo("docs/readme.md");
    assertThat(first.isCurrentVersion()).isFalse();
  }

  @Test
  void gitCodeFilesAreChunkedByStructureWithLanguageAndSymbol() throws Exception {
    DocumentParser parser = new DocumentParser(acquisitions, runs, documents, chunks, items,
        artifacts, ingestion, new UrlPolicy(true), queue, extraction, new ObjectMapper(), providers);
    UUID crateId = UUID.randomUUID(), runId = UUID.randomUUID(), acquisitionId = UUID.randomUUID();
    IngestionRun run = new IngestionRun(runId, crateId, UUID.randomUUID(), UUID.randomUUID(), "{}", "{}");
    AcquisitionRecord acquisition = new AcquisitionRecord(acquisitionId, runId, UUID.randomUUID(),
        "src/Greeter.java");
    acquisition.assignCrate(crateId);
    acquisition.success("git+https://example.com/repo.git@abc/src/Greeter.java", 200,
        "text/x-source", "UTF-8", "artifact.src", "sha", 100, 1);
    String code = "public class Greeter {\n  public String hello(String name) {\n"
        + "    return \"Hello \" + name;\n  }\n\n  public String bye() {\n    return \"Bye\";\n  }\n}\n";
    when(acquisitions.findById(acquisitionId)).thenReturn(Optional.of(acquisition));
    when(runs.findById(runId)).thenReturn(Optional.of(run));
    when(ingestion.connector(run)).thenReturn(ConnectorType.GIT);
    when(ingestion.jobConfiguration(run)).thenReturn(IngestionConfiguration.git(
        new IngestionConfiguration.Git("", null, null, List.of("**"), List.of(), 100, 1000,
            new CrawlConfiguration.Output(30, "", List.of(), 200, 20, "default"), false)));
    when(artifacts.open("artifact.src")).thenReturn(
        new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8)));
    when(providers.effectiveEmbedding(crateId)).thenReturn(new RuntimeProviderSettings.Embedding(
        true, "local", "model", "revision", "url", java.nio.file.Path.of("models"), null,
        null, null, null, 384, 8000));
    when(documents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(documents.findTopByCrateIdAndSourceIdAndIdentityUriOrderByVersionNumberDesc(
        any(), any(), anyString())).thenReturn(Optional.empty());

    parser.parse(new PipelinePayload(crateId, runId, acquisitionId));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DocumentChunk>> created = ArgumentCaptor.forClass(List.class);
    verify(chunks).saveAll(created.capture());
    assertThat(created.getValue()).isNotEmpty().allSatisfy(c -> {
      assertThat(c.isCode()).isTrue();
      assertThat(c.getLanguage()).isEqualTo("java");
    });
    assertThat(created.getValue()).anyMatch(c -> c.getSymbol() != null && c.getSymbol().contains("Greeter"));
    assertThat(created.getValue().get(0).displayContent()).startsWith("```java\n");
  }

  @Test
  void markdownImagesAreIndexedOnlyWhenImageIndexingIsOn() throws Exception {
    for (boolean on : new boolean[] {true, false}) {
      var rag = mock(tech.wenisch.contextcrate.answer.RagSettingsService.class);
      var settings = new tech.wenisch.contextcrate.domain.RagSettings(UUID.randomUUID(), false, true, true, true, true, true, "revise-once", "hybrid", "standard", "fail-indexing", 5);
      settings.updateImages(on, false, null, 200, 5_000_000, 64, "");
      var imageRows = mock(tech.wenisch.contextcrate.repository.DocumentImageRepository.class);
      when(rag.current(any())).thenReturn(settings);
      DocumentParser parser = new DocumentParser(acquisitions, runs, documents, chunks, items,
          artifacts, ingestion, new UrlPolicy(true), queue, extraction, new ObjectMapper(), providers);
      parser.setImageSupport(rag, imageRows);
      UUID crateId = UUID.randomUUID(), runId = UUID.randomUUID(), acquisitionId = UUID.randomUUID();
      IngestionRun run = new IngestionRun(runId, crateId, UUID.randomUUID(), UUID.randomUUID(), "{}", "{}");
      AcquisitionRecord acquisition = new AcquisitionRecord(acquisitionId, runId, UUID.randomUUID(), "docs/guide.md");
      acquisition.assignCrate(crateId);
      acquisition.success("git+https://github.com/o/r.git@" + "a".repeat(40) + "/docs/guide.md", 200,
          "text/markdown", "UTF-8", "a.md", "sha", 100, 1);
      String md = "# Guide\n\nSee the diagram.\n\n![Request flow](img/flow.png)\n\n![](img/spacer.png)\n";
      when(acquisitions.findById(acquisitionId)).thenReturn(Optional.of(acquisition));
      when(runs.findById(runId)).thenReturn(Optional.of(run));
      when(ingestion.connector(run)).thenReturn(ConnectorType.GIT);
      when(ingestion.jobConfiguration(run)).thenReturn(IngestionConfiguration.git(
          new IngestionConfiguration.Git("", null, null, List.of("**"), List.of(), 100, 1000,
              new CrawlConfiguration.Output(30, "", List.of(), 1000, 20, "default"), false)));
      when(artifacts.open("a.md")).thenReturn(new ByteArrayInputStream(md.getBytes(StandardCharsets.UTF_8)));
      when(providers.effectiveEmbedding(crateId)).thenReturn(new RuntimeProviderSettings.Embedding(
          true, "local", "model", "revision", "url", java.nio.file.Path.of("models"), null, null, null, null, 384, 8000));
      when(documents.save(any())).thenAnswer(inv -> inv.getArgument(0));
      when(documents.findTopByCrateIdAndSourceIdAndIdentityUriOrderByVersionNumberDesc(any(), any(), anyString())).thenReturn(Optional.empty());
      clearInvocations(chunks);

      parser.parse(new PipelinePayload(crateId, runId, acquisitionId));

      @SuppressWarnings("unchecked")
      ArgumentCaptor<List<DocumentChunk>> created = ArgumentCaptor.forClass(List.class);
      verify(chunks).saveAll(created.capture());
      String all = created.getValue().stream().map(DocumentChunk::getContent).reduce("", String::concat);
      if (on) {
        assertThat(all).contains("[Image: Request flow").contains("github.com/o/r/blob/");
        verify(imageRows, atLeastOnce()).save(any());
      } else {
        assertThat(all).doesNotContain("[Image:");
        verify(imageRows, never()).save(any());
      }
    }
  }

  @Test
  void changingSemanticChunkingRechunksUnchangedContentAndStandardDocumentsStayUntouched() throws Exception {
    DocumentParser parser = new DocumentParser(acquisitions, runs, documents, chunks, items,
        artifacts, ingestion, new UrlPolicy(true), queue, extraction, new ObjectMapper(), providers);
    var embeddings = mock(tech.wenisch.contextcrate.embedding.EmbeddingProvider.class);
    when(embeddings.available()).thenReturn(true);
    when(embeddings.embedDocuments(anyList())).thenAnswer(call -> {
      List<float[]> out = new ArrayList<>();
      for (String sentence : call.<List<String>>getArgument(0))
        out.add(sentence.contains("cat") ? new float[] {1, 0} : new float[] {0, 1});
      return out;
    });
    parser.setEmbeddings(embeddings);
    UUID crateId = UUID.randomUUID(), runId = UUID.randomUUID(), acquisitionId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    IngestionRun run = new IngestionRun(runId, crateId, sourceId, UUID.randomUUID(), "{}", "{}");
    AcquisitionRecord acquisition = new AcquisitionRecord(acquisitionId, runId, UUID.randomUUID(), "notes.md");
    acquisition.assignCrate(crateId);
    acquisition.success("git+https://example.com/repo.git@abc/notes.md", 200, "text/markdown", "UTF-8",
        "artifact.md", "sha", 100, 1);
    String markdown = "# Notes\n\nA cat sat down. The cat purred loudly. Cats like warm places. "
        + "Stocks fell today. Markets closed lower. Traders sold shares quickly.";
    when(acquisitions.findById(acquisitionId)).thenReturn(Optional.of(acquisition));
    when(runs.findById(runId)).thenReturn(Optional.of(run));
    when(ingestion.connector(run)).thenReturn(ConnectorType.GIT);
    when(artifacts.open("artifact.md")).thenAnswer(call -> new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)));
    when(providers.effectiveEmbedding(crateId)).thenReturn(new RuntimeProviderSettings.Embedding(
        true, "local", "model", "revision", "url", java.nio.file.Path.of("models"), null, null, null, null, 384, 8000));
    when(documents.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var standard = new CrawlConfiguration.Output(30, "", List.of(), 1000, 20, "default");
    var semantic = new CrawlConfiguration.Output(30, "", List.of(), 1000, 20, "default",
        new CrawlConfiguration.SemanticChunking(true, "percentile", 60, 0));
    when(documents.findTopByCrateIdAndSourceIdAndIdentityUriOrderByVersionNumberDesc(any(), any(), anyString()))
        .thenReturn(Optional.empty());

    when(ingestion.jobConfiguration(run)).thenReturn(IngestionConfiguration.git(
        new IngestionConfiguration.Git("", null, null, List.of("**"), List.of(), 100, 1000, standard, false)));
    parser.parse(new PipelinePayload(crateId, runId, acquisitionId));
    ArgumentCaptor<NormalizedDocument> saved = ArgumentCaptor.forClass(NormalizedDocument.class);
    verify(documents, times(1)).save(saved.capture());
    NormalizedDocument plain = saved.getValue();
    assertThat(plain.getMetadataJson()).doesNotContain("chunking");
    when(documents.findTopByCrateIdAndSourceIdAndIdentityUriOrderByVersionNumberDesc(any(), any(), anyString()))
        .thenReturn(Optional.of(plain));

    // same content, same (standard) configuration: nothing to do
    parser.parse(new PipelinePayload(crateId, runId, acquisitionId));
    verify(documents, times(1)).save(any());

    // same content, semantic chunking switched on: re-chunked as the next version
    when(ingestion.jobConfiguration(run)).thenReturn(IngestionConfiguration.git(
        new IngestionConfiguration.Git("", null, null, List.of("**"), List.of(), 100, 1000, semantic, false)));
    parser.parse(new PipelinePayload(crateId, runId, acquisitionId));
    verify(documents, times(3)).save(saved.capture());
    NormalizedDocument rechunked = saved.getValue();
    assertThat(rechunked.getVersionNumber()).isEqualTo(2);
    assertThat(rechunked.getMetadataJson()).contains("semantic:percentile 60").contains("\"breakpoints\":1");
    when(documents.findTopByCrateIdAndSourceIdAndIdentityUriOrderByVersionNumberDesc(any(), any(), anyString()))
        .thenReturn(Optional.of(rechunked));

    // and unchanged again afterwards
    parser.parse(new PipelinePayload(crateId, runId, acquisitionId));
    verify(documents, times(3)).save(any());
  }
}
