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
    for (String backend : new String[] {"lucene", "opensearch", "elasticsearch", "qdrant"})
      assertThatCode(() -> new BackendConfigurationValidator(properties(backend)).run(new DefaultApplicationArguments())).doesNotThrowAnyException();
    assertThatThrownBy(() -> new BackendConfigurationValidator(properties("pinecone")).run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Allowed");
  }

  private static ContextCrateProperties pgvector(String database, String endpoint, String prefix) {
    return new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"), new ContextCrateProperties.Database(database), null,
        new ContextCrateProperties.Index("pgvector", Path.of("data/index"), endpoint, prefix), null, null);
  }

  private static void validate(ContextCrateProperties properties) {
    new BackendConfigurationValidator(properties).run(new DefaultApplicationArguments());
  }

  @Test
  void elasticsearchDefaultsToTheUsualPort() {
    assertThat(new ContextCrateProperties.Index("elasticsearch", null, null, null).endpoint()).isEqualTo("http://localhost:9200");
  }

  @Test
  void pgvectorNeedsAJdbcUrlUnlessTheApplicationDatabaseIsPostgresql() {
    assertThat(new ContextCrateProperties.Index("pgvector", null, null, null).endpoint()).isEmpty();
    assertThatCode(() -> validate(pgvector("postgresql", "", "contextcrate"))).doesNotThrowAnyException();
    assertThatCode(() -> validate(pgvector("h2", "jdbc:postgresql://db:5432/idx", "contextcrate"))).doesNotThrowAnyException();
    assertThatThrownBy(() -> validate(pgvector("h2", "", "contextcrate"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("jdbc:postgresql");
    assertThatThrownBy(() -> validate(pgvector("postgresql", "http://localhost:9200", "contextcrate"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("jdbc:postgresql");
  }

  @Test
  void pgvectorPrefixMustBeAShortPlainIdentifier() {
    for (String bad : new String[] {"Bad", "1abc", "has-dash", "x".repeat(21), "a b"})
      assertThatThrownBy(() -> validate(pgvector("postgresql", "", bad))).isInstanceOf(IllegalStateException.class).hasMessageContaining("prefix");
    assertThatCode(() -> validate(pgvector("postgresql", "", "my_idx1"))).doesNotThrowAnyException();
  }

  @Test
  void credentialsAreTrimmedAndBlankMeansNone() {
    var index = new ContextCrateProperties.Index("elasticsearch", null, null, null, " key ", " elastic ", "", true);
    assertThat(index.apiKey()).isEqualTo("key");
    assertThat(index.username()).isEqualTo("elastic");
    assertThat(index.password()).isNull();
    assertThat(index.trustAllCertificates()).isTrue();
  }
}
