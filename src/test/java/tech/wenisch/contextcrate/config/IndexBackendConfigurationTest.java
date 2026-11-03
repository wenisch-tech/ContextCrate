package tech.wenisch.contextcrate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class IndexBackendConfigurationTest {
  @Test
  void theEndpointDefaultsToTheUsualPortOfTheBackend() {
    assertThat(new ContextCrateProperties.Index("opensearch", null, null, null).endpoint()).isEqualTo("http://localhost:9200");
    assertThat(new ContextCrateProperties.Index("qdrant", null, " ", null).endpoint()).isEqualTo("http://localhost:6333");
    assertThat(new ContextCrateProperties.Index("qdrant", null, "http://qdrant:6333", null).endpoint()).isEqualTo("http://qdrant:6333");
  }

  @Test
  void aBlankApiKeyMeansNoApiKey() {
    assertThat(new ContextCrateProperties.Index("qdrant", Path.of("x"), null, null, "  ").apiKey()).isNull();
    assertThat(new ContextCrateProperties.Index("qdrant", Path.of("x"), null, null, " secret ").apiKey()).isEqualTo("secret");
    assertThat(new ContextCrateProperties.Index("qdrant", Path.of("x"), null, null).apiKey()).isNull();
  }

  private static ContextCrateProperties properties(String indexBackend) {
    return new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"), new ContextCrateProperties.Database("h2"), null,
        new ContextCrateProperties.Index(indexBackend, Path.of("data/index"), null, null), null, null);
  }

  @Test
  void theValidatorAcceptsLuceneOpenSearchAndQdrantOnly() {
    for (String backend : new String[] {"lucene", "opensearch", "qdrant"})
      assertThatCode(() -> new BackendConfigurationValidator(properties(backend)).run(new DefaultApplicationArguments())).doesNotThrowAnyException();
    assertThatThrownBy(() -> new BackendConfigurationValidator(properties("pinecone")).run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Allowed");
  }
}
