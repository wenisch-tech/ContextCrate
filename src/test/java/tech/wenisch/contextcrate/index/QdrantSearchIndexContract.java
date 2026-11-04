package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
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
import tech.wenisch.contextcrate.reranking.DisabledRerankingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;

/** The shared expectations against an in-process fake and, when {@code QDRANT_URL} is set, against a real Qdrant. */
abstract class QdrantSearchIndexContract extends SearchIndexContract {
  @TempDir Path temporary;

  abstract String endpoint();
  String apiKey() { return null; }

  /** A real Qdrant is shared between tests, so each test works in its own prefix and only looks at its own collections. */
  final String prefix = "cc-test-" + UUID.randomUUID().toString().substring(0, 8);

  List<String> ownCollections() throws Exception {
    List<String> names = new ArrayList<>();
    for (JsonNode c : mapper.readTree(get("/collections")).path("result").path("collections"))
      if (c.path("name").asText().startsWith(prefix + "-crate-")) names.add(c.path("name").asText());
    return names;
  }

  @Override int ownIndexCount() throws Exception { return ownCollections().size(); }

  @Override
  SearchIndex index(Topics embeddings) {
    CrateRepository crates = mock(CrateRepository.class);
    when(crates.findById(crateId)).thenReturn(Optional.of(new Crate(crateId, "Qdrant", null, null)));
    var properties = new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"), new ContextCrateProperties.Database("h2"), null,
        new ContextCrateProperties.Index("qdrant", temporary, endpoint(), prefix, apiKey()), null, null);
    return new QdrantSearchIndex(properties, mapper, embeddings, new DisabledRerankingProvider(), crates);
  }

  String get(String path) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(endpoint() + path));
    if (apiKey() != null) builder.header("api-key", apiKey());
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString()).body();
  }

  @Test
  void createsTheCollectionWithDenseAndSparseVectors() throws Exception {
    var doc = document("Handbook", "body");
    index(new Topics(true)).upsert(doc, List.of(ChunkRetrievalRecord.standard(chunk(doc, 0, "Customers receive an invoice by email."))));

    assertThat(ownCollections()).hasSize(1);
    JsonNode params = mapper.readTree(get("/collections/" + ownCollections().getFirst())).path("result").path("config").path("params");
    assertThat(params.path("vectors").path("dense").path("size").asInt()).isEqualTo(3);
    assertThat(params.path("vectors").path("dense").path("distance").asText()).isEqualToIgnoringCase("cosine");
    assertThat(params.path("sparse_vectors").path("text").path("modifier").asText()).isEqualToIgnoringCase("idf");
  }
}
