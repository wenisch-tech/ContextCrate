package tech.wenisch.contextcrate.metrics;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Counts which documents, chunks and sources are returned to clients.
 *
 * <p>Recording is an in-memory increment, so it is safe on the search hot path. A scheduled flush
 * writes the deltas to {@code content_hit}; {@link MetricsGauges} publishes the top N from there.
 * Prometheus never sees a per-document label beyond that bounded top N.
 */
@Component
public class HotContentTracker {
  private static final Logger log = LoggerFactory.getLogger(HotContentTracker.class);
  /** Chunk id used for hits on a whole document. */
  public static final UUID DOCUMENT_LEVEL = new UUID(0, 0);

  private final JdbcTemplate jdbc;
  private final Map<Key, LongAdder> pending = new ConcurrentHashMap<>();

  @Autowired
  public HotContentTracker(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** A tracker that records nothing; used until Spring injects the real one. */
  public static HotContentTracker noop() {
    return new HotContentTracker(null);
  }

  /** @param chunkId the returned chunk, or {@code null} when the whole document was requested */
  public void record(UUID crateId, UUID documentId, UUID chunkId) {
    if (jdbc == null || crateId == null || documentId == null) return;
    pending
        .computeIfAbsent(new Key(crateId, documentId, chunkId == null ? DOCUMENT_LEVEL : chunkId),
            k -> new LongAdder())
        .increment();
  }

  @Scheduled(fixedDelayString = "${contextcrate.metrics.flush-interval-ms:15000}")
  public void flush() {
    if (jdbc == null) return;
    Timestamp now = Timestamp.from(Instant.now());
    for (Key key : pending.keySet()) {
      LongAdder adder = pending.remove(key);
      if (adder == null) continue;
      long delta = adder.sum();
      if (delta <= 0) continue;
      try {
        upsert(key, delta, now);
      } catch (RuntimeException e) {
        log.warn("Could not persist content hit counters: {}", e.getMessage());
        pending.computeIfAbsent(key, k -> new LongAdder()).add(delta);
      }
    }
  }

  private void upsert(Key key, long delta, Timestamp now) {
    if (update(key, delta, now) > 0) return;
    try {
      jdbc.update(
          "insert into content_hit (crate_id, document_id, chunk_id, source_id, hits, last_hit_at) "
              + "values (?, ?, ?, (select source_id from normalized_document where id = ?), ?, ?)",
          key.crate, key.document, key.chunk, key.document, delta, now);
    } catch (DuplicateKeyException raced) {
      update(key, delta, now);
    }
  }

  private int update(Key key, long delta, Timestamp now) {
    return jdbc.update(
        "update content_hit set hits = hits + ?, last_hit_at = ? "
            + "where crate_id = ? and document_id = ? and chunk_id = ?",
        delta, now, key.crate, key.document, key.chunk);
  }

  int pendingKeys() {
    return pending.size();
  }

  private record Key(UUID crate, UUID document, UUID chunk) {}
}
