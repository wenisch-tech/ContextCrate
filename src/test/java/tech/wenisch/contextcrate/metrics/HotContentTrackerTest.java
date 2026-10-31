package tech.wenisch.contextcrate.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class HotContentTrackerTest {
  private JdbcTemplate jdbc;
  private HotContentTracker tracker;

  @BeforeEach
  void database() {
    var ds = new DriverManagerDataSource(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    jdbc = new JdbcTemplate(ds);
    jdbc.execute("create table normalized_document (id uuid primary key, source_id uuid)");
    jdbc.execute("create table content_hit (crate_id uuid not null, document_id uuid not null, chunk_id uuid not null,"
        + " source_id uuid, hits bigint not null, last_hit_at timestamp not null,"
        + " primary key (crate_id, document_id, chunk_id))");
    tracker = new HotContentTracker(jdbc);
  }

  @Test
  void accumulatesHitsAcrossFlushesAndResolvesTheSource() {
    UUID crate = UUID.randomUUID(), document = UUID.randomUUID(), chunk = UUID.randomUUID(), source = UUID.randomUUID();
    jdbc.update("insert into normalized_document values (?, ?)", document, source);

    tracker.record(crate, document, chunk);
    tracker.record(crate, document, chunk);
    tracker.record(crate, document, null);
    tracker.flush();
    tracker.record(crate, document, chunk);
    tracker.flush();

    assertThat(hits(crate, document, chunk)).isEqualTo(3);
    assertThat(hits(crate, document, HotContentTracker.DOCUMENT_LEVEL)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select source_id from content_hit where chunk_id = ?", UUID.class, chunk))
        .isEqualTo(source);
    assertThat(tracker.pendingKeys()).isZero();
  }

  @Test
  void noopTrackerIgnoresEverything() {
    var noop = HotContentTracker.noop();
    noop.record(UUID.randomUUID(), UUID.randomUUID(), null);
    noop.flush();
    assertThat(noop.pendingKeys()).isZero();
  }

  private long hits(UUID crate, UUID document, UUID chunk) {
    return jdbc.queryForObject(
        "select hits from content_hit where crate_id = ? and document_id = ? and chunk_id = ?",
        Long.class, crate, document, chunk);
  }
}
