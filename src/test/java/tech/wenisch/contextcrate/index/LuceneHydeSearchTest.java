package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;

class LuceneHydeSearchTest {
  @TempDir Path temporary;

  @Test
  void embedsAHypotheticalAnswerAsADocumentAndAPlainQueryAsAQuery() throws Exception {
    UUID crateId = UUID.randomUUID();
    CrateRepository crates = mock(CrateRepository.class);
    when(crates.findById(crateId)).thenReturn(Optional.of(new Crate(crateId, "HyDE", null, null)));
    EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
    when(embeddings.available()).thenReturn(true);
    when(embeddings.embedDocuments(any())).thenAnswer(i -> List.of(new float[] {1f, 0f}));
    when(embeddings.embedQuery(any())).thenReturn(new float[] {1f, 0f});
    var properties = new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"),
        new ContextCrateProperties.Database("h2"), null, new ContextCrateProperties.Index("lucene", temporary, null, "contextcrate"), null, null);
    try (var index = new LuceneSearchIndex(properties, embeddings, crates)) {
      var document = new NormalizedDocument(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "https://example.test/a",
          "Backup guide", null, null, null, "Backups use pg_dump", tech.wenisch.contextcrate.storage.Hashing.sha256("Backups use pg_dump"), "{}");
      document.assignCrate(crateId);
      index.upsert(document, List.of());
      clearInvocations(embeddings);

      var plain = index.search(new SearchIndex.SearchRequest(crateId, "how backup", 5, null, "document", "semantic"));
      assertThat(plain.hits()).hasSize(1);
      verify(embeddings).embedQuery("how backup");
      verify(embeddings, never()).embedDocuments(any());
      clearInvocations(embeddings);

      var hyde = index.search(new SearchIndex.SearchRequest(crateId, "how backup", 5, null, "document", "semantic")
          .withVectorQuery("Backups are created with pg_dump."));
      assertThat(hyde.hits()).hasSize(1);
      assertThat(hyde.query()).isEqualTo("how backup");
      verify(embeddings).embedDocuments(List.of("Backups are created with pg_dump."));
      verify(embeddings, never()).embedQuery(any());
    }
  }
}
