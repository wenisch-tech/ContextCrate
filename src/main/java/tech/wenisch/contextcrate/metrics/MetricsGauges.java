package tech.wenisch.contextcrate.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;
import tech.wenisch.contextcrate.queue.PipelineQueue;

/**
 * System-wide gauges and the bounded "top N" content gauges.
 *
 * <p>Queue depth and dead letters are read live on every scrape. The database-backed values
 * (crate counts, indexed documents, hottest content) are refreshed on a schedule so a scrape never
 * runs a heavy query.
 */
@Component
public class MetricsGauges {
  private static final Logger log = LoggerFactory.getLogger(MetricsGauges.class);

  private final MeterRegistry registry;
  private final PipelineQueue queue;
  private final JdbcTemplate jdbc;
  private final int topN;
  private final AtomicLong indexedDocuments = new AtomicLong();
  private final Map<Crate.Status, AtomicLong> crateStatus = new EnumMap<>(Crate.Status.class);
  private final Map<String, Series> series = new ConcurrentHashMap<>();

  public MetricsGauges(
      MeterRegistry registry,
      PipelineQueue queue,
      JdbcTemplate jdbc,
      @Value("${contextcrate.metrics.top-n:20}") int topN) {
    this.registry = registry;
    this.queue = queue;
    this.jdbc = jdbc;
    this.topN = Math.max(1, topN);
  }

  @PostConstruct
  void register() {
    for (WorkStage stage : WorkStage.values())
      Gauge.builder(CrateMetrics.PREFIX + "pipeline.queue.depth", () -> safeDepth(stage))
          .tag("stage", stage.name())
          .description("Pending pipeline work items per stage")
          .register(registry);
    Gauge.builder(CrateMetrics.PREFIX + "pipeline.dead_letters", this::safeDeadLetters)
        .description("Work items that exhausted their retries")
        .register(registry);
    Gauge.builder(CrateMetrics.PREFIX + "index.documents", indexedDocuments, AtomicLong::get)
        .description("Current document versions across all crates")
        .register(registry);
    for (Crate.Status status : Crate.Status.values()) {
      var value = new AtomicLong();
      crateStatus.put(status, value);
      Gauge.builder(CrateMetrics.PREFIX + "crates", value, AtomicLong::get)
          .tag("status", status.name())
          .description("Crates by lifecycle status")
          .register(registry);
    }
  }

  private double safeDepth(WorkStage stage) {
    try {
      return queue.depth(stage);
    } catch (RuntimeException e) {
      return Double.NaN;
    }
  }

  private double safeDeadLetters() {
    try {
      return queue.deadLetters().size();
    } catch (RuntimeException e) {
      return Double.NaN;
    }
  }

  @Scheduled(
      initialDelayString = "${contextcrate.metrics.refresh-interval-ms:30000}",
      fixedDelayString = "${contextcrate.metrics.refresh-interval-ms:30000}")
  public void refresh() {
    try {
      refreshSystem();
      refreshTopContent();
    } catch (RuntimeException e) {
      log.warn("Could not refresh metrics gauges: {}", e.getMessage());
    }
  }

  void refreshSystem() {
    Long documents =
        jdbc.queryForObject(
            "select count(*) from normalized_document where current_version = true", Long.class);
    indexedDocuments.set(documents == null ? 0 : documents);
    crateStatus.values().forEach(v -> v.set(0));
    jdbc.query(
        "select status, count(*) from crate group by status",
        rs -> {
          try {
            crateStatus.get(Crate.Status.valueOf(rs.getString(1))).set(rs.getLong(2));
          } catch (IllegalArgumentException ignored) {
            // unknown status written by a newer version
          }
        });
  }

  void refreshTopContent() {
    Map<String, Map.Entry<Tags, Long>> documents = new HashMap<>();
    jdbc.query(
        "select * from (select h.crate_id, c.name crate_name, h.document_id, d.title, d.source_uri, "
            + "sum(h.hits) hits, row_number() over (partition by h.crate_id order by sum(h.hits) desc) rn "
            + "from content_hit h join crate c on c.id = h.crate_id "
            + "left join normalized_document d on d.id = h.document_id "
            + "group by h.crate_id, c.name, h.document_id, d.title, d.source_uri) t where rn <= ?",
        rs -> {
          var tags =
              Tags.of(
                  "crate", rs.getString("crate_name"),
                  "document_id", rs.getString("document_id"),
                  "title", clip(rs.getString("title"), 80),
                  "uri", clip(rs.getString("source_uri"), 120));
          documents.put(rs.getString("crate_id") + rs.getString("document_id"),
              Map.entry(tags, rs.getLong("hits")));
        },
        topN);
    sync("content.document.hits", "Hits on the most requested documents", documents);

    Map<String, Map.Entry<Tags, Long>> chunks = new HashMap<>();
    jdbc.query(
        "select * from (select h.crate_id, c.name crate_name, h.chunk_id, d.title, k.ordinal, h.hits, "
            + "row_number() over (partition by h.crate_id order by h.hits desc) rn "
            + "from content_hit h join crate c on c.id = h.crate_id "
            + "join document_chunk k on k.id = h.chunk_id "
            + "left join normalized_document d on d.id = h.document_id) t where rn <= ?",
        rs -> {
          var tags =
              Tags.of(
                  "crate", rs.getString("crate_name"),
                  "chunk_id", rs.getString("chunk_id"),
                  "title", clip(rs.getString("title"), 80),
                  "ordinal", String.valueOf(rs.getInt("ordinal")));
          chunks.put(rs.getString("crate_id") + rs.getString("chunk_id"),
              Map.entry(tags, rs.getLong("hits")));
        },
        topN);
    sync("content.chunk.hits", "Hits on the most requested chunks", chunks);

    Map<String, Map.Entry<Tags, Long>> sources = new HashMap<>();
    jdbc.query(
        "select * from (select h.crate_id, c.name crate_name, h.source_id, s.name source_name, "
            + "sum(h.hits) hits, row_number() over (partition by h.crate_id order by sum(h.hits) desc) rn "
            + "from content_hit h join crate c on c.id = h.crate_id "
            + "join source s on s.id = h.source_id "
            + "group by h.crate_id, c.name, h.source_id, s.name) t where rn <= ?",
        rs -> {
          var tags =
              Tags.of(
                  "crate", rs.getString("crate_name"),
                  "source_id", rs.getString("source_id"),
                  "source", clip(rs.getString("source_name"), 80));
          sources.put(rs.getString("crate_id") + rs.getString("source_id"),
              Map.entry(tags, rs.getLong("hits")));
        },
        topN);
    sync("content.source.hits", "Hits on the most requested sources", sources);
  }

  /** Updates gauge values for the current top N and drops series that fell out of it. */
  private void sync(String name, String description, Map<String, Map.Entry<Tags, Long>> current) {
    String full = CrateMetrics.PREFIX + name;
    Set<String> live = new HashSet<>();
    for (var entry : current.entrySet()) {
      String key = full + "|" + entry.getKey();
      live.add(key);
      Series existing = series.get(key);
      if (existing == null) {
        var value = new AtomicLong(entry.getValue().getValue());
        var gauge =
            Gauge.builder(full, value, AtomicLong::get)
                .description(description)
                .tags(entry.getValue().getKey())
                .register(registry);
        series.put(key, new Series(full, gauge.getId(), value));
      } else existing.value.set(entry.getValue().getValue());
    }
    List<String> stale = new ArrayList<>();
    for (var entry : series.entrySet())
      if (entry.getValue().meter.equals(full) && !live.contains(entry.getKey()))
        stale.add(entry.getKey());
    for (String key : stale) {
      Series gone = series.remove(key);
      if (gone != null) registry.remove(gone.id);
    }
  }

  private static String clip(String value, int max) {
    if (value == null || value.isBlank()) return "unknown";
    return value.length() <= max ? value : value.substring(0, max);
  }

  private record Series(String meter, Meter.Id id, AtomicLong value) {}
}
