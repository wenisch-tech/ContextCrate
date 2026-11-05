package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.repository.CrateRepository;

/** Runs the purge against the real schema, so a renamed table or column cannot silently break crate deletion. */
@SpringBootTest(
    properties = {
      "contextcrate.artifacts.path=target/it-data-purge/artifacts",
      "contextcrate.index.path=target/it-data-purge/index",
      "contextcrate.embeddings.enabled=false"
    })
@ActiveProfiles("test")
class CratePurgeIntegrationTest {
  @Autowired CratePurgeWorker worker;
  @Autowired CrateRepository crates;
  @Autowired JdbcTemplate jdbc;

  @Test
  void deletesTheCrateAndItsRows() throws Exception {
    UUID id = UUID.randomUUID();
    Crate crate = crates.saveAndFlush(new Crate(id, "To delete", null, jdbc.queryForObject("select id from app_user fetch first 1 rows only", UUID.class)));
    jdbc.update("insert into content_hit(crate_id, document_id, chunk_id, hits, last_hit_at) values (?, ?, ?, 1, current_timestamp)", id, UUID.randomUUID(), UUID.randomUUID());
    jdbc.update("insert into crate_index_generation(crate_id, generation, status, configuration_fingerprint, document_count, created_at, activated_at) values (?, 1, 'ACTIVE', 'x', 0, current_timestamp, current_timestamp)", id);
    crate.deleting();
    crates.saveAndFlush(crate);

    worker.purge(id);

    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (crates.findById(id).isPresent() && System.nanoTime() < deadline) Thread.sleep(100);
    assertThat(crates.findById(id)).isEmpty();
    assertThat(jdbc.queryForObject("select count(*) from content_hit where crate_id = ?", Long.class, id)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from crate_index_generation where crate_id = ?", Long.class, id)).isZero();
  }
}
