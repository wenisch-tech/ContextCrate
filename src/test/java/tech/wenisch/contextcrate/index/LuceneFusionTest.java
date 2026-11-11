package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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

class LuceneFusionTest {
  @TempDir Path temporary;
  private final UUID crateId = UUID.randomUUID();

  private static float[] vector(String text) {
    String t = text.toLowerCase(Locale.ROOT);
    return t.contains("rotation") || t.contains("on-call") ? new float[] {1, 0, 0} : t.contains("invoice") ? new float[] {0, 1, 0} : new float[] {0, 0, 1};
  }

  @Test
  void fusionCombinesNormalizedKeywordAndVectorScoresAndTheWeightDecides() throws Exception {
    CrateRepository crates = mock(CrateRepository.class);
    when(crates.findById(crateId)).thenReturn(Optional.of(new Crate(crateId, "Fusion", null, null)));
    EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
    when(embeddings.available()).thenReturn(true);
    when(embeddings.embedDocuments(any())).thenAnswer(i -> ((List<String>) i.getArgument(0)).stream().map(LuceneFusionTest::vector).toList());
    when(embeddings.embedQuery(any())).thenAnswer(i -> vector(i.getArgument(0)));
    var properties = new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"),
        new ContextCrateProperties.Database("h2"), null, new ContextCrateProperties.Index("lucene", temporary, null, "contextcrate"), null, null);
    try (var index = new LuceneSearchIndex(properties, embeddings, crates)) {
      var doc = new NormalizedDocument(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "https://example.test/h", "Handbook", null, null, null,
          "body", Hashing.sha256("body"), "{}");
      doc.assignCrate(crateId);
      // The keyword hit mentions "zebra" literally; the vector hit is about the on-call rotation.
      var keywordHit = chunk(doc, 0, "The zebra protocol is described here, with zebra zebra details.");
      var vectorHit = chunk(doc, 1, "Engineers swap the beeper every Monday for the on-call rotation.");
      index.upsert(doc, List.of(ChunkRetrievalRecord.standard(keywordHit), ChunkRetrievalRecord.standard(vectorHit)));

      var request = new SearchIndex.SearchRequest(crateId, "zebra rotation", 10, null, "chunk", "fusion");
      var keywordOnly = index.search(request.withVectorWeight(0.0));
      var vectorOnly = index.search(request.withVectorWeight(1.0));
      var balanced = index.search(request);

      assertThat(keywordOnly.mode()).isEqualTo("fusion");
      assertThat(keywordOnly.hits().getFirst().id()).isEqualTo(keywordHit.getId());
      assertThat(vectorOnly.hits().getFirst().id()).isEqualTo(vectorHit.getId());
      assertThat(balanced.hits()).isNotEmpty().allSatisfy(h -> assertThat(h.score()).isBetween(0f, 1f));
      assertThat(balanced.hits().getFirst().lexicalScore()).isNotNull();
      assertThat(balanced.hits().getFirst().semanticScore()).isNotNull();
    }
  }

  private DocumentChunk chunk(NormalizedDocument d, int ordinal, String text) {
    var c = new DocumentChunk(UUID.randomUUID(), d.getId(), ordinal, "Handbook", text, Hashing.sha256(text));
    c.assignCrate(crateId);
    return c;
  }
}
