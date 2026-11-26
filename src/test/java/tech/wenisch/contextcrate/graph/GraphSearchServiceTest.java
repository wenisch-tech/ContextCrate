package tech.wenisch.contextcrate.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.wenisch.contextcrate.answer.AnswerGenerationProvider;
import tech.wenisch.contextcrate.graph.GraphModel.*;

class GraphSearchServiceTest {
  private final UUID crate = UUID.randomUUID();
  private final GraphStore store = mock(GraphStore.class);
  private final AnswerGenerationProvider llm = mock(AnswerGenerationProvider.class);
  private final GraphSearchService service = new GraphSearchService(store, llm);

  // PostgreSQL -- ContextCrate -- Lucene -- Qdrant (chain), and an unrelated Island.
  private final UUID pg = UUID.randomUUID(), cc = UUID.randomUUID(), lucene = UUID.randomUUID(), qdrant = UUID.randomUUID(), island = UUID.randomUUID();
  private final UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID(), c3 = UUID.randomUUID(), c4 = UUID.randomUUID();

  private Graph graph() {
    return new Graph(
        List.of(entity(pg, "PostgreSQL", 3), entity(cc, "ContextCrate", 5), entity(lucene, "Lucene", 2), entity(qdrant, "Qdrant", 1), entity(island, "Island", 1)),
        List.of(edge(pg, cc, 4), edge(cc, lucene, 2), edge(lucene, qdrant, 1)),
        Map.of(pg, List.of(c1, c2), cc, List.of(c1, c2, c3), lucene, List.of(c3), qdrant, List.of(c4), island, List.of(c4)),
        List.of(new Community(UUID.randomUUID(), 0, null, "Storage", "ContextCrate stores data in PostgreSQL.", 8, 2, "f1", List.of(pg, cc)),
            new Community(UUID.randomUUID(), 0, null, "Search", "Lucene and Qdrant are search backends.", 6, 2, "f2", List.of(lucene, qdrant))));
  }

  private static Entity entity(UUID id, String name, int chunks) { return new Entity(id, name, GraphModel.normalize(name), "PRODUCT", "About " + name, chunks, 1, 1); }
  private static Edge edge(UUID a, UUID b, double w) { return new Edge(UUID.randomUUID(), a, b, w, 1, 0, a + " relates to " + b); }

  private void built() {
    when(store.status(crate)).thenReturn(new BuildStatus("READY", false, Instant.EPOCH, Instant.parse("2026-01-01T00:00:00Z"), 5, 3, 2, 1, null));
    when(store.load(crate)).thenReturn(graph());
  }

  @Test
  void localSearchStartsAtTheNamedEntitiesAndTraversesOutward() throws Exception {
    built();
    when(llm.complete(any())).thenReturn("{\"entities\":[\"postgresql\"]}");
    var result = service.search(crate, "What does PostgreSQL do?", List.of(), "local", 3, q -> List.of());
    assertThat(result.mode()).isEqualTo("local");
    String text = result.context().text();
    assertThat(text).contains("PostgreSQL (PRODUCT, named in the question)").contains("ContextCrate").contains("Lucene") // two hops
        .doesNotContain("Qdrant").doesNotContain("Island").contains("COMMUNITY REPORTS").contains("Storage");
    // Chunks mentioning the entry entity and its neighbours come first.
    assertThat(result.chunkIds()).hasSize(3).startsWith(c1);
    assertThat(result.chunkIds()).doesNotContain(c4);
  }

  @Test
  void localSearchFallsBackToTheEntitiesOfTheBestChunks() throws Exception {
    built();
    when(llm.complete(any())).thenReturn("{\"entities\":[\"something unknown\"]}");
    var result = service.search(crate, "vector search?", List.of(), "local", 4, q -> List.of(c4));
    assertThat(result.context().text()).contains("Qdrant").contains("Island");
  }

  @Test
  void localSearchWithoutAnyEntryPointReturnsNull() throws Exception {
    built();
    when(llm.complete(any())).thenReturn("{\"entities\":[]}");
    assertThat(service.search(crate, "hello", List.of(), "local", 4, q -> List.of())).isNull();
  }

  @Test
  void globalSearchMapsCommunityReportsAndKeepsTheBestRatedPartialAnswers() throws Exception {
    built();
    when(llm.complete(any())).thenReturn("{\"points\":[{\"answer\":\"Storage is PostgreSQL.\",\"score\":90},{\"answer\":\"Irrelevant.\",\"score\":0},{\"answer\":\"Search uses Lucene.\",\"score\":40}]}");
    var result = service.search(crate, "What are the main themes?", List.of(), "global", 4, q -> List.of());
    assertThat(result.mode()).isEqualTo("global");
    assertThat(result.chunkIds()).isEmpty();
    String text = result.context().text();
    assertThat(text).contains("[relevance 90] Storage is PostgreSQL.").contains("[relevance 40] Search uses Lucene.").doesNotContain("Irrelevant");
    assertThat(text.indexOf("relevance 90")).isLessThan(text.indexOf("relevance 40"));
    var prompt = ArgumentCaptor.forClass(List.class);
    verify(llm).complete(prompt.capture()); // two reports fit into one map batch
    assertThat(prompt.getValue().toString()).contains("Storage").contains("Search").contains("untrusted");
  }

  @Test
  void globalSearchWithoutRelevantPointsReturnsNull() throws Exception {
    built();
    when(llm.complete(any())).thenReturn("{\"points\":[]}");
    assertThat(service.search(crate, "themes?", List.of(), "global", 4, q -> List.of())).isNull();
  }

  @Test
  void autoModeRoutesByTheModelsChoiceAndFallsBackToLocal() throws Exception {
    built();
    when(llm.complete(any())).thenReturn("{\"mode\":\"global\"}", "{\"points\":[{\"answer\":\"Overview.\",\"score\":70}]}");
    assertThat(service.search(crate, "Give me an overview", List.of(), "auto", 4, q -> List.of()).mode()).isEqualTo("global");
    when(llm.complete(any())).thenThrow(new RuntimeException("down")).thenReturn("{\"entities\":[\"Lucene\"]}");
    assertThat(service.classify(crate, "Lucene?", List.of())).isEqualTo("local");
  }

  @Test
  void withoutABuiltGraphNothingIsSearched() throws Exception {
    when(store.status(crate)).thenReturn(BuildStatus.NONE);
    assertThat(service.search(crate, "q", List.of(), "auto", 4, q -> List.of())).isNull();
    verifyNoInteractions(llm);
  }

  @Test
  void theDeepestLevelWithFewEnoughCommunitiesIsUsedForGlobalSearch() {
    List<Community> communities = new ArrayList<>();
    communities.add(new Community(UUID.randomUUID(), 0, null, "t", "s", 1, 2, "f", List.of()));
    for (int i = 0; i < 10; i++) communities.add(new Community(UUID.randomUUID(), 1, null, "t", "s", 1, 2, "f", List.of()));
    for (int i = 0; i < 100; i++) communities.add(new Community(UUID.randomUUID(), 2, null, "t", "s", 1, 2, "f", List.of()));
    assertThat(GraphSearchService.level(new Graph(List.of(), List.of(), Map.of(), communities))).isEqualTo(1);
  }

  @Test
  void theGraphIsLoadedOncePerBuild() {
    built();
    service.graph(crate);
    service.graph(crate);
    verify(store, times(1)).load(crate);
  }
}
