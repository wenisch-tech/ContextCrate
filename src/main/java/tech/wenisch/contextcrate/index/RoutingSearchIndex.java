package tech.wenisch.contextcrate.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.CrateBackendSettings;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.metrics.CrateMetrics;
import tech.wenisch.contextcrate.reranking.RerankingProvider;
import tech.wenisch.contextcrate.repository.CrateBackendSettingsRepository;
import tech.wenisch.contextcrate.repository.CrateRepository;

/**
 * The search index the rest of the application uses. Every operation names its crate, so a crate with a dedicated
 * search backend is served by its own backend instance and every other crate by the installation's index.
 */
@Component
@Primary
public class RoutingSearchIndex implements SearchIndex, DisposableBean {
  static final long CACHE_MILLIS = 60_000;

  private record Resolved(String fingerprint, SearchIndex index, long loadedAt) {}

  private final SearchIndex global;
  private final CrateBackendSettingsRepository settings;
  private final ContextCrateProperties properties;
  private final Environment environment;
  private final ObjectMapper mapper;
  private final EmbeddingProvider embeddings;
  private final RerankingProvider reranking;
  private final CrateRepository crates;
  private final ObjectProvider<CrateMetrics> metrics;
  private final ConcurrentHashMap<UUID, Resolved> cache = new ConcurrentHashMap<>();

  public RoutingSearchIndex(List<SearchIndex> indexes, CrateBackendSettingsRepository settings, ContextCrateProperties properties, Environment environment,
      ObjectMapper mapper, EmbeddingProvider embeddings, RerankingProvider reranking, CrateRepository crates, ObjectProvider<CrateMetrics> metrics) {
    this.global = indexes.stream().filter(i -> !(i instanceof RoutingSearchIndex)).findFirst()
        .orElseThrow(() -> new IllegalStateException("No search index is configured"));
    this.settings = settings;
    this.properties = properties;
    this.environment = environment;
    this.mapper = mapper;
    this.embeddings = embeddings;
    this.reranking = reranking;
    this.crates = crates;
    this.metrics = metrics;
  }

  /** Forgets the cached backend of a crate (its settings changed or the crate was removed). */
  public void invalidate(UUID crateId) {
    var removed = cache.remove(crateId);
    if (removed != null) closeQuietly(removed.index());
  }

  SearchIndex delegate(UUID crateId) {
    var cached = cache.get(crateId);
    if (cached != null && System.currentTimeMillis() - cached.loadedAt() < CACHE_MILLIS) return cached.index();
    var row = settings.findById(crateId).filter(CrateBackendSettings::customIndex);
    String fingerprint = row.map(r -> r.indexConfig().fingerprint()).orElse("global");
    if (cached != null && cached.fingerprint().equals(fingerprint)) {
      cache.put(crateId, new Resolved(fingerprint, cached.index(), System.currentTimeMillis()));
      return cached.index();
    }
    SearchIndex index = row.map(r -> create(r.indexConfig())).orElse(global);
    var replaced = cache.put(crateId, new Resolved(fingerprint, index, System.currentTimeMillis()));
    if (replaced != null) closeQuietly(replaced.index());
    return index;
  }

  /** A backend instance for explicit settings: a crate's dedicated backend or the one a connection test probes. */
  public SearchIndex create(CrateBackendSettings.IndexConfig c) {
    return create(c, embeddings, crates);
  }

  public SearchIndex create(CrateBackendSettings.IndexConfig c, EmbeddingProvider embeddings, CrateRepository crates) {
    return create(c, embeddings, crates, this.reranking);
  }

  /** @param reranking the reranker the instance uses; a connection test passes a disabled one, because the throwaway crate has no provider settings */
  public SearchIndex create(CrateBackendSettings.IndexConfig c, EmbeddingProvider embeddings, CrateRepository crates, RerankingProvider reranking) {
    var index = new ContextCrateProperties.Index(c.backend(), Path.of("unused"), c.endpoint(), c.prefix(), c.apiKey(), c.username(), c.password(), c.trustAllCertificates());
    var p = properties.withIndex(index);
    CrateMetrics m = metrics.getIfAvailable();
    switch (c.backend()) {
      case "opensearch" -> { var i = new OpenSearchSearchIndex(p, mapper, embeddings, reranking, crates); if (m != null) i.setMetrics(m); return i; }
      case "elasticsearch" -> { var i = new ElasticsearchSearchIndex(p, mapper, embeddings, reranking, crates); if (m != null) i.setMetrics(m); return i; }
      case "qdrant" -> { var i = new QdrantSearchIndex(p, mapper, embeddings, reranking, crates); if (m != null) i.setMetrics(m); return i; }
      case "pgvector" -> { var i = new PgVectorSearchIndex(p, environment, embeddings, reranking, crates); if (m != null) i.setMetrics(m); return i; }
      default -> throw new IllegalArgumentException("Unsupported search backend '" + c.backend() + "'. Choose opensearch, elasticsearch, qdrant or pgvector.");
    }
  }

  @Override public void initialize(UUID crateId) throws IOException, InterruptedException { delegate(crateId).initialize(crateId); }
  @Override public void upsert(NormalizedDocument document, List<ChunkRetrievalRecord> chunks) throws IOException, InterruptedException { delegate(document.getCrateId()).upsert(document, chunks); }
  @Override public void upsertGeneration(UUID crateId, int generation, NormalizedDocument document, List<ChunkRetrievalRecord> chunks) throws IOException, InterruptedException { delegate(crateId).upsertGeneration(crateId, generation, document, chunks); }
  @Override public SearchResults search(SearchRequest request) throws IOException, InterruptedException { return delegate(request.crateId()).search(request); }
  @Override public void delete(UUID crateId, UUID documentId) throws IOException, InterruptedException { delegate(crateId).delete(crateId, documentId); }
  @Override public void commit(UUID crateId) throws IOException, InterruptedException { delegate(crateId).commit(crateId); }
  @Override public void commitGeneration(UUID crateId, int generation) throws IOException, InterruptedException { delegate(crateId).commitGeneration(crateId, generation); }
  @Override public void deleteGeneration(UUID crateId, int generation) throws IOException, InterruptedException { delegate(crateId).deleteGeneration(crateId, generation); }
  @Override public IndexHealth health(UUID crateId) { return delegate(crateId).health(crateId); }
  @Override public void deleteNamespace(UUID crateId) throws IOException, InterruptedException { delegate(crateId).deleteNamespace(crateId); }

  @Override
  public void destroy() {
    cache.values().forEach(r -> closeQuietly(r.index()));
    cache.clear();
  }

  private void closeQuietly(SearchIndex index) {
    if (index == global) return;
    try { index.close(); } catch (Exception ignored) { /* best effort */ }
  }
}
