package tech.wenisch.contextcrate.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.answer.AnswerGenerationProvider;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.graph.GraphModel.*;

class GraphBuildServiceTest {
  private final UUID crate = UUID.randomUUID();
  private final GraphStore store = mock(GraphStore.class);
  private final AnswerGenerationProvider llm = mock(AnswerGenerationProvider.class);
  private final RuntimeProviderSettings providers = mock(RuntimeProviderSettings.class);
  private final GraphBuildService service = new GraphBuildService(store, llm, providers);
  private final UUID docA = UUID.randomUUID(), docB = UUID.randomUUID(), docC = UUID.randomUUID();

  GraphBuildServiceTest() throws Exception {
    when(providers.effectiveAnswer()).thenReturn(new RuntimeProviderSettings.Answering(true, "http://llm", "m", null, null, 30, 0, 100));
    when(llm.available()).thenReturn(true);
    when(llm.complete(any(), anyDouble())).thenReturn("{\"title\":\"Databases\",\"summary\":\"About storage.\",\"rating\":7}");
    when(store.communitiesByFingerprint(crate)).thenReturn(Map.of());
  }

  private static Mention m(UUID chunk, UUID doc, String name) { return new Mention(chunk, doc, name, "PRODUCT", "About " + name); }

  @Test
  void mergesEntitiesAndWeightsEdgesBySeparateDocuments() {
    UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID(), c3 = UUID.randomUUID(), c4 = UUID.randomUUID();
    var mentions = List.of(
        m(c1, docA, "PostgreSQL"), m(c1, docA, "ContextCrate"),
        m(c2, docB, "postgresql"), m(c2, docB, "ContextCrate"),
        m(c3, docC, "PostgreSQL"), m(c3, docC, "ContextCrate"), m(c3, docC, "Lucene"),
        m(c4, docC, "Lucene"), m(c4, docC, "Qdrant"));
    var graph = service.assemble(crate, mentions, List.of(new RelationMention(c1, docA, "ContextCrate", "PostgreSQL", "stores data in", 8)));

    assertThat(graph.entities()).hasSize(4);
    var byName = graph.entities().stream().collect(java.util.stream.Collectors.toMap(Entity::normalizedName, e -> e));
    // A hub: mentioned in three chunks.
    assertThat(byName.get("postgresql").chunkCount()).isEqualTo(3);
    assertThat(byName.get("postgresql").documentCount()).isEqualTo(3);
    assertThat(graph.entityChunks().get(byName.get("postgresql").id())).containsExactlyInAnyOrder(c1, c2, c3);
    var strong = edge(graph, byName.get("postgresql"), byName.get("contextcrate"));
    var weak = edge(graph, byName.get("lucene"), byName.get("qdrant"));
    // Co-mentioned in three separate documents plus an extracted relation: 3 + 0.8.
    assertThat(strong.weight()).isEqualTo(3.8, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(strong.documentCount()).isEqualTo(3);
    assertThat(strong.description()).isEqualTo("stores data in");
    assertThat(weak.weight()).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(strong.weight()).isGreaterThan(weak.weight());
    assertThat(byName.get("contextcrate").degree()).isEqualTo(2);
  }

  @Test
  void everyCommunityGetsASummaryAndUnchangedOnesAreReused() throws Exception {
    List<Mention> mentions = new ArrayList<>();
    // Two groups of entities, never mentioned together.
    for (int i = 0; i < 4; i++) { UUID c = UUID.randomUUID(); mentions.add(m(c, docA, "A1")); mentions.add(m(c, docA, "A2")); mentions.add(m(c, docA, "A3")); }
    for (int i = 0; i < 4; i++) { UUID c = UUID.randomUUID(); mentions.add(m(c, docB, "B1")); mentions.add(m(c, docB, "B2")); }
    var graph = service.assemble(crate, mentions, List.of());
    assertThat(graph.communities()).hasSize(2).allMatch(c -> c.title().equals("Databases") && c.level() == 0);
    verify(llm, times(2)).complete(any(), anyDouble());

    Map<String, Community> previous = new HashMap<>();
    for (var c : graph.communities()) previous.put(c.fingerprint(), c);
    when(store.communitiesByFingerprint(crate)).thenReturn(previous);
    clearInvocations(llm);
    service.assemble(crate, mentions, List.of());
    verify(llm, never()).complete(any(), anyDouble());
  }

  @Test
  void aFailedSummaryFallsBackToTheEntityList() throws Exception {
    when(llm.complete(any(), anyDouble())).thenThrow(new RuntimeException("down"));
    UUID c = UUID.randomUUID();
    var graph = service.assemble(crate, List.of(m(c, docA, "Alpha"), m(c, docA, "Beta")), List.of());
    assertThat(graph.communities()).hasSize(1);
    assertThat(graph.communities().getFirst().title()).contains("Alpha").contains("Beta");
    assertThat(graph.communities().getFirst().fingerprint()).startsWith("uncached:");
  }

  @Test
  void aFailedBuildIsRecordedAndDoesNotReplaceTheGraph() {
    when(store.mentions(crate)).thenThrow(new IllegalStateException("db down"));
    assertThatThrownBy(() -> service.build(crate)).isInstanceOf(IllegalStateException.class);
    verify(store).fail(eq(crate), contains("db down"));
    verify(store, never()).replace(any(), any());
  }

  private static Edge edge(Graph graph, Entity a, Entity b) {
    return graph.edges().stream().filter(e -> Set.of(e.source(), e.target()).equals(Set.of(a.id(), b.id()))).findFirst().orElseThrow();
  }
}
