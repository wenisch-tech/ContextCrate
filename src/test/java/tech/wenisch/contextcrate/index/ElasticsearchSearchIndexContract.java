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
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.reranking.DisabledRerankingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;

/** The shared expectations against a real Elasticsearch ({@code ELASTICSEARCH_URL}); see {@link ElasticsearchLiveTest}. */
abstract class ElasticsearchSearchIndexContract extends SearchIndexContract {
  abstract String endpoint();
  String apiKey() { return null; }
  String username() { return null; }
  String password() { return null; }

  final String prefix = "cc-test-" + UUID.randomUUID().toString().substring(0, 8);

  @Override
  SearchIndex index(Topics embeddings) {
    CrateRepository crates = mock(CrateRepository.class);
    when(crates.findById(crateId)).thenReturn(Optional.of(new Crate(crateId, "Elasticsearch", null, null)));
    var properties = new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"), new ContextCrateProperties.Database("h2"), null,
        new ContextCrateProperties.Index("elasticsearch", Path.of("unused"), endpoint(), prefix, apiKey(), username(), password(), false), null, null);
    return new ElasticsearchSearchIndex(properties, mapper, embeddings, new DisabledRerankingProvider(), crates);
  }

  String get(String path) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(endpoint() + path));
    if (apiKey() != null) builder.header("Authorization", "ApiKey " + apiKey());
    else if (username() != null) builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((username() + ":" + password()).getBytes()));
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString()).body();
  }

  List<String> ownIndexes() throws Exception {
    List<String> names = new ArrayList<>();
    for (JsonNode row : mapper.readTree(get("/_cat/indices/" + prefix + "-crate-*?format=json&h=index"))) names.add(row.path("index").asText());
    return names;
  }

  @Override int ownIndexCount() throws Exception { return ownIndexes().size(); }

  @Test
  void createsADenseVectorFieldWithCosineSimilarityAndTheCodeAnalyzer() throws Exception {
    var doc = document("Handbook", "body");
    index(new Topics(true)).upsert(doc, List.of(ChunkRetrievalRecord.standard(chunk(doc, 0, "Customers receive an invoice by email."))));

    String name = ownIndexes().getFirst();
    JsonNode mapping = mapper.readTree(get("/" + name + "/_mapping")).path(name).path("mappings").path("properties");
    assertThat(mapping.path("embedding").path("type").asText()).isEqualTo("dense_vector");
    assertThat(mapping.path("embedding").path("dims").asInt()).isEqualTo(3);
    assertThat(mapping.path("embedding").path("similarity").asText()).isEqualTo("cosine");
    assertThat(mapping.path("text").path("analyzer").asText()).isEqualTo("code");
    assertThat(mapping.path("chunk_id").path("type").asText()).isEqualTo("keyword");
  }

  @Test
  void identifiersAreFoundByTheirParts() throws Exception {
    var doc = document("Handbook", "body");
    var code = chunk(doc, 0, "Call getUserName before rendering the profile page.");
    var index = index(new Topics(false));
    index.upsert(doc, List.of(ChunkRetrievalRecord.standard(code)));
    assertThat(index.search(new SearchIndex.SearchRequest(crateId, "user", 10, null, "chunk", "lexical")).hits()).extracting(SearchIndex.SearchHit::id).containsExactly(code.getId());
  }
}
