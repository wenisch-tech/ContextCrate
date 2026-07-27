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
    require("index", properties.index().backend(), Set.of("lucene", "opensearch"));
    require("embeddings provider", properties.embeddings().provider(), Set.of("local", "openai-compatible"));
    require("reranking provider", properties.reranking().provider(), Set.of("local", "cohere-compatible"));
    require("retrieval mode", properties.retrieval().defaultMode(), Set.of("lexical", "semantic", "hybrid"));
    require("answering provider", properties.answering().provider(), Set.of("openai-compatible"));
    require("answering retrieval mode", properties.answering().retrievalMode(), Set.of("lexical", "semantic", "hybrid"));
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

  private static void require(String name, String value, Set<String> allowed) {
    if (!allowed.contains(value))
      throw new IllegalStateException(
          "Unsupported " + name + " backend/role '" + value + "'. Allowed: " + allowed);
  }
}
