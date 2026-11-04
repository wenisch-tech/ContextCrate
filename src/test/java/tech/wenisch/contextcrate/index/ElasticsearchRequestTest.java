package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.reranking.DisabledRerankingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;

/** What ContextCrate sends to Elasticsearch, checked against a recording stand-in (behaviour is covered by {@link ElasticsearchLiveTest}). */
class ElasticsearchRequestTest {
  record Call(String method, String path, String query, String body, String authorization) {}

  final List<Call> calls = new CopyOnWriteArrayList<>();
  final ObjectMapper mapper = new ObjectMapper();
  volatile String bulkResponse = "{\"errors\":false,\"items\":[]}";
  volatile String catResponse = "[]";
  HttpServer server;
  final UUID crateId = UUID.randomUUID();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", e -> {
      String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      calls.add(new Call(e.getRequestMethod(), e.getRequestURI().getPath(), e.getRequestURI().getQuery(), body, e.getRequestHeaders().getFirst("Authorization")));
      String path = e.getRequestURI().getPath();
      String response = path.equals("/_bulk") ? bulkResponse : path.startsWith("/_cat/") ? catResponse : "{\"acknowledged\":true,\"hits\":{\"hits\":[]},\"count\":0}";
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      e.getResponseHeaders().set("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    });
    server.start();
  }

  @AfterEach void stop() { server.stop(0); }

  ElasticsearchSearchIndex index(String apiKey, String username, String password, boolean embeddings) {
    CrateRepository crates = mock(CrateRepository.class);
    when(crates.findById(crateId)).thenReturn(Optional.of(new Crate(crateId, "ES", null, null)));
    var properties = new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"), new ContextCrateProperties.Database("h2"), null,
        new ContextCrateProperties.Index("elasticsearch", Path.of("unused"), "http://127.0.0.1:" + server.getAddress().getPort(), "Prefix", apiKey, username, password, false), null, null);
    return new ElasticsearchSearchIndex(properties, mapper, new SearchIndexContract.Topics(embeddings), new DisabledRerankingProvider(), crates);
  }

  NormalizedDocument document() {
    var d = new NormalizedDocument(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "https://example.test/x", "Title", null, null, null, "body", "hash", "{}");
    d.assignCrate(crateId);
    return d;
  }

  @Test
  void anApiKeyIsSentAsAuthorizationHeaderAndBasicCredentialsAsBasic() throws Exception {
    index("abc123", null, null, false).initialize(crateId);
    assertThat(calls).isNotEmpty().allMatch(c -> "ApiKey abc123".equals(c.authorization()));

    calls.clear();
    index(null, "elastic", "changeme", false).initialize(crateId);
    assertThat(calls).isNotEmpty().allMatch(c -> ("Basic " + Base64.getEncoder().encodeToString("elastic:changeme".getBytes())).equals(c.authorization()));

    calls.clear();
    index(null, null, null, false).initialize(crateId);
    assertThat(calls).allMatch(c -> c.authorization() == null);
  }

  @Test
  void theIndexIsCreatedLowerCasedWithADenseVectorFieldOnlyWhenEmbeddingsHaveDimensions() throws Exception {
    index(null, null, null, true).initialize(crateId);
    Call create = calls.getFirst();
    assertThat(create.method()).isEqualTo("PUT");
    assertThat(create.path()).isEqualTo("/prefix-crate-" + crateId + "-1");
    JsonNode properties = mapper.readTree(create.body()).path("mappings").path("properties");
    assertThat(properties.path("embedding").path("type").asText()).isEqualTo("dense_vector");
    assertThat(properties.path("embedding").path("similarity").asText()).isEqualTo("cosine");
    assertThat(mapper.readTree(create.body()).path("settings").has("index.knn")).isFalse();

    calls.clear();
    index(null, null, null, false).initialize(crateId);
    assertThat(mapper.readTree(calls.getFirst().body()).path("mappings").path("properties").has("embedding")).isFalse();
  }

  @Test
  void bulkFailuresInAnOtherwiseSuccessfulResponseAreReported() {
    bulkResponse = "{\"errors\":true,\"items\":[{\"index\":{\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"bad vector\"}}}]}";
    var d = document();
    assertThatThrownBy(() -> index(null, null, null, false).upsert(d, List.of()))
        .isInstanceOf(IOException.class).hasMessageContaining("mapper_parsing_exception").hasMessageContaining("bad vector");
  }

  @Test
  void aKnnSearchCarriesFilterAndCandidates() throws Exception {
    var index = index(null, null, null, true);
    index.search(new SearchIndex.SearchRequest(crateId, "who is on-call", 5, null, "chunk", "semantic"));
    Call search = calls.stream().filter(c -> c.path().endsWith("/_search")).findFirst().orElseThrow();
    JsonNode knn = mapper.readTree(search.body()).path("knn");
    assertThat(knn.path("field").asText()).isEqualTo("embedding");
    assertThat(knn.path("query_vector")).hasSize(3);
    assertThat(knn.path("num_candidates").asInt()).isGreaterThanOrEqualTo(knn.path("k").asInt());
    assertThat(knn.path("filter").get(0).path("term").path("kind").asText()).isEqualTo("chunk");
  }

  @Test
  void namespacesAreDeletedByNameBecauseWildcardDeletesAreRefused() throws Exception {
    catResponse = "[{\"index\":\"prefix-crate-" + crateId + "-1\"},{\"index\":\"prefix-crate-" + crateId + "-2\"}]";
    index(null, null, null, false).deleteNamespace(crateId);

    assertThat(calls.stream().filter(c -> c.method().equals("DELETE")).map(Call::path))
        .containsExactly("/prefix-crate-" + crateId + "-1", "/prefix-crate-" + crateId + "-2");
    assertThat(calls.stream().filter(c -> c.method().equals("DELETE")).map(Call::path)).noneMatch(p -> p.contains("*"));
  }

  @Test
  void committingAnEmptyGenerationCreatesItFirstSoTheRefreshCannotFail() throws Exception {
    index(null, null, null, false).commitGeneration(crateId, 4);
    assertThat(calls).extracting(c -> c.method() + " " + c.path()).containsExactly(
        "PUT /prefix-crate-" + crateId + "-4", "POST /prefix-crate-" + crateId + "-4/_refresh");
  }
}
