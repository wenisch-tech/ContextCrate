package tech.wenisch.contextcrate.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("contextcrate")
public record ContextCrateProperties(
    String profile,
    String role,
    Queue queue,
    Database database,
    Artifacts artifacts,
    Index index,
    Embeddings embeddings,
    Reranking reranking,
    Retrieval retrieval,
    Answering answering,
    Worker worker,
    Security security) {

  /** Compatibility constructor for callers that predate embedding and retrieval settings. */
  public ContextCrateProperties(
      String profile, String role, Queue queue, Database database, Artifacts artifacts, Index index,
      Worker worker, Security security) {
    this(profile, role, queue, database, artifacts, index, null, null, null, null, worker, security);
  }

  /** The same properties with another search index configuration, used for a crate's dedicated backend. */
  public ContextCrateProperties withIndex(Index index) {
    return new ContextCrateProperties(profile, role, queue, database, artifacts, index, embeddings, reranking, retrieval, answering, worker, security);
  }

  @ConstructorBinding
  public ContextCrateProperties {
    profile = defaulted(profile, "standalone");
    role = defaulted(role, "all");
    queue = queue == null ? new Queue("local") : queue;
    database = database == null ? new Database("h2") : database;
    artifacts =
        artifacts == null
            ? new Artifacts("filesystem", Path.of("data/artifacts"), null, null, null, null)
            : artifacts;
    index =
        index == null
            ? new Index("lucene", Path.of("data/index"), "http://localhost:9200", "contextcrate")
            : index;
    embeddings = embeddings == null ? Embeddings.defaults() : embeddings;
    reranking = reranking == null ? Reranking.defaults() : reranking;
    retrieval = retrieval == null ? new Retrieval("hybrid", "rrf", 60, 100) : retrieval;
    answering = answering == null ? Answering.defaults() : answering;
    worker = worker == null ? new Worker(8, 30, 5) : worker;
    security = security == null ? new Security("admin@contextcrate.local", "admin") : security;
  }

  public record Queue(String backend) {
    public Queue {
      backend = defaulted(backend, "local");
    }
  }

  public record Database(String backend) {
    public Database {
      backend = defaulted(backend, "h2");
    }
  }

  public record Artifacts(
      String backend, Path path, String endpoint, String region, String bucket, String prefix) {
    public Artifacts {
      backend = defaulted(backend, "filesystem");
      path = path == null ? Path.of("data/artifacts") : path;
    }
  }

  /**
   * @param endpoint base URL of OpenSearch, Elasticsearch or Qdrant (defaults to that backend's usual local port);
   *     for {@code pgvector} a JDBC URL, blank meaning the application's own PostgreSQL database
   * @param apiKey optional API key (Qdrant {@code api-key} header, Elasticsearch {@code Authorization: ApiKey})
   * @param username optional user for Elasticsearch (basic authentication) and pgvector (JDBC)
   * @param password password belonging to {@code username}
   * @param trustAllCertificates accept any TLS certificate of the Elasticsearch/Qdrant endpoint (development only)
   */
  public record Index(String backend, Path path, String endpoint, String prefix, String apiKey,
      String username, String password, boolean trustAllCertificates) {
    @ConstructorBinding
    public Index {
      backend = defaulted(backend, "lucene");
      path = path == null ? Path.of("data/index") : path;
      prefix = defaulted(prefix, "contextcrate");
      if ("pgvector".equals(backend)) endpoint = endpoint == null ? "" : endpoint.strip();
      else if (endpoint == null || endpoint.isBlank())
        endpoint = "qdrant".equals(backend) ? "http://localhost:6333" : "http://localhost:9200";
      apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
      username = username == null || username.isBlank() ? null : username.strip();
      password = password == null || password.isEmpty() ? null : password;
    }

    public Index(String backend, Path path, String endpoint, String prefix, String apiKey) {
      this(backend, path, endpoint, prefix, apiKey, null, null, false);
    }

    public Index(String backend, Path path, String endpoint, String prefix) {
      this(backend, path, endpoint, prefix, null, null, null, false);
    }
  }

  public record Embeddings(
      boolean enabled,
      String provider,
      Local local,
      OpenAiCompatible openaiCompatible) {
    static Embeddings defaults() {
      return new Embeddings(true, "local", Local.defaults(), new OpenAiCompatible(null, null, null, null, 1536, 30, 8000, true));
    }
    public Embeddings {
      provider = defaulted(provider, "local");
      local = local == null ? Local.defaults() : local;
      openaiCompatible = openaiCompatible == null ? new OpenAiCompatible(null, null, null, null, 1536, 30, 8000, true) : openaiCompatible;
    }
    public record Local(String modelId, String revision, String downloadUrl, Path cachePath, Path modelPath, int downloadTimeoutSeconds) {
      static Local defaults() {
        return new Local("Xenova/multilingual-e5-small", "97cb8f96eaed1a1f0dac821239943e854fce9c36",
            "https://huggingface.co/Xenova/multilingual-e5-small/resolve/97cb8f96eaed1a1f0dac821239943e854fce9c36",
            Path.of("data/models"), null, 120);
      }
      public Local {
        modelId = defaulted(modelId, "Xenova/multilingual-e5-small");
        revision = defaulted(revision, "97cb8f96eaed1a1f0dac821239943e854fce9c36");
        downloadUrl = defaulted(downloadUrl, "https://huggingface.co/Xenova/multilingual-e5-small/resolve/97cb8f96eaed1a1f0dac821239943e854fce9c36");
        cachePath = cachePath == null ? Path.of("data/models") : cachePath;
        downloadTimeoutSeconds = Math.max(5, downloadTimeoutSeconds);
      }
    }
    public record OpenAiCompatible(String baseUrl, String model, String apiKey, String headers, int dimensions, int timeoutSeconds, int maxInputCharacters, boolean automaticLimitRecovery) {
      public OpenAiCompatible(String baseUrl, String model, String apiKey, String headers, int dimensions, int timeoutSeconds, int maxInputCharacters) { this(baseUrl, model, apiKey, headers, dimensions, timeoutSeconds, maxInputCharacters, true); }
      public OpenAiCompatible { dimensions = Math.max(1, dimensions); timeoutSeconds = Math.max(5, timeoutSeconds); maxInputCharacters = maxInputCharacters < 256 ? 8000 : maxInputCharacters; }
    }
  }

  public record Retrieval(String defaultMode, String hybridFusion, int rrfConstant, int candidateLimit) {
    public Retrieval {
      defaultMode = defaulted(defaultMode, "hybrid");
      hybridFusion = defaulted(hybridFusion, "rrf");
      rrfConstant = Math.max(1, rrfConstant);
      candidateLimit = Math.max(10, Math.min(candidateLimit, 1000));
    }
  }

  /** Query-time cross-encoder reranking. It is disabled by default to preserve existing latency. */
  public record Reranking(boolean enabled, String provider, int candidateLimit, Local local,
                          CohereCompatible cohereCompatible) {
    static Reranking defaults() {
      return new Reranking(false, "local", 30, Local.defaults(),
          new CohereCompatible(null, null, null, 4000, 30));
    }
    public Reranking {
      provider = defaulted(provider, "local");
      candidateLimit = Math.max(1, Math.min(candidateLimit, 100));
      local = local == null ? Local.defaults() : local;
      cohereCompatible = cohereCompatible == null
          ? new CohereCompatible(null, null, null, 4000, 30) : cohereCompatible;
    }
    public record Local(String modelId, String revision, String downloadUrl, Path cachePath,
                        Path modelPath, int downloadTimeoutSeconds) {
      static Local defaults() { return new Local(null, null, null, Path.of("data/models"), null, 120); }
      public Local {
        cachePath = cachePath == null ? Path.of("data/models") : cachePath;
        downloadTimeoutSeconds = Math.max(5, downloadTimeoutSeconds);
      }
    }
    public record CohereCompatible(String baseUrl, String model, String apiKey,
                                   int maxInputCharacters, int timeoutSeconds) {
      public CohereCompatible {
        maxInputCharacters = maxInputCharacters < 256 ? 4000 : maxInputCharacters;
        timeoutSeconds = Math.max(5, timeoutSeconds);
      }
    }
  }

  public record Answering(boolean enabled, String provider, OpenAiCompatible openaiCompatible,
                          String retrievalMode, int sourceLimit, int contextTokenBudget,
                          int maxHistoryMessages, double temperature, int maxOutputTokens) {
    static Answering defaults() {
      return new Answering(false, "openai-compatible", new OpenAiCompatible(null, null, null, null, 60), "hybrid", 12, 6000, 10, 0.2, 800);
    }
    public Answering {
      provider = defaulted(provider, "openai-compatible");
      openaiCompatible = openaiCompatible == null ? new OpenAiCompatible(null, null, null, null, 60) : openaiCompatible;
      retrievalMode = defaulted(retrievalMode, "hybrid");
      sourceLimit = Math.max(1, Math.min(sourceLimit, 16));
      contextTokenBudget = Math.max(256, Math.min(contextTokenBudget, 100_000));
      maxHistoryMessages = Math.max(0, Math.min(maxHistoryMessages, 50));
      temperature = Math.max(0, Math.min(temperature, 2));
      maxOutputTokens = Math.max(32, Math.min(maxOutputTokens, 16_000));
    }
    public record OpenAiCompatible(String baseUrl, String model, String apiKey, String headers, int timeoutSeconds) {
      public OpenAiCompatible { timeoutSeconds = Math.max(5, timeoutSeconds); }
    }
  }

  public record Worker(int concurrency, int leaseSeconds, int maxAttempts) {
    public Worker {
      concurrency = concurrency < 1 ? 8 : concurrency;
      leaseSeconds = leaseSeconds < 5 ? 30 : leaseSeconds;
      maxAttempts = maxAttempts < 1 ? 5 : maxAttempts;
    }
  }

  public record Security(String initialAdminEmail, String initialAdminPassword) {
    public Security {
      initialAdminEmail = defaulted(initialAdminEmail, "admin@contextcrate.local");
      initialAdminPassword = defaulted(initialAdminPassword, "admin");
    }
  }

  private static String defaulted(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
