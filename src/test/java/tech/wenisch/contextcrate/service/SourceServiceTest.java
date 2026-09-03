package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.repository.*;

class SourceServiceTest {
  private final SourceRepository sources = mock(SourceRepository.class);
  private final IngestionJobRepository jobs = mock(IngestionJobRepository.class);
  private final NormalizedDocumentRepository documents = mock(NormalizedDocumentRepository.class);
  private final IngestionRunRepository runs = mock(IngestionRunRepository.class);
  private final SourceConfigurationCodec codec = mock(SourceConfigurationCodec.class);
  private final AuditLogRepository audits = mock(AuditLogRepository.class);
  private final SourceService service = new SourceService(sources, jobs, documents, runs, codec, audits);

  @Test
  void summariesMapCurrentContentAndLatestRunWithoutPerSourceQueries() {
    UUID crateId = UUID.randomUUID();
    Source populated = new Source(UUID.randomUUID(), crateId, "Docs", "", ConnectorType.HTTPS, "{}");
    Source empty = new Source(UUID.randomUUID(), crateId, "Empty", "", ConnectorType.HTTPS, "{}");
    IngestionRun latest = new IngestionRun(UUID.randomUUID(), crateId, populated.getId(),
        UUID.randomUUID(), "{}", "{}");
    when(sources.findByCrateIdOrderByCreatedAtDesc(crateId)).thenReturn(List.of(populated, empty));
    when(jobs.countBySource(eq(crateId), anyCollection())).thenReturn(List.of(jobCount(populated.getId(), 2)));
    when(documents.countCurrentContentBySource(eq(crateId), anyCollection()))
        .thenReturn(List.of(contentCount(populated.getId(), 7, 31)));
    when(runs.findLatestBySourceIdIn(eq(crateId), anyCollection())).thenReturn(List.of(latest));

    List<SourceService.SourceSummary> summaries = service.summaries(crateId);

    assertThat(summaries).extracting(summary -> summary.source().getName()).containsExactly("Docs", "Empty");
    assertThat(summaries.getFirst()).satisfies(summary -> {
      assertThat(summary.jobs()).isEqualTo(2);
      assertThat(summary.documents()).isEqualTo(7);
      assertThat(summary.chunks()).isEqualTo(31);
      assertThat(summary.latestRun()).isSameAs(latest);
    });
    assertThat(summaries.get(1)).satisfies(summary -> {
      assertThat(summary.jobs()).isZero();
      assertThat(summary.documents()).isZero();
      assertThat(summary.chunks()).isZero();
      assertThat(summary.latestRun()).isNull();
    });
    verify(jobs).countBySource(eq(crateId), anyCollection());
    verify(documents).countCurrentContentBySource(eq(crateId), anyCollection());
    verify(runs).findLatestBySourceIdIn(eq(crateId), anyCollection());
  }

  private static IngestionJobRepository.SourceJobCount jobCount(UUID sourceId, long value) {
    return new IngestionJobRepository.SourceJobCount() {
      public UUID getSourceId() { return sourceId; }
      public long getJobs() { return value; }
    };
  }

  private static NormalizedDocumentRepository.SourceContentCount contentCount(UUID sourceId,
      long documents, long chunks) {
    return new NormalizedDocumentRepository.SourceContentCount() {
      public UUID getSourceId() { return sourceId; }
      public long getDocuments() { return documents; }
      public long getChunks() { return chunks; }
    };
  }
}
