package tech.wenisch.contextcrate.index;

import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.backend.ConnectionTest;
import tech.wenisch.contextcrate.backend.EndpointGuard;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.CrateBackendSettings.IndexConfig;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;
import tech.wenisch.contextcrate.storage.Hashing;

/**
 * Checks a search backend before a crate uses it: the server answers and accepts the credentials, an index can be
 * created, a document with a vector can be written, keyword and vector search find it, and everything can be removed
 * again. The check runs against a throwaway crate id, so it never touches real data, and it is bounded in time.
 */
@Component
public class SearchBackendTester {
  public static final Set<String> BACKENDS = Set.of("opensearch", "elasticsearch", "qdrant", "pgvector");
  static final int TIMEOUT_SECONDS = 40;

  private final RoutingSearchIndex factory;
  private final EndpointGuard guard;
  private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "search-backend-test");
    t.setDaemon(true);
    return t;
  });

  public SearchBackendTester(RoutingSearchIndex factory, EndpointGuard guard) {
    this.factory = factory;
    this.guard = guard;
  }

  @jakarta.annotation.PreDestroy
  void shutdown() { executor.shutdownNow(); }

  public ConnectionTest test(IndexConfig config) {
    Future<ConnectionTest> future = executor.submit(() -> run(config));
    try {
      return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      return new ConnectionTest(false, List.of(new ConnectionTest.Step("timeout", "Test finished in time", ConnectionTest.Status.FAILED,
          "The backend did not answer within " + TIMEOUT_SECONDS + " seconds.")));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ConnectionTest(false, List.of());
    } catch (ExecutionException e) {
      return new ConnectionTest(false, List.of(new ConnectionTest.Step("error", "Test ran", ConnectionTest.Status.FAILED, ConnectionTest.describe(e.getCause()))));
    }
  }

  private ConnectionTest run(IndexConfig config) {
    UUID testCrate = UUID.randomUUID();
    SearchIndex[] index = new SearchIndex[1];
    var test = new ConnectionTest.Builder()
        .plan("input", "Settings are complete")
        .plan("server", "Server reachable and credentials accepted")
        .plan("create", "Create an index and write a document with a vector")
        .plan("keyword", "Keyword search finds it")
        .plan("vector", "Vector search finds it")
        .plan("cleanup", "Clean up");
    try {
      test.run(() -> {
        if (config.backend() == null || !BACKENDS.contains(config.backend())) throw new IllegalArgumentException("Choose one of: " + String.join(", ", new TreeSet<>(BACKENDS)));
        guard.check(config.endpoint(), "pgvector".equals(config.backend()));
        if ("pgvector".equals(config.backend()) && (config.username() == null || config.username().isBlank())) throw new IllegalArgumentException("A database user is required");
        index[0] = factory.create(config, new ProbeEmbeddings(), new ProbeCrates(testCrate).repository(), new tech.wenisch.contextcrate.reranking.DisabledRerankingProvider());
        return null;
      });
      test.run(() -> serverCheck(config));
      test.run(() -> {
        index[0].upsertGeneration(testCrate, 1, document(testCrate), List.of(record(testCrate)));
        return null;
      });
      test.run(() -> {
        var hits = index[0].search(new SearchIndex.SearchRequest(testCrate, "contextcrate probe", 5, null, "chunk", "lexical")).hits();
        if (hits.isEmpty()) throw new IllegalStateException("The document was written but keyword search did not return it.");
        return null;
      });
      test.run(() -> {
        var hits = index[0].search(new SearchIndex.SearchRequest(testCrate, "contextcrate probe", 5, null, "chunk", "semantic")).hits();
        if (hits.isEmpty()) throw new IllegalStateException("The document was written but vector search did not return it.");
        return null;
      });
      test.run(() -> {
        index[0].deleteGeneration(testCrate, 1);
        return null;
      });
    } finally {
      if (index[0] != null) {
        try { index[0].deleteGeneration(testCrate, 1); } catch (Exception ignored) { /* already removed or never created */ }
        try { index[0].close(); } catch (Exception ignored) { /* best effort */ }
      }
    }
    return test.build();
  }

  /** A first, cheap call that separates "cannot connect" and "wrong credentials" from later failures. */
  private String serverCheck(IndexConfig config) throws Exception {
    if ("pgvector".equals(config.backend())) {
      try (var connection = DriverManager.getConnection(config.trustAllCertificates() || tech.wenisch.contextcrate.util.InsecureSsl.globalTrustAll() ? tech.wenisch.contextcrate.util.InsecureSsl.relaxPostgresUrl(config.endpoint()) : config.endpoint(), config.username(), config.password() == null ? "" : config.password());
          var statement = connection.createStatement()) {
        String version;
        try (var rs = statement.executeQuery("select current_setting('server_version')")) { rs.next(); version = rs.getString(1); }
        try (var rs = statement.executeQuery("select count(*) from pg_available_extensions where name = 'vector'")) {
          rs.next();
          if (rs.getInt(1) == 0) throw new IllegalStateException("PostgreSQL " + version + " is reachable but does not offer the pgvector extension. Use an image or service that includes it.");
        }
        return "PostgreSQL " + version + " with pgvector";
      }
    }
    var properties = new ContextCrateProperties.Index(config.backend(), java.nio.file.Path.of("unused"), config.endpoint(), config.prefix(), config.apiKey(), config.username(), config.password(), config.trustAllCertificates());
    var http = new RestIndexClient(config.backend(), properties, "qdrant".equals(config.backend()) ? "api-key" : "elasticsearch".equals(config.backend()) ? "Authorization" : "Authorization");
    var response = http.request("GET", "/", null, Set.of(200));
    var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
    String version = root.path("version").isObject() ? root.path("version").path("number").asText() : root.path("version").asText("");
    return config.backend() + (version.isBlank() ? "" : " " + version);
  }

  private static NormalizedDocument document(UUID crate) {
    var d = new NormalizedDocument(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "https://contextcrate.invalid/connection-test", "Connection test", null, null, null,
        "contextcrate probe", Hashing.sha256("contextcrate probe"), "{}");
    d.assignCrate(crate);
    return d;
  }

  private static ChunkRetrievalRecord record(UUID crate) {
    var chunk = new DocumentChunk(UUID.randomUUID(), UUID.randomUUID(), 0, "Probe", "contextcrate probe", Hashing.sha256("contextcrate probe"));
    chunk.assignCrate(crate);
    return ChunkRetrievalRecord.standard(chunk);
  }

  /** A fixed three-dimensional embedding, so the vector path is exercised without the crate's model. */
  static final class ProbeEmbeddings implements EmbeddingProvider {
    @Override public ModelDescriptor descriptor() { return new ModelDescriptor("probe", "probe", "1", 3, true, "", ""); }
    @Override public boolean available() { return true; }
    @Override public List<float[]> embedDocuments(List<String> texts) { return texts.stream().map(t -> new float[] {1f, 0f, 0f}).toList(); }
  }

  /** Answers the one lookup the backends make (the crate and its active generation) for the throwaway crate. */
  record ProbeCrates(UUID id) {
    CrateRepository repository() {
      Crate crate = new Crate(id, "connection test", null, null);
      return (CrateRepository) java.lang.reflect.Proxy.newProxyInstance(CrateRepository.class.getClassLoader(), new Class<?>[] {CrateRepository.class}, (proxy, method, args) -> {
        if (method.getName().equals("findById") && id.equals(args[0])) return Optional.of(crate);
        if (method.getName().equals("toString")) return "ProbeCrates";
        if (method.getName().equals("hashCode")) return id.hashCode();
        if (method.getName().equals("equals")) return proxy == args[0];
        throw new UnsupportedOperationException(method.getName());
      });
    }
  }
}
