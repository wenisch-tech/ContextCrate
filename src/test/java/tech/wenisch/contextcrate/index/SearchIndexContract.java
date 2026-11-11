package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.storage.Hashing;

/** Backend-neutral expectations every search index must meet. Subclasses supply the backend (a fake server or a real service). */
abstract class SearchIndexContract {
  final ObjectMapper mapper = new ObjectMapper();

  /** The index under test, configured for {@link #crateId}. */
  abstract SearchIndex index(Topics embeddings) throws Exception;

  /** Number of physical indexes (collections, tables) this test created for the crate. */
  abstract int ownIndexCount() throws Exception;

  /** Topic vectors: rotation / invoice / other. Records which calls were used. */
  static class Topics implements EmbeddingProvider {
    final List<String> asDocuments = new ArrayList<>(), asQueries = new ArrayList<>();
    final boolean available;
    Topics(boolean available) { this.available = available; }
    static float[] vector(String text) {
      String t = text.toLowerCase(Locale.ROOT);
      return t.contains("rotation") || t.contains("on-call") ? new float[] {1, 0, 0} : t.contains("invoice") || t.contains("billing") ? new float[] {0, 1, 0} : new float[] {0, 0, 1};
    }
    @Override public ModelDescriptor descriptor() { return new ModelDescriptor("test", "topics", "1", available ? 3 : 0, true, "", ""); }
    @Override public boolean available() { return available; }
    @Override public List<float[]> embedDocuments(List<String> texts) { asDocuments.addAll(texts); return texts.stream().map(Topics::vector).toList(); }
    @Override public List<float[]> embedQueries(List<String> texts) { asQueries.addAll(texts); return texts.stream().map(Topics::vector).toList(); }
  }

  final UUID crateId = UUID.randomUUID();
  NormalizedDocument document(String title, String body) {
    var d = new NormalizedDocument(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "https://example.test/" + title.toLowerCase(), title, null, null, null, body, Hashing.sha256(body), "{}");
    d.assignCrate(crateId);
    return d;
  }

  DocumentChunk chunk(NormalizedDocument d, int ordinal, String text) {
    var c = new DocumentChunk(UUID.randomUUID(), d.getId(), ordinal, "Handbook", text, Hashing.sha256(text));
    c.assignCrate(crateId);
    return c;
  }

  @Test
  void searchesLexicalSemanticAndHybrid() throws Exception {
    var embeddings = new Topics(true);
    var doc = document("Handbook", "body");
    var rotation = chunk(doc, 0, "Engineers swap the beeper every Monday for the on-call rotation.");
    var billing = chunk(doc, 1, "Customers receive an invoice by email at the end of the month.");
    var index = index(embeddings);
    index.upsert(doc, List.of(ChunkRetrievalRecord.standard(rotation), ChunkRetrievalRecord.standard(billing)));

    assertThat(index.health(crateId)).satisfies(h -> { assertThat(h.healthy()).isTrue(); assertThat(h.documents()).isEqualTo(3); });

    var lexical = index.search(new SearchIndex.SearchRequest(crateId, "invoice email", 10, null, "chunk", "lexical"));
    assertThat(lexical.hits()).first().satisfies(h -> { assertThat(h.id()).isEqualTo(billing.getId()); assertThat(h.chunkOrdinal()).isEqualTo(1); assertThat(h.content()).isEqualTo(billing.getContent()); assertThat(h.lexicalScore()).isPositive(); });
    var identifiers = index.search(new SearchIndex.SearchRequest(crateId, "monday", 10, null, "chunk", "lexical"));
    assertThat(identifiers.hits()).extracting(SearchIndex.SearchHit::id).containsExactly(rotation.getId());

    var semantic = index.search(new SearchIndex.SearchRequest(crateId, "who is on-call", 10, null, "chunk", "semantic"));
    assertThat(semantic.hits()).first().satisfies(h -> { assertThat(h.id()).isEqualTo(rotation.getId()); assertThat(h.semanticScore()).isGreaterThan(0.9f); });

    var hybrid = index.search(new SearchIndex.SearchRequest(crateId, "on-call monday", 10, null, "chunk", "hybrid"));
    assertThat(hybrid.hits().getFirst().id()).isEqualTo(rotation.getId());
    assertThat(hybrid.hits().getFirst().lexicalScore()).isNotNull();
    assertThat(hybrid.hits().getFirst().semanticScore()).isNotNull();

    // the kind filter keeps documents out of chunk searches and the other way round
    assertThat(index.search(new SearchIndex.SearchRequest(crateId, "handbook", 10, null, "document", "lexical")).hits()).allMatch(h -> h.kind().equals("document"));
    // a run filter that matches nothing
    assertThat(index.search(new SearchIndex.SearchRequest(crateId, "invoice", 10, UUID.randomUUID(), "chunk", "lexical")).hits()).isEmpty();
    assertThat(embeddings.asDocuments).contains(rotation.getContent(), billing.getContent());
  }

  @Test
  void fusionNormalizesBothLegsAndRanksByTheWeightedScore() throws Exception {
    var embeddings = new Topics(true);
    var doc = document("Handbook", "body");
    var rotation = chunk(doc, 0, "Engineers swap the beeper every Monday for the on-call rotation.");
    var billing = chunk(doc, 1, "Customers receive an invoice by email at the end of the month.");
    var index = index(embeddings);
    index.upsert(doc, List.of(ChunkRetrievalRecord.standard(rotation), ChunkRetrievalRecord.standard(billing)));

    var fusion = index.search(new SearchIndex.SearchRequest(crateId, "on-call monday", 10, null, "chunk", "fusion"));

    assertThat(fusion.mode()).isEqualTo("fusion");
    assertThat(fusion.hits().getFirst().id()).isEqualTo(rotation.getId());
    assertThat(fusion.hits().getFirst().lexicalScore()).isNotNull();
    assertThat(fusion.hits().getFirst().semanticScore()).isNotNull();
    assertThat(fusion.hits()).allSatisfy(h -> assertThat(h.score()).isBetween(0f, 1f));
    var vectorOnly = index.search(new SearchIndex.SearchRequest(crateId, "on-call monday", 10, null, "chunk", "fusion").withVectorWeight(1.0));
    assertThat(vectorOnly.hits().getFirst().id()).isEqualTo(rotation.getId());
  }

  @Test
  void aGeneratedQuestionFindsItsParentChunkOnceAndIsEmbeddedLikeAQuery() throws Exception {
    var embeddings = new Topics(true);
    var doc = document("Handbook", "body");
    var parent = chunk(doc, 0, "Engineers swap the beeper every Monday morning at nine.");
    var other = chunk(doc, 1, "Customers receive an invoice by email.");
    var index = index(embeddings);
    index.upsert(doc, List.of(ChunkRetrievalRecord.standard(parent), ChunkRetrievalRecord.standard(other),
        ChunkRetrievalRecord.question(parent, 0, "How does the on-call rotation work?"), ChunkRetrievalRecord.question(parent, 1, "When does the rotation change?")));

    var result = index.search(new SearchIndex.SearchRequest(crateId, "who is on-call", 10, null, "chunk", "semantic"));
    assertThat(result.hits()).extracting(SearchIndex.SearchHit::id).containsExactly(parent.getId(), other.getId());
    assertThat(result.hits().getFirst().content()).isEqualTo(parent.getContent());
    assertThat(result.hits().getFirst().retrievalContent()).isEqualTo(parent.getContent());
    assertThat(embeddings.asQueries).contains("How does the on-call rotation work?");
    assertThat(embeddings.asDocuments).doesNotContain("How does the on-call rotation work?");
    var lexical = index.search(new SearchIndex.SearchRequest(crateId, "rotation change", 10, null, "chunk", "lexical"));
    assertThat(lexical.hits()).extracting(SearchIndex.SearchHit::id).containsExactly(parent.getId());
  }

  @Test
  void deletingADocumentAndGenerationsOnlyAffectsTheirOwnData() throws Exception {
    var embeddings = new Topics(true);
    var first = document("First", "body");
    var second = document("Second", "body");
    var a = chunk(first, 0, "Alpha chunk about the rotation.");
    var b = chunk(second, 0, "Beta chunk about the invoice.");
    var index = index(embeddings);
    index.upsert(first, List.of(ChunkRetrievalRecord.standard(a)));
    index.upsert(second, List.of(ChunkRetrievalRecord.standard(b)));
    index.delete(crateId, first.getId());
    assertThat(index.search(new SearchIndex.SearchRequest(crateId, "chunk", 10, null, "chunk", "lexical")).hits()).extracting(SearchIndex.SearchHit::id).containsExactly(b.getId());

    // upserting the same document again replaces its points instead of duplicating them
    index.upsert(second, List.of(ChunkRetrievalRecord.standard(b)));
    assertThat(index.health(crateId).documents()).isEqualTo(2);

    // a second generation lives in its own collection and can be dropped without touching the first
    index.upsertGeneration(crateId, 2, second, List.of(ChunkRetrievalRecord.standard(b)));
    assertThat(ownIndexCount()).isEqualTo(2);
    index.deleteGeneration(crateId, 2);
    assertThat(ownIndexCount()).isEqualTo(1);
    index.deleteNamespace(crateId);
    assertThat(ownIndexCount()).isZero();
  }

  @Test
  void withoutEmbeddingsKeywordSearchWorksAndSemanticSearchSaysWhy() throws Exception {
    var embeddings = new Topics(false);
    var doc = document("Handbook", "body");
    var c = chunk(doc, 0, "Customers receive an invoice by email.");
    var index = index(embeddings);
    index.upsert(doc, List.of(ChunkRetrievalRecord.standard(c)));

    assertThat(index.search(new SearchIndex.SearchRequest(crateId, "invoice", 10, null, "chunk")).hits()).extracting(SearchIndex.SearchHit::id).containsExactly(c.getId());
    assertThatThrownBy(() -> index.search(new SearchIndex.SearchRequest(crateId, "invoice", 10, null, "chunk", "semantic"))).hasMessageContaining("embeddings are unavailable");
  }
}
