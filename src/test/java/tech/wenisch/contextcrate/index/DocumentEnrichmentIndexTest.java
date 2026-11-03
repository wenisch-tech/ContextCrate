package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;
import tech.wenisch.contextcrate.storage.Hashing;

class DocumentEnrichmentIndexTest {
  @TempDir Path temporary;

  /** Topic vectors: "rotation" texts are axis 0, "billing" axis 1, everything else axis 2. Records which calls were used. */
  static class TopicEmbeddings implements EmbeddingProvider {
    final List<String> asDocuments = new ArrayList<>(), asQueries = new ArrayList<>();
    static float[] vector(String t) {
      t = t.toLowerCase(Locale.ROOT);
      return t.contains("rotat") || t.contains("on-call") ? new float[] {1, 0, 0} : t.contains("invoice") || t.contains("billing") ? new float[] {0, 1, 0} : new float[] {0, 0, 1};
    }
    @Override public ModelDescriptor descriptor() { return new ModelDescriptor("test", "topics", "1", 3, true, "", ""); }
    @Override public boolean available() { return true; }
    @Override public List<float[]> embedDocuments(List<String> texts) { asDocuments.addAll(texts); return texts.stream().map(TopicEmbeddings::vector).toList(); }
    @Override public List<float[]> embedQueries(List<String> texts) { asQueries.addAll(texts); return texts.stream().map(TopicEmbeddings::vector).toList(); }
  }

  @Test
  void aMatchOnAGeneratedQuestionReturnsTheParentChunkOnce() throws Exception {
    UUID crateId = UUID.randomUUID();
    CrateRepository crates = mock(CrateRepository.class);
    when(crates.findById(crateId)).thenReturn(Optional.of(new Crate(crateId, "Enrichment", null, null)));
    var properties = new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"), new ContextCrateProperties.Database("h2"),
        null, new ContextCrateProperties.Index("lucene", temporary, null, "contextcrate"), null, null);
    var embeddings = new TopicEmbeddings();
    var document = new NormalizedDocument(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "https://example.test/handbook", "Handbook", null, null, null,
        "body", Hashing.sha256("body"), "{}");
    document.assignCrate(crateId);
    String text = "Engineers hand the pager over every Monday morning at nine.";
    var chunk = new DocumentChunk(UUID.randomUUID(), document.getId(), 0, "Handbook", text, Hashing.sha256(text));
    chunk.assignCrate(crateId);
    var other = new DocumentChunk(UUID.randomUUID(), document.getId(), 1, "Handbook", "Customers receive an invoice by email.", Hashing.sha256("o"));
    other.assignCrate(crateId);
    var records = new ArrayList<>(List.of(ChunkRetrievalRecord.standard(chunk), ChunkRetrievalRecord.standard(other),
        ChunkRetrievalRecord.question(chunk, 0, "How does the on-call rotation work?"),
        ChunkRetrievalRecord.question(chunk, 1, "When does the rotation change?")));

    try (var index = new LuceneSearchIndex(properties, embeddings, crates)) {
      index.upsert(document, records);
      // The user question shares no word with the chunk text but is close to the generated questions.
      var semantic = index.search(new SearchIndex.SearchRequest(crateId, "who is on-call this week", 10, null, "chunk", "semantic"));

      assertThat(semantic.hits()).hasSize(2).first().satisfies(hit -> {
        assertThat(hit.id()).isEqualTo(chunk.getId());
        assertThat(hit.content()).isEqualTo(text);
        assertThat(hit.snippet()).contains("pager");
        assertThat(hit.retrievalContent()).isEqualTo(text);
      });
      assertThat(semantic.hits()).extracting(SearchIndex.SearchHit::id).doesNotHaveDuplicates();

      // Lexical search also finds the parent through the words of a generated question.
      var lexical = index.search(new SearchIndex.SearchRequest(crateId, "rotation change", 10, null, "chunk", "lexical"));
      assertThat(lexical.hits()).singleElement().satisfies(hit -> assertThat(hit.id()).isEqualTo(chunk.getId()));
    }
    // Chunks are embedded as passages, generated questions like queries.
    assertThat(embeddings.asQueries).contains("How does the on-call rotation work?", "When does the rotation change?");
    assertThat(embeddings.asDocuments).contains(text).doesNotContain("How does the on-call rotation work?");
  }
}
