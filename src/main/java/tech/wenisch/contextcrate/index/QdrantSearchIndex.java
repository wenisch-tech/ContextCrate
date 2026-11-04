package tech.wenisch.contextcrate.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.config.CrateContext;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.metrics.CrateMetrics;
import tech.wenisch.contextcrate.reranking.RerankingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;

/**
 * Search index on a Qdrant server (REST API, Query API, Qdrant 1.10 or newer).
 *
 * <p>One collection per crate and index generation, named {@code {prefix}-crate-{crateId}-{generation}}.
 * Every document and every {@link ChunkRetrievalRecord} is one point. A collection has a dense vector
 * {@code dense} (cosine) for semantic search and a sparse vector {@code text} with the {@code idf}
 * modifier for keyword search (see {@link SparseVectors}); hybrid search fuses both lists with
 * reciprocal-rank fusion on the client, like the other backends.
 */
@Component
@ConditionalOnProperty(name = "contextcrate.index.backend", havingValue = "qdrant")
public class QdrantSearchIndex implements SearchIndex {
  static final String DENSE = "dense", SPARSE = "text";
  static final int UPSERT_BATCH = 64, EMBED_BATCH = 32, MAX_DOCUMENT_TEXT = 200_000;

  private final ObjectMapper mapper;
  private final RestIndexClient http;
  private final String prefix;
  private final EmbeddingProvider embeddings;
  private final RerankingProvider reranking;
  private final ContextCrateProperties.Retrieval retrieval;
  private final CrateRepository crates;
  private final Set<String> known = ConcurrentHashMap.newKeySet();
  private CrateMetrics metrics = CrateMetrics.noop();

  public QdrantSearchIndex(ContextCrateProperties properties, ObjectMapper mapper, EmbeddingProvider embeddings,
      RerankingProvider reranking, CrateRepository crates) {
    this.mapper = mapper;
    this.http = new RestIndexClient("Qdrant", properties.index(), "api-key");
    this.prefix = properties.index().prefix();
    this.embeddings = embeddings;
    this.reranking = reranking;
    this.retrieval = properties.retrieval();
    this.crates = crates;
  }

  @Autowired(required = false)
  public void setMetrics(CrateMetrics metrics) { this.metrics = metrics; }

  // ---- naming and lifecycle ------------------------------------------------------------------

  private int generation(UUID crateId) { return crates.findById(crateId).orElseThrow().getActiveIndexGeneration(); }

  private String collection(UUID crateId, int generation) { return prefix + "-crate-" + crateId + "-" + generation; }

  @Override
  public void initialize(UUID crateId) throws IOException, InterruptedException {
    ensureCollection(collection(crateId, generation(crateId)));
  }

  /** Creates the collection with its vectors and payload indexes when it does not exist yet. */
  private void ensureCollection(String name) throws IOException, InterruptedException {
    if (known.contains(name)) return;
    Map<String, Object> body = new LinkedHashMap<>();
    int dimensions = embeddings.descriptor().dimensions();
    if (dimensions > 0) body.put("vectors", Map.of(DENSE, Map.of("size", dimensions, "distance", "Cosine")));
    body.put("sparse_vectors", Map.of(SPARSE, Map.of("modifier", "idf")));
    int status = request("PUT", "/collections/" + name, mapper.writeValueAsString(body), Set.of(200, 409)).status();
    if (status == 200)
      for (String field : List.of("kind", "parent_id", "run_id", "chunk_id"))
        request("PUT", "/collections/" + name + "/index?wait=true",
            mapper.writeValueAsString(Map.of("field_name", field, "field_schema", "keyword")), Set.of(200));
    known.add(name);
  }

  @Override
  public void commit(UUID crateId) { /* every write waits for completion, so it is searchable immediately */ }

  @Override
  public void commitGeneration(UUID crateId, int generation) { /* see commit */ }

  @Override
  public void deleteGeneration(UUID crateId, int generation) throws IOException, InterruptedException {
    String name = collection(crateId, generation);
    known.remove(name);
    request("DELETE", "/collections/" + name, null, Set.of(200, 404));
  }

  @Override
  public void deleteNamespace(UUID crateId) throws IOException, InterruptedException {
    String start = prefix + "-crate-" + crateId + "-";
    for (JsonNode c : mapper.readTree(request("GET", "/collections", null, Set.of(200)).body()).path("result").path("collections")) {
      String name = c.path("name").asText();
      if (!name.startsWith(start)) continue;
      known.remove(name);
      request("DELETE", "/collections/" + name, null, Set.of(200, 404));
    }
  }

  @Override
  public IndexHealth health(UUID crateId) {
    try {
      String name = collection(crateId, generation(crateId));
      JsonNode info = mapper.readTree(request("GET", "/collections/" + name, null, Set.of(200)).body()).path("result");
      return new IndexHealth(crateId, "qdrant", true, info.path("points_count").asLong(), http.endpoint() + "; embeddings=" + embeddings.available());
    } catch (Exception e) {
      return new IndexHealth(crateId, "qdrant", false, 0, e.getMessage());
    }
  }

  // ---- writing -------------------------------------------------------------------------------

  @Override
  public void upsert(NormalizedDocument document, List<ChunkRetrievalRecord> chunks) throws IOException, InterruptedException {
    upsertGeneration(document.getCrateId(), generation(document.getCrateId()), document, chunks);
  }

  @Override
  public void upsertGeneration(UUID crateId, int generation, NormalizedDocument document, List<ChunkRetrievalRecord> chunks)
      throws IOException, InterruptedException {
    if (!crateId.equals(document.getCrateId())) throw new IllegalArgumentException("Index input crosses crate boundary");
    try (var ignored = CrateContext.use(crateId)) {
      String name = collection(crateId, generation);
      ensureCollection(name);
      List<Point> points = new ArrayList<>();
      String body = document.getBody() == null ? "" : document.getBody();
      points.add(new Point(document.getId().toString(), "document", document, null, cap(body), body, false));
      for (ChunkRetrievalRecord c : chunks)
        points.add(new Point(c.recordId().toString(), "chunk", document, c, c.retrievalText(), c.sourceContent(), c.isQuestion()));
      List<float[]> vectors = embed(points);
      deletePoints(name, document.getId());
      for (int from = 0; from < points.size(); from += UPSERT_BATCH) {
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = from; i < Math.min(points.size(), from + UPSERT_BATCH); i++) batch.add(toQdrant(points.get(i), vectors == null ? null : vectors.get(i)));
        request("PUT", "/collections/" + name + "/points?wait=true", mapper.writeValueAsString(Map.of("points", batch)), Set.of(200));
      }
    }
  }

  private record Point(String id, String kind, NormalizedDocument document, ChunkRetrievalRecord record, String text, String source, boolean question) {}

  /** Dense vectors for all points (generated questions are embedded like queries), or {@code null} without embeddings. */
  private List<float[]> embed(List<Point> points) throws IOException {
    if (!embeddings.available()) return null;
    try {
      float[][] out = new float[points.size()][];
      embedGroup(points, out, false);
      embedGroup(points, out, true);
      return Arrays.asList(out);
    } catch (Exception e) {
      throw new IOException("Could not create document embeddings: " + detail(e), e);
    }
  }

  private void embedGroup(List<Point> points, float[][] out, boolean questions) throws Exception {
    List<Integer> slots = new ArrayList<>();
    for (int i = 0; i < points.size(); i++) if (points.get(i).question() == questions) slots.add(i);
    for (int from = 0; from < slots.size(); from += EMBED_BATCH) {
      List<Integer> part = slots.subList(from, Math.min(slots.size(), from + EMBED_BATCH));
      List<String> texts = part.stream().map(i -> points.get(i).text()).toList();
      List<float[]> vectors = questions ? embeddings.embedQueries(texts) : embeddings.embedDocuments(texts);
      for (int i = 0; i < part.size(); i++) out[part.get(i)] = vectors.get(i);
    }
  }

  private Map<String, Object> toQdrant(Point point, float[] dense) {
    var d = point.document();
    var c = point.record();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("kind", point.kind());
    payload.put("parent_id", d.getId().toString());
    payload.put("run_id", d.getRunId().toString());
    payload.put("url", d.getSourceUri());
    payload.put("title", nullable(d.getTitle()));
    payload.put("language", nullable(d.getLanguage()));
    payload.put("content_hash", c == null ? d.getContentHash() : c.contentHash());
    payload.put("text", point.text());
    String heading = null;
    if (c != null) {
      heading = c.heading();
      payload.put("chunk_id", c.chunkId().toString());
      payload.put("record_id", c.recordId().toString());
      payload.put("record_type", c.type());
      payload.put("heading", nullable(heading));
      payload.put("ordinal", c.ordinal());
      payload.put("source_text", c.sourceContent());
      payload.put("proposition", c.proposition());
    }
    if (dense != null) payload.put("embedding_model", embeddings.descriptor().modelId() + ":" + embeddings.descriptor().version());
    Map<String, Object> vector = new LinkedHashMap<>();
    if (dense != null) vector.put(DENSE, dense);
    vector.put(SPARSE, SparseVectors.document(List.of(
        new SparseVectors.Field(d.getTitle(), 3), new SparseVectors.Field(heading, 2),
        new SparseVectors.Field(point.text(), 1), new SparseVectors.Field(d.getSourceUri(), 1)), SparseVectors.DEFAULT_MAX_TERMS).toQdrant());
    return Map.of("id", point.id(), "vector", vector, "payload", payload);
  }

  @Override
  public void delete(UUID crateId, UUID documentId) throws IOException, InterruptedException {
    deletePoints(collection(crateId, generation(crateId)), documentId);
  }

  private void deletePoints(String name, UUID documentId) throws IOException, InterruptedException {
    request("POST", "/collections/" + name + "/points/delete?wait=true",
        mapper.writeValueAsString(Map.of("filter", Map.of("must", List.of(match("parent_id", documentId.toString()))))), Set.of(200, 404));
  }

  // ---- searching -----------------------------------------------------------------------------

  @Override
  public SearchResults search(SearchRequest r) throws IOException, InterruptedException {
    long started = System.nanoTime();
    String outcome = "ok";
    SearchResults results = null;
    try {
      results = searchScoped(r);
      return results;
    } catch (IOException | InterruptedException | RuntimeException e) {
      outcome = "error";
      throw e;
    } finally {
      metrics.search("qdrant", r, results, outcome, System.nanoTime() - started);
    }
  }

  private SearchResults searchScoped(SearchRequest r) throws IOException, InterruptedException {
    try (var ignored = CrateContext.use(r.crateId())) {
      String name = collection(r.crateId(), generation(r.crateId()));
      ensureCollection(name);
      String mode = mode(r);
      if (r.query().isBlank()) return new SearchResults(r.query(), mode, List.of());
      try {
        List<SearchHit> lexical = mode.equals("semantic") ? List.of() : lexicalLeg(name, r);
        List<SearchHit> semantic = mode.equals("lexical") ? List.of() : semanticLeg(name, r);
        int candidates = RerankingSearchSupport.candidateLimit(r, reranking, retrieval.candidateLimit());
        List<SearchHit> found = mode.equals("hybrid") ? fuse(lexical, semantic, candidates)
            : (mode.equals("lexical") ? lexical : semantic).stream().limit(candidates).toList();
        return new SearchResults(r.query(), mode, RerankingSearchSupport.apply(r, found, reranking, metrics));
      } catch (Exception e) {
        if (e instanceof IOException io) throw io;
        if (e instanceof InterruptedException ie) throw ie;
        if (e instanceof IllegalStateException state) throw state;
        throw new IOException("Search failed", e);
      }
    }
  }

  private String mode(SearchRequest r) { return r.mode() != null ? r.mode() : (embeddings.available() ? retrieval.defaultMode() : "lexical"); }

  private List<SearchHit> lexicalLeg(String name, SearchRequest r) throws Exception {
    var sparse = SparseVectors.query(r.query());
    if (sparse.isEmpty()) return List.of();
    return query(name, r, Map.of("query", sparse.toQdrant(), "using", SPARSE), false);
  }

  private List<SearchHit> semanticLeg(String name, SearchRequest r) throws Exception {
    if (!embeddings.available()) throw new IllegalStateException("Semantic search requested but embeddings are unavailable");
    // HyDE: a hypothetical answer is document-like, so it is embedded as a passage, not as a query.
    float[] vector = r.hasVectorQuery() ? embeddings.embedDocuments(List.of(r.vectorQuery())).getFirst() : embeddings.embedQuery(r.query());
    return query(name, r, Map.of("query", vector, "using", DENSE), true);
  }

  private List<SearchHit> query(String name, SearchRequest r, Map<String, Object> leg, boolean semantic) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>(leg);
    body.put("limit", retrieval.candidateLimit());
    body.put("with_payload", true);
    List<Map<String, Object>> must = new ArrayList<>();
    if (r.kind() != null) must.add(match("kind", r.kind()));
    if (r.runId() != null) must.add(match("run_id", r.runId().toString()));
    if (!must.isEmpty()) body.put("filter", Map.of("must", must));
    JsonNode points = mapper.readTree(request("POST", "/collections/" + name + "/points/query", mapper.writeValueAsString(body), Set.of(200)).body())
        .path("result").path("points");
    Map<UUID, SearchHit> out = new LinkedHashMap<>();
    boolean first = true;
    for (JsonNode point : points) {
      SearchHit hit = hit(point, r.query(), !semantic);
      if (first) {
        first = false;
        var payload = point.path("payload");
        metrics.count("search.top_match", r.crateId(), "type", payload.path("record_type").asText(payload.path("kind").asText()),
            "leg", semantic ? "semantic" : "lexical");
      }
      out.putIfAbsent(hit.id(), hit);
    }
    return new ArrayList<>(out.values());
  }

  private static SearchHit hit(JsonNode point, String query, boolean lexical) {
    JsonNode s = point.path("payload");
    String kind = s.path("kind").asText();
    boolean chunk = kind.equals("chunk");
    UUID document = UUID.fromString(s.path("parent_id").asText());
    UUID id = UUID.fromString(chunk ? s.path("chunk_id").asText(point.path("id").asText()) : s.path("parent_id").asText());
    String retrieval = s.path("text").asText("");
    String source = chunk ? s.path("source_text").asText(retrieval) : retrieval;
    // A generated question is only a way to find its chunk: snippet, reranking and answer use the chunk text.
    if (chunk && "question".equals(s.path("record_type").asText(""))) retrieval = source;
    float score = (float) point.path("score").asDouble();
    return new SearchHit(id, document, UUID.fromString(s.path("run_id").asText()), kind, s.path("title").asText(null), s.path("url").asText(null),
        chunk ? s.path("ordinal").asInt() : null, SearchSnippets.snippet(retrieval, query), score, lexical ? score : null, lexical ? null : score, null, source, retrieval);
  }

  private List<SearchHit> fuse(List<SearchHit> lexical, List<SearchHit> semantic, int limit) {
    return HybridFusion.fuse(lexical, semantic, retrieval.rrfConstant(), limit);
  }

  // ---- helpers -------------------------------------------------------------------------------

  private static Map<String, Object> match(String key, String value) { return Map.of("key", key, "match", Map.of("value", value)); }

  private static String nullable(String value) { return value == null ? "" : value; }

  private static String cap(String value) { return value.length() > MAX_DOCUMENT_TEXT ? value.substring(0, MAX_DOCUMENT_TEXT) : value; }

  private static String detail(Exception error) {
    StringBuilder detail = new StringBuilder();
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() == null || cause.getMessage().isBlank()) continue;
      if (!detail.isEmpty()) detail.append("; caused by: ");
      detail.append(cause.getMessage());
      if (detail.length() >= 3500) break;
    }
    return detail.isEmpty() ? error.getClass().getSimpleName() : detail.toString();
  }

  private RestIndexClient.Response request(String method, String path, String body, Set<Integer> expected) throws IOException, InterruptedException {
    return http.request(method, path, body, expected);
  }
}
