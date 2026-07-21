package tech.wenisch.harvex.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("harvex")
public record HarvexProperties(
    String profile,
    String role,
    Queue queue,
    Database database,
    Artifacts artifacts,
    Index index,
    Embeddings embeddings,
    Retrieval retrieval,
    Worker worker,
    Security security) {

  /** Compatibility constructor for callers that predate embedding and retrieval settings. */
  public HarvexProperties(
      String profile, String role, Queue queue, Database database, Artifacts artifacts, Index index,
      Worker worker, Security security) {
    this(profile, role, queue, database, artifacts, index, null, null, worker, security);
  }

  @ConstructorBinding
  public HarvexProperties {
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
            ? new Index("lucene", Path.of("data/index"), "http://localhost:9200", "harvex")
            : index;
    embeddings = embeddings == null ? Embeddings.defaults() : embeddings;
    retrieval = retrieval == null ? new Retrieval("hybrid", "rrf", 60, 100) : retrieval;
    worker = worker == null ? new Worker(8, 30, 5) : worker;
    security = security == null ? new Security("admin@harvex.local", "admin") : security;
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

  public record Index(String backend, Path path, String endpoint, String prefix) {
    public Index {
      backend = defaulted(backend, "lucene");
      path = path == null ? Path.of("data/index") : path;
      prefix = defaulted(prefix, "harvex");
    }
  }

  public record Embeddings(
      boolean enabled,
      String provider,
      Local local,
      OpenAiCompatible openaiCompatible) {
    static Embeddings defaults() {
      return new Embeddings(true, "local", Local.defaults(), new OpenAiCompatible(null, null, null, null, 1536, 30));
    }
    public Embeddings {
      provider = defaulted(provider, "local");
      local = local == null ? Local.defaults() : local;
      openaiCompatible = openaiCompatible == null ? new OpenAiCompatible(null, null, null, null, 1536, 30) : openaiCompatible;
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
    public record OpenAiCompatible(String baseUrl, String model, String apiKey, String headers, int dimensions, int timeoutSeconds) {
      public OpenAiCompatible { dimensions = Math.max(1, dimensions); timeoutSeconds = Math.max(5, timeoutSeconds); }
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

  public record Worker(int concurrency, int leaseSeconds, int maxAttempts) {
    public Worker {
      concurrency = concurrency < 1 ? 8 : concurrency;
      leaseSeconds = leaseSeconds < 5 ? 30 : leaseSeconds;
      maxAttempts = maxAttempts < 1 ? 5 : maxAttempts;
    }
  }

  public record Security(String initialAdminEmail, String initialAdminPassword) {
    public Security {
      initialAdminEmail = defaulted(initialAdminEmail, "admin@harvex.local");
      initialAdminPassword = defaulted(initialAdminPassword, "admin");
    }
  }

  private static String defaulted(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
