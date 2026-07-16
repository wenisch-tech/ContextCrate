package tech.wenisch.harvex.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("harvex")
public record HarvexProperties(
    String profile,
    String role,
    Queue queue,
    Database database,
    Artifacts artifacts,
    Index index,
    Worker worker,
    Security security) {

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
