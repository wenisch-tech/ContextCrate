package tech.wenisch.contextcrate.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.repository.CrateRepository;

/**
 * Single entry point for ContextCrate's business metrics.
 *
 * <p>Every method takes the crate as a UUID and turns it into a {@code crate} tag carrying the
 * crate's <em>name</em>, which is what a Grafana variable wants to show. Names are cached briefly so
 * hot paths such as search do not hit the database. All metrics are prefixed {@code contextcrate.}
 * and always carry the {@code crate} tag as their first tag, which keeps tag keys consistent per
 * meter (Prometheus requires that).
 *
 * <p>Classes that predate metrics receive this through an optional setter and default to {@link
 * #noop()}, so existing constructors and tests keep working.
 */
@Component
public class CrateMetrics {
  private static final long NAME_TTL_NANOS = Duration.ofSeconds(60).toNanos();
  public static final String PREFIX = "contextcrate.";

  private final MeterRegistry registry;
  private final CrateRepository crates;
  private final int keyTagLimit;
  private final Map<UUID, CachedName> names = new ConcurrentHashMap<>();
  private final Set<String> knownKeys = ConcurrentHashMap.newKeySet();

  @Autowired
  public CrateMetrics(
      MeterRegistry registry,
      CrateRepository crates,
      @Value("${contextcrate.metrics.key-tag-limit:100}") int keyTagLimit) {
    this.registry = registry;
    this.crates = crates;
    this.keyTagLimit = keyTagLimit;
  }

  /** A metrics sink that records into a private registry; used until Spring injects the real one. */
  public static CrateMetrics noop() {
    return new CrateMetrics(new SimpleMeterRegistry(), null, 100);
  }

  public MeterRegistry registry() {
    return registry;
  }

  /** The crate's display name, or its id if the crate cannot be resolved. */
  public String crate(UUID crateId) {
    if (crateId == null) return "none";
    long now = System.nanoTime();
    CachedName cached = names.get(crateId);
    if (cached != null && now - cached.at < NAME_TTL_NANOS) return cached.name;
    String name = crateId.toString();
    if (crates != null) {
      try {
        var found = crates.findById(crateId);
        if (found != null && found.isPresent() && found.get().getName() != null)
          name = found.get().getName();
      } catch (RuntimeException ignored) {
        if (cached != null) return cached.name;
      }
    }
    names.put(crateId, new CachedName(name, now));
    return name;
  }

  /** Bounds the number of distinct API key names used as tag values. */
  public String keyTag(String name) {
    String clean = name == null || name.isBlank() ? "unknown" : name.replace("api-key:", "").strip();
    if (clean.length() > 60) clean = clean.substring(0, 60);
    if (knownKeys.contains(clean)) return clean;
    if (knownKeys.size() >= keyTagLimit) return "other";
    knownKeys.add(clean);
    return clean;
  }

  /** Increments {@code contextcrate.<name>} tagged with the crate plus the given key/value pairs. */
  public void count(String name, UUID crateId, String... tags) {
    registry.counter(PREFIX + name, tags(crate(crateId), tags)).increment();
  }

  /** Increments a counter that is not scoped to a crate (system-wide). */
  public void countGlobal(String name, String... tags) {
    registry.counter(PREFIX + name, Tags.of(tags)).increment();
  }

  public void time(String name, UUID crateId, long nanos, String... tags) {
    Timer.builder(PREFIX + name)
        .tags(tags(crate(crateId), tags))
        .publishPercentileHistogram()
        .register(registry)
        .record(Duration.ofNanos(Math.max(0, nanos)));
  }

  public void summary(String name, UUID crateId, double value, String... tags) {
    DistributionSummary.builder(PREFIX + name)
        .tags(tags(crate(crateId), tags))
        .register(registry)
        .record(value);
  }

  /** Records one search call: request count, latency and hit count. */
  public void search(
      String backend,
      tech.wenisch.contextcrate.index.SearchIndex.SearchRequest request,
      tech.wenisch.contextcrate.index.SearchIndex.SearchResults results,
      String outcome,
      long nanos) {
    try {
      String mode = results == null || results.mode() == null ? "unknown" : results.mode();
      String kind = request.kind() == null ? "all" : request.kind();
      count("search.requests", request.crateId(), "backend", backend, "mode", mode, "kind", kind, "outcome", outcome);
      time("search.duration", request.crateId(), nanos, "backend", backend, "mode", mode, "kind", kind);
      if (results != null) summary("search.hits", request.crateId(), results.hits().size(), "backend", backend, "mode", mode);
    } catch (RuntimeException ignored) {
      // metrics must never break a search
    }
  }

  private static Tags tags(String crate, String[] pairs) {
    return Tags.of("crate", crate).and(pairs);
  }

  private record CachedName(String name, long at) {}
}
