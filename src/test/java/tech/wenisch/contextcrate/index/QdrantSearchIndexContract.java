package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.reranking.DisabledRerankingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;
import tech.wenisch.contextcrate.storage.Hashing;

/** The same expectations run against an in-process fake and, when {@code QDRANT_URL} is set, against a real Qdrant. */
abstract class QdrantSearchIndexContract {
  @TempDir Path temporary;
  final ObjectMapper mapper = new ObjectMapper();

  abstract String endpoint();
  String apiKey() { return null; }

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
  /** A real Qdrant is shared between tests, so each test works in its own prefix and only looks at its own collections. */
  final String prefix = "cc-test-" + UUID.randomUUID().toString().substring(0, 8);

  List<String> ownCollections() throws Exception {
    List<String> names = new ArrayList<>();
    for (JsonNode c : mapper.readTree(get("/collections")).path("result").path("collections"))
      if (c.path("name").asText().startsWith(prefix + "-crate-")) names.add(c.path("name").asText());
    return names;
  }

  QdrantSearchIndex index(Topics embeddings, int generation) {
    CrateRepository crates = mock(CrateRepository.class);
    var crate = new Crate(crateId, "Qdrant", null, null);
    when(crates.findById(crateId)).thenReturn(Optional.of(crate));
    var properties = new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"), new ContextCrateProperties.Database("h2"), null,
        new ContextCrateProperties.Index("qdrant", temporary, endpoint(), prefix, apiKey()), null, null);
    return new QdrantSearchIndex(properties, mapper, embeddings, new DisabledRerankingProvider(), crates);
  }

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

  String get(String path) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(endpoint() + path));
    if (apiKey() != null) builder.header("api-key", apiKey());
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString()).body();
  }

  @Test
  void createsTheCollectionWithDenseAndSparseVectorsAndSearchesLexicalSemanticAndHybrid() throws Exception {
    var embeddings = new Topics(true);
    var doc = document("Handbook", "body");
    var rotation = chunk(doc, 0, "Engineers swap the beeper every Monday for the on-call rotation.");
    var billing = chunk(doc, 1, "Customers receive an invoice by email at the end of the month.");
    var index = index(embeddings, 1);
    index.upsert(doc, List.of(ChunkRetrievalRecord.standard(rotation), ChunkRetrievalRecord.standard(billing)));

    // schema: dense size 3 / cosine and sparse text with the idf modifier
    assertThat(ownCollections()).hasSize(1);
    JsonNode params = mapper.readTree(get("/collections/" + ownCollections().getFirst())).path("result").path("config").path("params");
    assertThat(params.path("vectors").path("dense").path("size").asInt()).isEqualTo(3);
    assertThat(params.path("vectors").path("dense").path("distance").asText()).isEqualToIgnoringCase("cosine");
    assertThat(params.path("sparse_vectors").path("text").path("modifier").asText()).isEqualToIgnoringCase("idf");
    assertThat(index.health(crateId)).satisfies(h -> { assertThat(h.healthy()).isTrue(); assertThat(h.backend()).isEqualTo("qdrant"); assertThat(h.documents()).isEqualTo(3); });

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
  void aGeneratedQuestionFindsItsParentChunkOnceAndIsEmbeddedLikeAQuery() throws Exception {
    var embeddings = new Topics(true);
    var doc = document("Handbook", "body");
    var parent = chunk(doc, 0, "Engineers swap the beeper every Monday morning at nine.");
    var other = chunk(doc, 1, "Customers receive an invoice by email.");
    var index = index(embeddings, 1);
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
    var index = index(embeddings, 1);
    index.upsert(first, List.of(ChunkRetrievalRecord.standard(a)));
    index.upsert(second, List.of(ChunkRetrievalRecord.standard(b)));
    index.delete(crateId, first.getId());
    assertThat(index.search(new SearchIndex.SearchRequest(crateId, "chunk", 10, null, "chunk", "lexical")).hits()).extracting(SearchIndex.SearchHit::id).containsExactly(b.getId());

    // upserting the same document again replaces its points instead of duplicating them
    index.upsert(second, List.of(ChunkRetrievalRecord.standard(b)));
    assertThat(index.health(crateId).documents()).isEqualTo(2);

    // a second generation lives in its own collection and can be dropped without touching the first
    index.upsertGeneration(crateId, 2, second, List.of(ChunkRetrievalRecord.standard(b)));
    assertThat(ownCollections()).hasSize(2);
    index.deleteGeneration(crateId, 2);
    assertThat(ownCollections()).hasSize(1);
    index.deleteNamespace(crateId);
    assertThat(ownCollections()).isEmpty();
  }

  @Test
  void withoutEmbeddingsKeywordSearchWorksAndSemanticSearchSaysWhy() throws Exception {
    var embeddings = new Topics(false);
    var doc = document("Handbook", "body");
    var c = chunk(doc, 0, "Customers receive an invoice by email.");
    var index = index(embeddings, 1);
    index.upsert(doc, List.of(ChunkRetrievalRecord.standard(c)));

    assertThat(index.search(new SearchIndex.SearchRequest(crateId, "invoice", 10, null, "chunk")).hits()).extracting(SearchIndex.SearchHit::id).containsExactly(c.getId());
    assertThatThrownBy(() -> index.search(new SearchIndex.SearchRequest(crateId, "invoice", 10, null, "chunk", "semantic"))).hasMessageContaining("embeddings are unavailable");
  }
}
