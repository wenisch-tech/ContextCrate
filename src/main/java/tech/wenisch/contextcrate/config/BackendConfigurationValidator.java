package tech.wenisch.contextcrate.config;

import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BackendConfigurationValidator implements ApplicationRunner {
  private final ContextCrateProperties properties;

  public BackendConfigurationValidator(ContextCrateProperties properties) {
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    require("queue", properties.queue().backend(), Set.of("local", "rabbitmq"));
    require("database", properties.database().backend(), Set.of("h2", "postgresql"));
    require("artifacts", properties.artifacts().backend(), Set.of("filesystem", "s3"));
    require("index", properties.index().backend(), Set.of("lucene", "opensearch", "elasticsearch", "qdrant", "pgvector"));
    validatePgVector();
    require("embeddings provider", properties.embeddings().provider(), Set.of("local", "openai-compatible"));
    require("reranking provider", properties.reranking().provider(), Set.of("local", "cohere-compatible"));
    require("retrieval mode", properties.retrieval().defaultMode(), Set.of("lexical", "semantic", "hybrid", "fusion"));
    require("answering provider", properties.answering().provider(), Set.of("openai-compatible"));
    require("answering retrieval mode", properties.answering().retrievalMode(), Set.of("lexical", "semantic", "hybrid", "fusion"));
    require(
        "role",
        properties.role(),
        Set.of("all", "control-plane", "crawler-http", "crawler-browser", "parser", "indexer"));
    if (properties.queue().backend().equals("local") && !properties.role().equals("all")) {
      throw new IllegalStateException(
          "contextcrate.queue.backend=local requires contextcrate.role=all; use RabbitMQ for independent worker"
              + " processes");
    }
    if (properties.database().backend().equals("h2") && !properties.role().equals("all")) {
      throw new IllegalStateException(
          "File-backed H2 requires a single process with contextcrate.role=all");
    }
  }

  /** pgvector creates tables named after the prefix, so the prefix must be a short, plain identifier. */
  private void validatePgVector() {
    var index = properties.index();
    if (!"pgvector".equals(index.backend())) return;
    if (!index.prefix().matches("[a-z][a-z0-9_]{0,19}"))
      throw new IllegalStateException("contextcrate.index.prefix must match [a-z][a-z0-9_]{0,19} for the pgvector backend, but is '"
          + index.prefix() + "'");
    if (index.endpoint().isBlank() && !"postgresql".equals(properties.database().backend()))
      throw new IllegalStateException("The pgvector backend needs contextcrate.index.endpoint (a jdbc:postgresql: URL) unless the "
          + "application database is PostgreSQL");
    if (!index.endpoint().isBlank() && !index.endpoint().startsWith("jdbc:postgresql:"))
      throw new IllegalStateException("contextcrate.index.endpoint must be a jdbc:postgresql: URL for the pgvector backend");
  }

  private static void require(String name, String value, Set<String> allowed) {
    if (!allowed.contains(value))
      throw new IllegalStateException(
          "Unsupported " + name + " backend/role '" + value + "'. Allowed: " + allowed);
  }
}
