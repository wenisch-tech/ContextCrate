package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;
import tech.wenisch.contextcrate.storage.Hashing;

class LuceneContextHeaderTest {
  @TempDir Path temporary;

  @Test
  void embedsAndIndexesHeaderPlusChunkButReturnsTheOriginalChunk() throws Exception {
    UUID crateId = UUID.randomUUID();
    CrateRepository crates = mock(CrateRepository.class);
    when(crates.findById(crateId)).thenReturn(Optional.of(new Crate(crateId, "Headers", null, null)));
    EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
    when(embeddings.available()).thenReturn(true);
    List<String> embedded = new ArrayList<>();
    when(embeddings.embedDocuments(any())).thenAnswer(i -> {
      List<String> texts = i.getArgument(0);
      embedded.addAll(texts);
      return texts.stream().map(t -> new float[] {1f, 0f}).toList();
    });
    var properties = new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"),
        new ContextCrateProperties.Database("h2"), null, new ContextCrateProperties.Index("lucene", temporary, null, "contextcrate"), null, null);
    try (var index = new LuceneSearchIndex(properties, embeddings, crates)) {
      var document = new NormalizedDocument(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "https://example.test/oncall",
          "oncall.md", null, null, null, "Restart it after changing the certificate.", Hashing.sha256("body"), "{}");
      document.assignCrate(crateId);
      var chunk = new DocumentChunk(UUID.randomUUID(), document.getId(), 0, "Handbook > Pager rules",
          "Restart it after changing the certificate.", Hashing.sha256("Restart it after changing the certificate."));
      chunk.assignCrate(crateId);
      String header = "Title: On-Call Strategy for engineers\nSection: Handbook > Pager rules";

      index.upsert(document, List.of(ChunkRetrievalRecord.standard(chunk, header)));

      // The vector index received the full text: header followed by the chunk.
      assertThat(embedded).contains(header + "\n\nRestart it after changing the certificate.");
      // A word that exists only in the header finds the chunk by keyword search ...
      var hits = index.search(new SearchIndex.SearchRequest(crateId, "strategy", 5, null, "chunk", "lexical"));
      assertThat(hits.hits()).singleElement().satisfies(hit -> {
        assertThat(hit.id()).isEqualTo(chunk.getId());
        // ... while the content handed to answers stays the original chunk.
        assertThat(hit.content()).isEqualTo("Restart it after changing the certificate.");
        assertThat(hit.retrievalContent()).startsWith("Title: On-Call Strategy");
      });
    }
  }
}
