package tech.wenisch.contextcrate.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.StandardEnvironment;
import tech.wenisch.contextcrate.backend.ConnectionTest;
import tech.wenisch.contextcrate.backend.ConnectionTest.Status;
import tech.wenisch.contextcrate.backend.EndpointGuard;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.crawl.UrlPolicy;
import tech.wenisch.contextcrate.domain.CrateBackendSettings.IndexConfig;
import tech.wenisch.contextcrate.reranking.DisabledRerankingProvider;
import tech.wenisch.contextcrate.repository.CrateBackendSettingsRepository;
import tech.wenisch.contextcrate.repository.CrateRepository;

class SearchBackendTesterTest {
  FakeQdrantServer server;

  @BeforeEach void start() throws Exception { server = new FakeQdrantServer(); }
  @AfterEach void stop() { server.close(); }

  @SuppressWarnings("unchecked")
  SearchBackendTester tester(boolean allowPrivate) {
    var properties = new ContextCrateProperties("standalone", "all", new ContextCrateProperties.Queue("local"), new ContextCrateProperties.Database("h2"), null,
        new ContextCrateProperties.Index("lucene", java.nio.file.Path.of("data/index"), null, "contextcrate"), null, null);
    var routing = new RoutingSearchIndex(List.of(mock(SearchIndex.class)), mock(CrateBackendSettingsRepository.class), properties, new StandardEnvironment(), new ObjectMapper(),
        new SearchIndexContract.Topics(true), crateBoundReranking(), mock(CrateRepository.class), mock(ObjectProvider.class));
    return new SearchBackendTester(routing, new EndpointGuard(new UrlPolicy(allowPrivate)));
  }

  /** Like the real reranker, which resolves the provider settings of the crate in scope and fails for a crate that has none yet. */
  static tech.wenisch.contextcrate.reranking.RerankingProvider crateBoundReranking() {
    var provider = mock(tech.wenisch.contextcrate.reranking.RerankingProvider.class);
    when(provider.available()).thenThrow(new IllegalStateException("Provider settings are not initialized for crate"));
    return provider;
  }

  IndexConfig qdrant(String endpoint, String apiKey) { return new IndexConfig("qdrant", endpoint, apiKey, null, null, "probe", false); }

  @Test
  void aWorkingBackendPassesEveryStepAndLeavesNothingBehind() {
    ConnectionTest result = tester(true).test(qdrant(server.endpoint(), "k"));

    assertThat(result.ok()).isTrue();
    assertThat(result.steps()).extracting(ConnectionTest.Step::status).containsOnly(Status.OK);
    assertThat(result.steps()).extracting(ConnectionTest.Step::id).containsExactly("input", "server", "create", "keyword", "vector", "cleanup");
    assertThat(result.steps().get(1).detail()).contains("qdrant 1.15.0");
    assertThat(server.collections).isEmpty(); // the probe collection was removed again
    assertThat(server.apiKeys).isNotEmpty().allMatch("k"::equals);
  }

  @Test
  void aRejectedRequestFailsAtTheServerStepAndTheRestIsSkipped() {
    server.failWith = 401;
    ConnectionTest result = tester(true).test(qdrant(server.endpoint(), "wrong"));

    assertThat(result.ok()).isFalse();
    assertThat(result.steps()).extracting(ConnectionTest.Step::status).containsExactly(Status.OK, Status.FAILED, Status.SKIPPED, Status.SKIPPED, Status.SKIPPED, Status.SKIPPED);
    assertThat(result.steps().get(1).detail()).contains("401");
  }

  @Test
  void privateAddressesAreRefusedBeforeAnyConnectionIsMade() {
    ConnectionTest result = tester(false).test(qdrant(server.endpoint(), null));
    assertThat(result.ok()).isFalse();
    assertThat(result.steps().getFirst().status()).isEqualTo(Status.FAILED);
    assertThat(result.steps().getFirst().detail()).contains("Private network");
    assertThat(server.requests).isEmpty();
  }

  @Test
  void unknownBackendsAndMissingFieldsAreReportedOnTheFirstStep() {
    var tester = tester(true);
    assertThat(tester.test(new IndexConfig("pinecone", "http://x", null, null, null, null, false)).steps().getFirst().detail()).contains("Choose one of");
    assertThat(tester.test(new IndexConfig("qdrant", "", null, null, null, null, false)).steps().getFirst().detail()).contains("required");
    assertThat(tester.test(new IndexConfig("pgvector", "jdbc:postgresql://127.0.0.1:5432/x", null, "", null, null, false)).steps().getFirst().detail()).contains("user");
  }
}
