package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;
import tech.wenisch.contextcrate.storage.Hashing;

class LuceneCaptionSearchTest {
  @TempDir Path temporary;

  static class Embeddings implements EmbeddingProvider {
    final List<String> asDocuments = new ArrayList<>(), asQueries = new ArrayList<>();
    static float[] vector(String t) {
      t = t.toLowerCase(Locale.ROOT);
      return t.contains("pricing") || t.contains("plan") ? new float[] {1, 0} : new float[] {0, 1};
    }
    @Override public ModelDescriptor descriptor() { return new ModelDescriptor("test", "topics", "1", 2, true, "", ""); }
    @Override public boolean available() { return true; }
    @Override public List<float[]> embedDocuments(List<String> texts) { asDocuments.addAll(texts); return texts.stream().map(Embeddings::vector).toList(); }
    @Override public List<float[]> embedQueries(List<String> texts) { asQueries.addAll(texts); return texts.stream().map(Embeddings::vector).toList(); }
  }

  @Test
  void aCaptionFindsItsChunkAndTheHitIsTheRealTableWithItsSource() throws Exception {
    UUID crateId = UUID.randomUUID();
    CrateRepository crates = mock(CrateRepository.class);
    when(crates.findById(crateId)).thenReturn(Optional.of(new Crate(crateId, "Captions", null, null)));
    var properties = new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"), new ContextCrateProperties.Database("h2"),
        null, new ContextCrateProperties.Index("lucene", temporary, null, "contextcrate"), null, null);
    var embeddings = new Embeddings();
    var document = new NormalizedDocument(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "https://example.test/pricing", "Pricing page", null, null, null,
        "body", Hashing.sha256("body"), "{}");
    document.assignCrate(crateId);
    String table = "Tier | Cost\nFree | 0\nPro | 20";
    var chunk = new DocumentChunk(UUID.randomUUID(), document.getId(), 4, "Pricing > Plans", table, Hashing.sha256(table));
    chunk.assignCrate(crateId);
    var other = new DocumentChunk(UUID.randomUUID(), document.getId(), 5, "Pricing", "We ship worldwide.", Hashing.sha256("o"));
    other.assignCrate(crateId);
    var caption = ChunkRetrievalRecord.caption(chunk, "table", "Compares the subscription plans and what each one costs per month.");

    try (var index = new LuceneSearchIndex(properties, embeddings, crates)) {
      index.upsert(document, List.of(ChunkRetrievalRecord.standard(chunk), ChunkRetrievalRecord.standard(other), caption));

      // The words of the caption are not in the table; they find the table through the caption record.
      var lexical = index.search(new SearchIndex.SearchRequest(crateId, "subscription", 10, null, "chunk", "lexical"));
      assertThat(lexical.hits()).singleElement().satisfies(hit -> {
        assertThat(hit.id()).isEqualTo(chunk.getId());
        assertThat(hit.content()).isEqualTo(table);
        assertThat(hit.retrievalContent()).isEqualTo(table);
        assertThat(hit.chunkOrdinal()).isEqualTo(4);
        assertThat(hit.documentId()).isEqualTo(document.getId());
        assertThat(hit.sourceUri()).isEqualTo("https://example.test/pricing");
        assertThat(hit.title()).isEqualTo("Pricing page");
      });

      var semantic = index.search(new SearchIndex.SearchRequest(crateId, "which plan is cheapest", 10, null, "chunk", "semantic"));
      assertThat(semantic.hits()).extracting(SearchIndex.SearchHit::id).doesNotHaveDuplicates().contains(chunk.getId());
    }
    // Chunks and captions are both embedded as passages.
    assertThat(embeddings.asDocuments).contains(table, "Table caption: Compares the subscription plans and what each one costs per month.");
    assertThat(embeddings.asQueries).doesNotContain("Table caption: Compares the subscription plans and what each one costs per month.");
  }
}
