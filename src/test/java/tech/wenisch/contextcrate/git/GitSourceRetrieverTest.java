package tech.wenisch.contextcrate.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.wenisch.contextcrate.crawl.UrlPolicy;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.queue.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.*;
import tech.wenisch.contextcrate.storage.ArtifactStore;

@ExtendWith(MockitoExtension.class)
class GitSourceRetrieverTest {
  @Mock SourceItemRepository items;
  @Mock AcquisitionRecordRepository acquisitions;
  @Mock IngestionRunRepository runs;
  @Mock ArtifactStore artifacts;
  @Mock PipelineQueue queue;
  @Mock IngestionService ingestion;
  @Mock UrlPolicy urls;
  @TempDir Path temporary;

  @Test
  void acquiresMatchingReadableFilesAndRecordsExactRevision() throws Exception {
    Path repository = temporary.resolve("fixture");
    Files.createDirectories(repository.resolve("docs"));
    Files.writeString(repository.resolve("README.md"), "# Root\nReadable", StandardCharsets.UTF_8);
    Files.writeString(repository.resolve("docs/guide.txt"), "Guide text", StandardCharsets.UTF_8);
    Files.writeString(repository.resolve("docs/ignored.md"), "# Ignore", StandardCharsets.UTF_8);
    Files.writeString(repository.resolve("image.bin"), "\0binary", StandardCharsets.UTF_8);
    String revision;
    try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
      git.add().addFilepattern(".").call();
      revision = git.commit().setMessage("fixture")
          .setAuthor("ContextCrate", "test@example.invalid")
          .setCommitter("ContextCrate", "test@example.invalid").call().getId().name();
    }

    UUID crateId = UUID.randomUUID(), runId = UUID.randomUUID(), rootId = UUID.randomUUID();
    IngestionRun run = new IngestionRun(runId, crateId, UUID.randomUUID(), UUID.randomUUID(),
        "{}", "{}");
    SourceItem root = new SourceItem(rootId, runId, repository.toUri().toString(),
        repository.toUri().toString(), 0);
    root.assignCrate(crateId);
    SourceConfiguration source = SourceConfiguration.git(repository.toUri().toString());
    IngestionConfiguration config = IngestionConfiguration.git(new IngestionConfiguration.Git(
        "", null, null, List.of("**"), List.of("docs/ignored.md"), 100, 1024,
        CrawlConfiguration.Output.defaults()));

    when(items.findById(rootId)).thenReturn(Optional.of(root));
    when(runs.findById(runId)).thenReturn(Optional.of(run));
    when(ingestion.sourceConfiguration(run)).thenReturn(source);
    when(ingestion.jobConfiguration(run)).thenReturn(config);
    when(items.findByRunIdAndSourceUri(eq(runId), anyString())).thenReturn(Optional.empty());
    when(artifacts.put(anyString(), any(), eq(1024L)))
        .thenAnswer(call -> new ArtifactStore.ArtifactMetadata(
            call.getArgument(0), "sha", 10));

    new GitSourceRetriever(items, acquisitions, runs, artifacts, queue, ingestion, urls)
        .fetch(new PipelinePayload(crateId, runId, rootId));

    assertThat(run.getResolvedRevision()).isEqualTo(revision);
    assertThat(root.getStatus()).isEqualTo(PipelineTypes.FrontierStatus.FETCHED);
    ArgumentCaptor<AcquisitionRecord> records =
        ArgumentCaptor.forClass(AcquisitionRecord.class);
    verify(acquisitions, times(2)).save(records.capture());
    assertThat(records.getAllValues()).extracting(AcquisitionRecord::getRequestedLocator)
        .containsExactlyInAnyOrder("README.md", "docs/guide.txt");
    assertThat(records.getAllValues()).allSatisfy(record -> {
      assertThat(record.getFinalLocator()).contains("@" + revision + "/");
      assertThat(record.getOutcome()).isEqualTo(PipelineTypes.FetchOutcome.SUCCEEDED);
    });
    verify(queue, times(2)).publish(argThat(message ->
        message.stage() == PipelineTypes.WorkStage.PARSE));
  }
}
