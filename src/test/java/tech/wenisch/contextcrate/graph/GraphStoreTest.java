package tech.wenisch.contextcrate.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tech.wenisch.contextcrate.graph.GraphModel.*;

/** The graph tables of the real migrations, on H2 in PostgreSQL mode. */
class GraphStoreTest {
  private static GraphStore store;

  @BeforeAll
  static void database() {
    var dataSource = new DriverManagerDataSource("jdbc:h2:mem:graph-store-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    store = new GraphStore(new JdbcTemplate(dataSource));
  }

  @Test
  void dirtyCratesAreClaimedOnce() {
    UUID crate = UUID.randomUUID();
    store.markDirty(crate);
    store.markDirty(crate);
    assertThat(store.dirtyCrates()).contains(crate);
    Instant now = Instant.now();
    assertThat(store.claim(crate, now, now.minusSeconds(3600))).isTrue();
    assertThat(store.claim(crate, now, now.minusSeconds(3600))).isFalse();
    assertThat(store.status(crate).status()).isEqualTo("BUILDING");
    // Changed while building: dirty again, but a running build is not claimed twice until it is stale.
    store.markDirty(crate);
    assertThat(store.claim(crate, now, now.minusSeconds(3600))).isFalse();
    assertThat(store.claim(crate, now.plusSeconds(10), now.plusSeconds(5))).isTrue();
  }

  @Test
  void aBuiltGraphIsStoredAndLoadedAgain() {
    UUID crate = UUID.randomUUID();
    store.markDirty(crate);
    store.claim(crate, Instant.now(), Instant.now().minusSeconds(60));
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), chunk = UUID.randomUUID(), top = UUID.randomUUID(), sub = UUID.randomUUID();
    var graph = new Graph(
        List.of(new Entity(a, "Alpha", "alpha", "PRODUCT", "First", 2, 1, 1), new Entity(b, "Beta", "beta", "PRODUCT", "Second", 1, 1, 1)),
        List.of(new Edge(UUID.randomUUID(), a, b, 2.5, 2, 1, "related")),
        Map.of(a, List.of(chunk), b, List.of(chunk)),
        List.of(new Community(top, 0, null, "Top", "All of it", 8, 2, "fp-top", List.of(a, b)),
            new Community(sub, 1, top, "Sub", "Part", 3, 1, "fp-sub", List.of(a))));
    store.replace(crate, graph);

    var status = store.status(crate);
    assertThat(status.status()).isEqualTo("READY");
    assertThat(status.built()).isTrue();
    assertThat(status.entities()).isEqualTo(2);
    assertThat(status.levels()).isEqualTo(2);
    var loaded = store.load(crate);
    assertThat(loaded.entities()).extracting(Entity::name).containsExactlyInAnyOrder("Alpha", "Beta");
    assertThat(loaded.edges().getFirst().weight()).isEqualTo(2.5);
    assertThat(loaded.entityChunks().get(a)).containsExactly(chunk);
    assertThat(loaded.communities()).extracting(Community::title).containsExactly("Top", "Sub");
    assertThat(loaded.communities().get(1).parentId()).isEqualTo(top);
    assertThat(loaded.communities().get(0).members()).containsExactlyInAnyOrder(a, b);
    assertThat(store.communitiesByFingerprint(crate)).containsKeys("fp-top", "fp-sub");

    // A second build replaces the first one completely.
    store.replace(crate, new Graph(List.of(new Entity(a, "Alpha", "alpha", "PRODUCT", "First", 1, 1, 0)), List.of(), Map.of(), List.of()));
    assertThat(store.load(crate).entities()).hasSize(1);
    assertThat(store.load(crate).communities()).isEmpty();
  }

  @Test
  void failuresAreRecordedAndTheMentionQueriesRun() {
    UUID crate = UUID.randomUUID();
    store.markDirty(crate);
    store.fail(crate, "boom");
    assertThat(store.status(crate).status()).isEqualTo("FAILED");
    assertThat(store.status(crate).error()).isEqualTo("boom");
    assertThat(store.mentions(crate)).isEmpty();
    assertThat(store.relations(crate)).isEmpty();
    assertThat(store.extractionFingerprint(UUID.randomUUID())).isEmpty();
    assertThat(store.status(UUID.randomUUID())).isEqualTo(BuildStatus.NONE);
  }
}
