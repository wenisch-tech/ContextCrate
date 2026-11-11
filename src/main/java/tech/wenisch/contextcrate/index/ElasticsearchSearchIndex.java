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
 * Search index on Elasticsearch 8 or 9 (REST API, no client library).
 *
 * <p>One index per crate and index generation, named {@code {prefix}-crate-{crateId}-{generation}}, with one
 * document per source document and per {@link ChunkRetrievalRecord}. Keyword search is BM25 over title, heading,
 * text and URL with the same code-aware analyzer as OpenSearch; vector search uses a {@code dense_vector} field
 * with cosine similarity and the top-level {@code knn} search clause. Hybrid search fuses both lists with
 * reciprocal-rank fusion on the client.
 */
@Component
@ConditionalOnProperty(name = "contextcrate.index.backend", havingValue = "elasticsearch")
public class ElasticsearchSearchIndex implements SearchIndex {
  static final int BULK_BATCH = 100, EMBED_BATCH = 32;

  private final ObjectMapper mapper;
  private final RestIndexClient http;
  private final String prefix;
  private final EmbeddingProvider embeddings;
  private final RerankingProvider reranking;
  private final ContextCrateProperties.Retrieval retrieval;
  private final CrateRepository crates;
  private final Set<String> known = ConcurrentHashMap.newKeySet();
  private CrateMetrics metrics = CrateMetrics.noop();

  public ElasticsearchSearchIndex(ContextCrateProperties properties, ObjectMapper mapper, EmbeddingProvider embeddings,
      RerankingProvider reranking, CrateRepository crates) {
    this.mapper = mapper;
    this.http = new RestIndexClient("Elasticsearch", properties.index(), "Authorization");
    this.prefix = properties.index().prefix().toLowerCase(Locale.ROOT);
    this.embeddings = embeddings;
    this.reranking = reranking;
    this.retrieval = properties.retrieval();
    this.crates = crates;
  }

  @Autowired(required = false)
  public void setMetrics(CrateMetrics metrics) { this.metrics = metrics; }

  // ---- naming and lifecycle ------------------------------------------------------------------

  private int generation(UUID crateId) { return crates.findById(crateId).orElseThrow().getActiveIndexGeneration(); }

  private String index(UUID crateId, int generation) { return prefix + "-crate-" + crateId + "-" + generation; }

  @Override
  public void initialize(UUID crateId) throws IOException, InterruptedException { ensureIndex(index(crateId, generation(crateId))); }

  private void ensureIndex(String name) throws IOException, InterruptedException {
    if (known.contains(name)) return;
    Map<String, Object> properties = new LinkedHashMap<>();
    for (String key : List.of("kind", "parent_id", "run_id", "url", "language", "content_hash", "chunk_id", "record_id", "record_type", "embedding_model"))
      properties.put(key, Map.of("type", "keyword"));
    for (String key : List.of("url_text", "title", "heading", "text"))
      properties.put(key, Map.of("type", "text", "analyzer", "code"));
    properties.put("source_text", Map.of("type", "text", "index", false)); // only returned, never searched
    properties.put("ordinal", Map.of("type", "integer"));
    properties.put("proposition", Map.of("type", "boolean"));
    int dimensions = embeddings.descriptor().dimensions();
    if (dimensions > 0) properties.put("embedding", Map.of("type", "dense_vector", "dims", dimensions, "index", true, "similarity", "cosine"));
    Map<String, Object> analysis = Map.of(
        "filter", Map.of("code_words", Map.of("type", "word_delimiter_graph", "split_on_case_change", true, "split_on_numerics", true, "preserve_original", true)),
        "analyzer", Map.of("code", Map.of("type", "custom", "tokenizer", "standard", "filter", List.of("code_words", "lowercase"))));
    var response = http.request("PUT", "/" + name, mapper.writeValueAsString(Map.of("settings", Map.of("analysis", analysis), "mappings", Map.of("properties", properties))),
        Set.of(200, 400));
    if (response.status() == 400 && !response.body().contains("resource_already_exists_exception"))
      throw new IOException("Elasticsearch PUT /" + name + " returned 400: " + response.body());
    known.add(name);
  }

  @Override
  public void commit(UUID crateId) throws IOException, InterruptedException { commitGeneration(crateId, generation(crateId)); }

  @Override
  public void commitGeneration(UUID crateId, int generation) throws IOException, InterruptedException {
    String name = index(crateId, generation);
    ensureIndex(name); // a generation without documents (an empty crate) has not been created by a write yet
    http.request("POST", "/" + name + "/_refresh", "", Set.of(200));
  }

  @Override
  public void deleteGeneration(UUID crateId, int generation) throws IOException, InterruptedException {
    String name = index(crateId, generation);
    known.remove(name);
    http.request("DELETE", "/" + name, null, Set.of(200, 404));
  }

  /** Lists the crate's indexes and deletes them by name: Elasticsearch 8 refuses wildcard deletes by default. */
  @Override
  public void deleteNamespace(UUID crateId) throws IOException, InterruptedException {
    String pattern = prefix + "-crate-" + crateId + "-*";
    var listing = http.request("GET", "/_cat/indices/" + pattern + "?format=json&h=index", null, Set.of(200, 404));
    if (listing.status() == 404) return;
    for (JsonNode row : mapper.readTree(listing.body())) {
      String name = row.path("index").asText();
      known.remove(name);
      http.request("DELETE", "/" + name, null, Set.of(200, 404));
    }
  }

  @Override
  public IndexHealth health(UUID crateId) {
    try {
      long count = mapper.readTree(http.request("GET", "/" + index(crateId, generation(crateId)) + "/_count", null, Set.of(200)).body()).path("count").asLong();
      return new IndexHealth(crateId, "elasticsearch", true, count, http.endpoint() + "; embeddings=" + embeddings.available());
    } catch (Exception e) {
      return new IndexHealth(crateId, "elasticsearch", false, 0, e.getMessage());
    }
  }

  // ---- writing -------------------------------------------------------------------------------

  @Override
  public void upsert(NormalizedDocument document, List<ChunkRetrievalRecord> chunks) throws IOException, InterruptedException {
    upsertGeneration(document.getCrateId(), generation(document.getCrateId()), document, chunks);
  }

  private record Entry(String id, String kind, ChunkRetrievalRecord record, String text, boolean question) {}

  @Override
  public void upsertGeneration(UUID crateId, int generation, NormalizedDocument document, List<ChunkRetrievalRecord> chunks)
      throws IOException, InterruptedException {
    if (!crateId.equals(document.getCrateId())) throw new IllegalArgumentException("Index input crosses crate boundary");
    try (var ignored = CrateContext.use(crateId)) {
      String name = index(crateId, generation);
      ensureIndex(name);
      List<Entry> entries = new ArrayList<>();
      entries.add(new Entry(document.getId().toString(), "document", null, document.getBody(), false));
      for (ChunkRetrievalRecord c : chunks) entries.add(new Entry(c.recordId().toString(), "chunk", c, c.retrievalText(), c.isQuestion()));
      List<float[]> vectors = embed(entries);
      deleteDocument(name, document.getId());
      for (int from = 0; from < entries.size(); from += BULK_BATCH) {
        StringBuilder bulk = new StringBuilder();
        for (int i = from; i < Math.min(entries.size(), from + BULK_BATCH); i++) {
          Entry e = entries.get(i);
          bulk.append(mapper.writeValueAsString(Map.of("index", Map.of("_index", name, "_id", e.id())))).append('\n')
              .append(mapper.writeValueAsString(source(document, e, vectors == null ? null : vectors.get(i)))).append('\n');
        }
        checkBulk(http.request("POST", "/_bulk?refresh=wait_for", bulk.toString(), "application/x-ndjson", Set.of(200)).body());
      }
    }
  }

  /** A bulk request answers 200 even when single documents failed; those failures must not go unnoticed. */
  private void checkBulk(String body) throws IOException {
    JsonNode response = mapper.readTree(body);
    if (!response.path("errors").asBoolean(false)) return;
    for (JsonNode item : response.path("items")) {
      JsonNode error = item.path("index").path("error");
      if (!error.isMissingNode()) throw new IOException("Elasticsearch bulk indexing failed: " + error.path("type").asText() + ": " + error.path("reason").asText());
    }
    throw new IOException("Elasticsearch bulk indexing failed");
  }

  private List<float[]> embed(List<Entry> entries) throws IOException {
    if (!embeddings.available()) return null;
    try {
      float[][] out = new float[entries.size()][];
      for (boolean questions : new boolean[] {false, true}) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).question() == questions) slots.add(i);
        for (int from = 0; from < slots.size(); from += EMBED_BATCH) {
          List<Integer> part = slots.subList(from, Math.min(slots.size(), from + EMBED_BATCH));
          List<String> texts = part.stream().map(i -> entries.get(i).text()).toList();
          List<float[]> vectors = questions ? embeddings.embedQueries(texts) : embeddings.embedDocuments(texts);
          for (int i = 0; i < part.size(); i++) out[part.get(i)] = vectors.get(i);
        }
      }
      return Arrays.asList(out);
    } catch (Exception e) {
      throw new IOException("Could not create document embeddings: " + e.getMessage(), e);
    }
  }

  private Map<String, Object> source(NormalizedDocument d, Entry entry, float[] vector) {
    var c = entry.record();
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("kind", entry.kind());
    s.put("parent_id", d.getId().toString());
    s.put("run_id", d.getRunId().toString());
    s.put("url", d.getSourceUri());
    s.put("url_text", d.getSourceUri());
    s.put("title", nullable(d.getTitle()));
    s.put("language", nullable(d.getLanguage()));
    s.put("text", entry.text());
    s.put("content_hash", c == null ? d.getContentHash() : c.contentHash());
    if (c != null) {
      s.put("chunk_id", c.chunkId().toString());
      s.put("record_id", c.recordId().toString());
      s.put("record_type", c.type());
      s.put("heading", nullable(c.heading()));
      s.put("ordinal", c.ordinal());
      s.put("source_text", c.sourceContent());
      s.put("proposition", c.proposition());
    }
    if (vector != null) {
      s.put("embedding", vector);
      s.put("embedding_model", embeddings.descriptor().modelId() + ":" + embeddings.descriptor().version());
    }
    return s;
  }

  @Override
  public void delete(UUID crateId, UUID documentId) throws IOException, InterruptedException {
    deleteDocument(index(crateId, generation(crateId)), documentId);
  }

  private void deleteDocument(String name, UUID documentId) throws IOException, InterruptedException {
    http.request("POST", "/" + name + "/_delete_by_query?refresh=true&conflicts=proceed",
        mapper.writeValueAsString(Map.of("query", Map.of("term", Map.of("parent_id", documentId.toString())))), Set.of(200, 404));
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
      metrics.search("elasticsearch", r, results, outcome, System.nanoTime() - started);
    }
  }

  private SearchResults searchScoped(SearchRequest r) throws IOException, InterruptedException {
    try (var ignored = CrateContext.use(r.crateId())) {
      String name = index(r.crateId(), generation(r.crateId()));
      ensureIndex(name);
      String mode = mode(r);
      if (r.query().isBlank()) return new SearchResults(r.query(), mode, List.of());
      try {
        List<SearchHit> lexical, semantic;
        if (mode.equals("fusion")) {
          var legs = ParallelLegs.run(r.crateId(), () -> search(name, r, false), () -> search(name, r, true));
          lexical = legs.lexical(); semantic = legs.semantic();
        } else {
          lexical = mode.equals("semantic") ? List.of() : search(name, r, false);
          semantic = mode.equals("lexical") ? List.of() : search(name, r, true);
        }
        int candidates = RerankingSearchSupport.candidateLimit(r, reranking, retrieval.candidateLimit());
        List<SearchHit> found = mode.equals("fusion") ? HybridFusion.fuseNormalized(lexical, semantic, r.vectorWeight(), candidates)
            : mode.equals("hybrid") ? HybridFusion.fuse(lexical, semantic, retrieval.rrfConstant(), candidates)
            : (mode.equals("lexical") ? lexical : semantic).stream().limit(candidates).toList();
        return new SearchResults(r.query(), mode, RerankingSearchSupport.apply(r, found, reranking, metrics));
      } catch (IOException | InterruptedException | IllegalStateException e) {
        throw e;
      } catch (Exception e) {
        throw new IOException("Search failed", e);
      }
    }
  }

  private String mode(SearchRequest r) { return r.mode() != null ? r.mode() : (embeddings.available() ? retrieval.defaultMode() : "lexical"); }

  private List<SearchHit> search(String name, SearchRequest r, boolean semantic) throws Exception {
    int limit = retrieval.candidateLimit();
    List<Map<String, Object>> filters = new ArrayList<>();
    if (r.kind() != null) filters.add(Map.of("term", Map.of("kind", r.kind())));
    if (r.runId() != null) filters.add(Map.of("term", Map.of("run_id", r.runId().toString())));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("size", limit);
    if (semantic) {
      if (!embeddings.available()) throw new IllegalStateException("Semantic search requested but embeddings are unavailable");
      // HyDE: a hypothetical answer is document-like, so it is embedded as a passage, not as a query.
      float[] vector = r.hasVectorQuery() ? embeddings.embedDocuments(List.of(r.vectorQuery())).getFirst() : embeddings.embedQuery(r.query());
      Map<String, Object> knn = new LinkedHashMap<>();
      knn.put("field", "embedding");
      knn.put("query_vector", vector);
      knn.put("k", limit);
      knn.put("num_candidates", Math.max(limit * 2, 100));
      if (!filters.isEmpty()) knn.put("filter", filters);
      body.put("knn", knn);
    } else {
      Map<String, Object> bool = new LinkedHashMap<>();
      bool.put("must", List.of(Map.of("multi_match", Map.of("query", r.query(), "fields", List.of("title^3", "heading^2", "text", "url_text")))));
      if (!filters.isEmpty()) bool.put("filter", filters);
      body.put("query", Map.of("bool", bool));
      body.put("highlight", Map.of("fields", Map.of("text", Map.of(), "heading", Map.of())));
    }
    JsonNode hits = mapper.readTree(http.request("POST", "/" + name + "/_search", mapper.writeValueAsString(body), Set.of(200)).body()).path("hits").path("hits");
    Map<UUID, SearchHit> out = new LinkedHashMap<>();
    boolean first = true;
    for (JsonNode h : hits) {
      SearchHit hit = hit(h, r.query(), !semantic);
      if (first) {
        first = false;
        var src = h.path("_source");
        metrics.count("search.top_match", r.crateId(), "type", src.path("record_type").asText(src.path("kind").asText()), "leg", semantic ? "semantic" : "lexical");
      }
      out.putIfAbsent(hit.id(), hit);
    }
    return new ArrayList<>(out.values());
  }

  private static SearchHit hit(JsonNode h, String query, boolean lexical) {
    JsonNode s = h.path("_source");
    String kind = s.path("kind").asText();
    boolean chunk = kind.equals("chunk");
    boolean question = chunk && "question".equals(s.path("record_type").asText(""));
    UUID document = UUID.fromString(s.path("parent_id").asText());
    UUID id = UUID.fromString(chunk ? s.path("chunk_id").asText(h.path("_id").asText()) : s.path("parent_id").asText());
    String retrieval = s.path("text").asText("");
    String source = chunk ? s.path("source_text").asText(retrieval) : retrieval;
    // A generated question is only a way to find its chunk: snippet, reranking and answer use the chunk text.
    if (question) retrieval = source;
    JsonNode highlight = h.path("highlight").path("text");
    String snippet = !question && highlight.isArray() && highlight.size() > 0 ? highlight.get(0).asText() : SearchSnippets.snippet(retrieval, query);
    float score = (float) h.path("_score").asDouble();
    return new SearchHit(id, document, UUID.fromString(s.path("run_id").asText()), kind, s.path("title").asText(null), s.path("url").asText(null),
        chunk ? s.path("ordinal").asInt() : null, snippet, score, lexical ? score : null, lexical ? null : score, null, source, retrieval);
  }

  private static String nullable(String value) { return value == null ? "" : value; }
}
