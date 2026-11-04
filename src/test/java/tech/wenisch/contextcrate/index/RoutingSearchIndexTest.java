package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.StandardEnvironment;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.CrateBackendSettings;
import tech.wenisch.contextcrate.reranking.DisabledRerankingProvider;
import tech.wenisch.contextcrate.repository.CrateBackendSettingsRepository;
import tech.wenisch.contextcrate.repository.CrateRepository;

class RoutingSearchIndexTest {
  final UUID custom = UUID.randomUUID(), plain = UUID.randomUUID();
  final SearchIndex global = mock(SearchIndex.class);
  final CrateBackendSettingsRepository repository = mock(CrateBackendSettingsRepository.class);

  @SuppressWarnings("unchecked")
  RoutingSearchIndex routing() {
    var properties = new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"), new ContextCrateProperties.Database("h2"), null,
        new ContextCrateProperties.Index("lucene", java.nio.file.Path.of("data/index"), null, "contextcrate"), null, null);
    ObjectProvider<tech.wenisch.contextcrate.metrics.CrateMetrics> metrics = mock(ObjectProvider.class);
    return new RoutingSearchIndex(List.of(global), repository, properties, new StandardEnvironment(), new ObjectMapper(), new SearchIndexContract.Topics(true),
        new DisabledRerankingProvider(), mock(CrateRepository.class), metrics);
  }

  private CrateBackendSettings row(UUID crate, String backend, String endpoint) {
    return new CrateBackendSettings(crate).index(new CrateBackendSettings.IndexConfig(backend, endpoint, null, null, null, "crate_idx", false));
  }

  @Test
  void aCrateWithoutDedicatedBackendUsesTheGlobalIndexForEveryOperation() throws Exception {
    when(repository.findById(plain)).thenReturn(Optional.empty());
    var routing = routing();
    routing.initialize(plain);
    routing.deleteNamespace(plain);
    routing.health(plain);
    routing.commit(plain);
    verify(global).initialize(plain);
    verify(global).deleteNamespace(plain);
    verify(global).health(plain);
    verify(global).commit(plain);
  }

  @Test
  void aCrateWithDedicatedBackendGetsItsOwnInstanceOfTheChosenKind() {
    var routing = routing();
    when(repository.findById(custom)).thenReturn(Optional.of(row(custom, "elasticsearch", "http://es.example.test:9200")));
    assertThat(routing.delegate(custom)).isInstanceOf(ElasticsearchSearchIndex.class).isNotSameAs(global);
    assertThat(routing.delegate(custom)).isSameAs(routing.delegate(custom)); // cached

    UUID q = UUID.randomUUID(), o = UUID.randomUUID();
    when(repository.findById(q)).thenReturn(Optional.of(row(q, "qdrant", "http://q.example.test:6333")));
    when(repository.findById(o)).thenReturn(Optional.of(row(o, "opensearch", "http://o.example.test:9200")));
    assertThat(routing.delegate(q)).isInstanceOf(QdrantSearchIndex.class);
    assertThat(routing.delegate(o)).isInstanceOf(OpenSearchSearchIndex.class);
  }

  @Test
  void aChangedConfigurationReplacesTheCachedInstance() {
    var routing = routing();
    when(repository.findById(custom)).thenReturn(Optional.of(row(custom, "qdrant", "http://one.example.test:6333")));
    var first = routing.delegate(custom);
    routing.invalidate(custom);
    when(repository.findById(custom)).thenReturn(Optional.of(row(custom, "qdrant", "http://two.example.test:6333")));
    assertThat(routing.delegate(custom)).isNotSameAs(first);
  }

  @Test
  void luceneAndUnknownBackendsCannotBeDedicated() {
    var routing = routing();
    when(repository.findById(custom)).thenReturn(Optional.of(row(custom, "lucene", "http://x")));
    assertThatThrownBy(() -> routing.delegate(custom)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported");
  }
}
