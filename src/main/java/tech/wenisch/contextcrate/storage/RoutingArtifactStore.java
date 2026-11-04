package tech.wenisch.contextcrate.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.domain.CrateBackendSettings;
import tech.wenisch.contextcrate.repository.CrateBackendSettingsRepository;

/**
 * The artifact store the rest of the application uses. Artifact keys start with {@code crates/{crateId}/}, so the
 * crate is known from the key: a crate with its own S3 storage reads and writes there, everything else uses the
 * installation's store.
 */
@Component
@Primary
public class RoutingArtifactStore implements ArtifactStore, DisposableBean {
  static final long CACHE_MILLIS = 60_000;
  private static final Pattern CRATE_KEY = Pattern.compile("^crates/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:/|$)");

  private record Resolved(String fingerprint, ArtifactStore store, long loadedAt) {}

  private final ArtifactStore global;
  private final CrateBackendSettingsRepository settings;
  private final ConcurrentHashMap<UUID, Resolved> cache = new ConcurrentHashMap<>();

  public RoutingArtifactStore(List<ArtifactStore> stores, CrateBackendSettingsRepository settings) {
    this.global = stores.stream().filter(s -> !(s instanceof RoutingArtifactStore)).findFirst()
        .orElseThrow(() -> new IllegalStateException("No artifact store is configured"));
    this.settings = settings;
  }

  /** Forgets the cached store of a crate (its settings changed or the crate was removed). */
  public void invalidate(UUID crateId) {
    var removed = cache.remove(crateId);
    if (removed != null) closeQuietly(removed.store());
  }

  ArtifactStore storeFor(String key) {
    Matcher m = key == null ? null : CRATE_KEY.matcher(key);
    if (m == null || !m.find()) return global;
    UUID crateId = UUID.fromString(m.group(1));
    var cached = cache.get(crateId);
    if (cached != null && System.currentTimeMillis() - cached.loadedAt() < CACHE_MILLIS) return cached.store();
    Optional<CrateBackendSettings> row = settings.findById(crateId).filter(CrateBackendSettings::customStorage);
    String fingerprint = row.map(r -> r.s3Config().fingerprint()).orElse("global");
    if (cached != null && cached.fingerprint().equals(fingerprint)) {
      cache.put(crateId, new Resolved(fingerprint, cached.store(), System.currentTimeMillis()));
      return cached.store();
    }
    ArtifactStore store = row.<ArtifactStore>map(r -> new S3ArtifactStore(r.s3Config())).orElse(global);
    var replaced = cache.put(crateId, new Resolved(fingerprint, store, System.currentTimeMillis()));
    if (replaced != null && replaced.store() != global) closeQuietly(replaced.store());
    return store;
  }

  @Override public ArtifactMetadata put(String key, InputStream content, long maximumBytes) throws IOException { return storeFor(key).put(key, content, maximumBytes); }
  @Override public InputStream open(String key) throws IOException { return storeFor(key).open(key); }
  @Override public boolean exists(String key) { return storeFor(key).exists(key); }
  @Override public void delete(String key) throws IOException { storeFor(key).delete(key); }
  @Override public void deletePrefix(String prefix) throws IOException { storeFor(prefix).deletePrefix(prefix); }

  @Override
  public void destroy() {
    cache.values().forEach(r -> closeQuietly(r.store()));
    cache.clear();
  }

  private void closeQuietly(ArtifactStore store) {
    if (store instanceof S3ArtifactStore s3 && store != global) {
      try { s3.close(); } catch (RuntimeException ignored) { /* already closed */ }
    }
  }
}
