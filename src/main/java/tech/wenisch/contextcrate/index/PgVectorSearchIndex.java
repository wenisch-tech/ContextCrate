package tech.wenisch.contextcrate.index;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.config.CrateContext;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.embedding.EmbeddingProvider;
import tech.wenisch.contextcrate.metrics.CrateMetrics;
import tech.wenisch.contextcrate.reranking.RerankingProvider;
import tech.wenisch.contextcrate.repository.CrateRepository;

/**
 * Search index in PostgreSQL with the pgvector extension.
 *
 * <p>One table per crate and index generation ({@code {prefix}_c{crateId}_{generation}}) holds one row per document
 * and per {@link ChunkRetrievalRecord}. Vector search uses a {@code vector(N)} column with an HNSW cosine index.
 * Keyword search uses PostgreSQL full-text search ({@code tsvector}, {@code simple} configuration, title weighted
 * A, heading B, text C, URL D) over the lexemes of ContextCrate's code-aware analyzer, ranked with {@code ts_rank};
 * unlike BM25 this has no inverse document frequency. Hybrid search fuses both lists with reciprocal-rank fusion.
 *
 * <p>The index uses its own small connection pool, so its transactions never join the application's.
 */
@Component
@ConditionalOnProperty(name = "contextcrate.index.backend", havingValue = "pgvector")
public class PgVectorSearchIndex implements SearchIndex {
  static final int INSERT_BATCH = 200, EMBED_BATCH = 32;
  private static final String COLUMNS = "id, kind, parent_id, run_id, chunk_id, url, title, ordinal, text, source_text, record_type";

  private final String prefix;
  private final EmbeddingProvider embeddings;
  private final RerankingProvider reranking;
  private final ContextCrateProperties.Retrieval retrieval;
  private final CrateRepository crates;
  private final DataSource dataSource;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final Set<String> known = ConcurrentHashMap.newKeySet();
  private volatile Boolean iterativeScan;
  private CrateMetrics metrics = CrateMetrics.noop();

  /** Production constructor: a dedicated pool from the index endpoint, or from the application's PostgreSQL database. */
  @Autowired
  public PgVectorSearchIndex(ContextCrateProperties properties, Environment environment, EmbeddingProvider embeddings, RerankingProvider reranking,
      CrateRepository crates) {
    this(properties, pool(properties.index(), environment), embeddings, reranking, crates);
  }

  /** Uses the given data source (tests). */
  PgVectorSearchIndex(ContextCrateProperties properties, DataSource dataSource, EmbeddingProvider embeddings, RerankingProvider reranking, CrateRepository crates) {
    this.prefix = properties.index().prefix();
    if (!prefix.matches("[a-z][a-z0-9_]{0,19}"))
      throw new IllegalStateException("contextcrate.index.prefix must match [a-z][a-z0-9_]{0,19} for the pgvector backend");
    this.embeddings = embeddings;
    this.reranking = reranking;
    this.retrieval = properties.retrieval();
    this.crates = crates;
    this.dataSource = dataSource;
    this.jdbc = new JdbcTemplate(dataSource);
    this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  private static DataSource pool(ContextCrateProperties.Index index, Environment environment) {
    String url = index.endpoint(), user = index.username(), password = index.password();
    if (url.isBlank()) { // use the application's own PostgreSQL database
      url = environment.getProperty("spring.datasource.url", "");
      if (!url.startsWith("jdbc:postgresql:"))
        throw new IllegalStateException("The pgvector backend needs contextcrate.index.endpoint (a jdbc:postgresql: URL) because the application database is not PostgreSQL");
      if (user == null) user = environment.getProperty("spring.datasource.username");
      if (password == null) password = environment.getProperty("spring.datasource.password");
    }
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(4);
    config.setConnectionTimeout(15_000);
    config.setPoolName("contextcrate-pgvector");
    config.setInitializationFailTimeout(-1); // connect on first use, not at startup
    return new HikariDataSource(config);
  }

  @Autowired(required = false)
  public void setMetrics(CrateMetrics metrics) { this.metrics = metrics; }

  @Override
  public void close() {
    if (dataSource instanceof HikariDataSource pool) pool.close();
  }

  // ---- naming and lifecycle ------------------------------------------------------------------

  private int generation(UUID crateId) { return crates.findById(crateId).orElseThrow().getActiveIndexGeneration(); }

  private String table(UUID crateId, int generation) { return PgVectorSql.table(prefix, crateId, generation); }

  @Override
  public void initialize(UUID crateId) throws IOException { ensureTable(table(crateId, generation(crateId))); }

  private void ensureTable(String name) throws IOException {
    if (known.contains(name)) return;
    try {
      try {
        jdbc.execute("create extension if not exists vector");
      } catch (DataAccessException e) {
        throw new IllegalStateException("The pgvector extension is not available in this PostgreSQL database. Use an image that includes it "
            + "(for example pgvector/pgvector) and let a privileged user run: CREATE EXTENSION vector", e);
      }
      int dimensions = embeddings.descriptor().dimensions();
      String t = PgVectorSql.quote(name);
      jdbc.execute("create table if not exists " + t + " (id uuid primary key, kind text not null, parent_id uuid not null, run_id uuid not null, "
          + "chunk_id uuid, record_id uuid, record_type text, url text, title text, language text, heading text, ordinal integer, text text, "
          + "source_text text, content_hash text, proposition boolean, embedding_model text, "
          + (dimensions > 0 ? "embedding vector(" + dimensions + "), " : "") + "tsv tsvector)");
      for (String column : List.of("parent_id", "kind", "run_id"))
        jdbc.execute("create index if not exists " + PgVectorSql.quote(name + "_" + column) + " on " + t + " (" + column + ")");
      jdbc.execute("create index if not exists " + PgVectorSql.quote(name + "_tsv") + " on " + t + " using gin (tsv)");
      if (dimensions > 0 && dimensions <= PgVectorSql.MAX_HNSW_DIMENSIONS)
        jdbc.execute("create index if not exists " + PgVectorSql.quote(name + "_hnsw") + " on " + t + " using hnsw (embedding vector_cosine_ops)");
      known.add(name);
    } catch (DataAccessException e) {
      throw new IOException("Could not prepare the pgvector table " + name + ": " + e.getMostSpecificCause().getMessage(), e);
    }
  }

  @Override public void commit(UUID crateId) { /* every write is committed */ }

  @Override public void commitGeneration(UUID crateId, int generation) { /* see commit */ }

  @Override
  public void deleteGeneration(UUID crateId, int generation) {
    String name = table(crateId, generation);
    known.remove(name);
    jdbc.execute("drop table if exists " + PgVectorSql.quote(name));
  }

  @Override
  public void deleteNamespace(UUID crateId) {
    for (String name : jdbc.queryForList("select tablename from pg_tables where schemaname = current_schema() and starts_with(tablename, ?)",
        String.class, PgVectorSql.tablePrefix(prefix, crateId))) {
      known.remove(name);
      jdbc.execute("drop table if exists " + PgVectorSql.quote(name));
    }
  }

  @Override
  public IndexHealth health(UUID crateId) {
    try {
      Long count = jdbc.queryForObject("select count(*) from " + PgVectorSql.quote(table(crateId, generation(crateId))), Long.class);
      return new IndexHealth(crateId, "pgvector", true, count == null ? 0 : count, "embeddings=" + embeddings.available());
    } catch (Exception e) {
      return new IndexHealth(crateId, "pgvector", false, 0, e.getMessage());
    }
  }

  // ---- writing -------------------------------------------------------------------------------

  @Override
  public void upsert(NormalizedDocument document, List<ChunkRetrievalRecord> chunks) throws IOException {
    upsertGeneration(document.getCrateId(), generation(document.getCrateId()), document, chunks);
  }

  private record Row(UUID id, String kind, ChunkRetrievalRecord record, String text, boolean question) {}

  @Override
  public void upsertGeneration(UUID crateId, int generation, NormalizedDocument d, List<ChunkRetrievalRecord> chunks) throws IOException {
    if (!crateId.equals(d.getCrateId())) throw new IllegalArgumentException("Index input crosses crate boundary");
    try (var ignored = CrateContext.use(crateId)) {
      String name = table(crateId, generation);
      ensureTable(name);
      List<Row> rows = new ArrayList<>();
      rows.add(new Row(d.getId(), "document", null, d.getBody() == null ? "" : d.getBody(), false));
      for (ChunkRetrievalRecord c : chunks) rows.add(new Row(c.recordId(), "chunk", c, c.retrievalText(), c.isQuestion()));
      List<float[]> vectors = embed(rows);
      boolean withVector = embeddings.descriptor().dimensions() > 0;
      String t = PgVectorSql.quote(name);
      String insert = "insert into " + t + " (id, kind, parent_id, run_id, chunk_id, record_id, record_type, url, title, language, heading, ordinal, text, "
          + "source_text, content_hash, proposition, embedding_model, " + (withVector ? "embedding, " : "") + "tsv) values "
          + "(?::uuid, ?, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + (withVector ? "?::vector, " : "")
          + "setweight(to_tsvector('simple', ?), 'A') || setweight(to_tsvector('simple', ?), 'B') || setweight(to_tsvector('simple', ?), 'C') || setweight(to_tsvector('simple', ?), 'D'))";
      transactions.executeWithoutResult(status -> {
        jdbc.update("delete from " + t + " where parent_id = ?::uuid", d.getId().toString());
        for (int from = 0; from < rows.size(); from += INSERT_BATCH) {
          List<Row> batch = rows.subList(from, Math.min(rows.size(), from + INSERT_BATCH));
          int offset = from;
          jdbc.batchUpdate(insert, batch, batch.size(), (ps, row) -> bind(ps, d, row, vectors == null ? null : vectors.get(offset + batch.indexOf(row)), withVector));
        }
      });
    } catch (DataAccessException e) {
      throw new IOException("Could not write the pgvector index: " + e.getMostSpecificCause().getMessage(), e);
    }
  }

  private void bind(PreparedStatement ps, NormalizedDocument d, Row row, float[] vector, boolean withVector) throws SQLException {
    var c = row.record();
    int i = 1;
    ps.setString(i++, row.id().toString());
    ps.setString(i++, row.kind());
    ps.setString(i++, d.getId().toString());
    ps.setString(i++, d.getRunId().toString());
    ps.setString(i++, c == null ? null : c.chunkId().toString());
    ps.setString(i++, c == null ? null : c.recordId().toString());
    ps.setString(i++, c == null ? null : c.type());
    ps.setString(i++, d.getSourceUri());
    ps.setString(i++, d.getTitle());
    ps.setString(i++, d.getLanguage());
    String heading = c == null ? null : c.heading();
    ps.setString(i++, heading);
    if (c == null) ps.setNull(i++, Types.INTEGER); else ps.setInt(i++, c.ordinal());
    ps.setString(i++, row.text());
    ps.setString(i++, c == null ? null : c.sourceContent());
    ps.setString(i++, c == null ? d.getContentHash() : c.contentHash());
    if (c == null) ps.setNull(i++, Types.BOOLEAN); else ps.setBoolean(i++, c.proposition());
    ps.setString(i++, vector == null ? null : embeddings.descriptor().modelId() + ":" + embeddings.descriptor().version());
    if (withVector) ps.setString(i++, vector == null ? null : PgVectorSql.vector(vector));
    ps.setString(i++, PgVectorSql.tsvectorText(d.getTitle()));
    ps.setString(i++, PgVectorSql.tsvectorText(heading));
    ps.setString(i++, PgVectorSql.tsvectorText(row.text()));
    ps.setString(i, PgVectorSql.tsvectorText(d.getSourceUri()));
  }

  private List<float[]> embed(List<Row> rows) throws IOException {
    if (!embeddings.available()) return null;
    try {
      float[][] out = new float[rows.size()][];
      for (boolean questions : new boolean[] {false, true}) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).question() == questions) slots.add(i);
        for (int from = 0; from < slots.size(); from += EMBED_BATCH) {
          List<Integer> part = slots.subList(from, Math.min(slots.size(), from + EMBED_BATCH));
          List<String> texts = part.stream().map(i -> rows.get(i).text()).toList();
          List<float[]> vectors = questions ? embeddings.embedQueries(texts) : embeddings.embedDocuments(texts);
          for (int i = 0; i < part.size(); i++) out[part.get(i)] = vectors.get(i);
        }
      }
      return Arrays.asList(out);
    } catch (Exception e) {
      throw new IOException("Could not create document embeddings: " + e.getMessage(), e);
    }
  }

  @Override
  public void delete(UUID crateId, UUID documentId) {
    jdbc.update("delete from " + PgVectorSql.quote(table(crateId, generation(crateId))) + " where parent_id = ?::uuid", documentId.toString());
  }

  // ---- searching -----------------------------------------------------------------------------

  @Override
  public SearchResults search(SearchRequest r) throws IOException {
    long started = System.nanoTime();
    String outcome = "ok";
    SearchResults results = null;
    try {
      results = searchScoped(r);
      return results;
    } catch (IOException | RuntimeException e) {
      outcome = "error";
      throw e;
    } finally {
      metrics.search("pgvector", r, results, outcome, System.nanoTime() - started);
    }
  }

  private SearchResults searchScoped(SearchRequest r) throws IOException {
    try (var ignored = CrateContext.use(r.crateId())) {
      String name = table(r.crateId(), generation(r.crateId()));
      ensureTable(name);
      String mode = mode(r);
      if (r.query().isBlank()) return new SearchResults(r.query(), mode, List.of());
      try {
        List<SearchHit> lexical = mode.equals("semantic") ? List.of() : lexical(name, r);
        List<SearchHit> semantic = mode.equals("lexical") ? List.of() : semantic(name, r);
        int candidates = RerankingSearchSupport.candidateLimit(r, reranking, retrieval.candidateLimit());
        List<SearchHit> found = mode.equals("hybrid") ? HybridFusion.fuse(lexical, semantic, retrieval.rrfConstant(), candidates)
            : (mode.equals("lexical") ? lexical : semantic).stream().limit(candidates).toList();
        return new SearchResults(r.query(), mode, RerankingSearchSupport.apply(r, found, reranking, metrics));
      } catch (IllegalStateException e) {
        throw e;
      } catch (Exception e) {
        throw new IOException("Search failed: " + e.getMessage(), e);
      }
    }
  }

  private String mode(SearchRequest r) { return r.mode() != null ? r.mode() : (embeddings.available() ? retrieval.defaultMode() : "lexical"); }

  private List<SearchHit> lexical(String name, SearchRequest r) {
    String query = PgVectorSql.tsquery(r.query());
    if (query.isEmpty()) return List.of();
    List<Object> args = new ArrayList<>(List.of(query));
    String filter = filters(r, args);
    args.add(retrieval.candidateLimit());
    String sql = "select " + COLUMNS + ", ts_rank(tsv, q.query, 1) as score from " + PgVectorSql.quote(name)
        + ", (select to_tsquery('simple', ?) as query) q where tsv @@ q.query" + filter + " order by score desc limit ?";
    return hits(jdbc.query(sql, (rs, n) -> row(rs), args.toArray()), r, false);
  }

  private List<SearchHit> semantic(String name, SearchRequest r) throws Exception {
    if (!embeddings.available()) throw new IllegalStateException("Semantic search requested but embeddings are unavailable");
    // HyDE: a hypothetical answer is document-like, so it is embedded as a passage, not as a query.
    String vector = PgVectorSql.vector(r.hasVectorQuery() ? embeddings.embedDocuments(List.of(r.vectorQuery())).getFirst() : embeddings.embedQuery(r.query()));
    List<Object> args = new ArrayList<>(List.of(vector));
    String filter = filters(r, args);
    args.add(vector);
    args.add(retrieval.candidateLimit());
    String sql = "select " + COLUMNS + ", 1 - (embedding <=> ?::vector) as score from " + PgVectorSql.quote(name) + " where embedding is not null" + filter
        + " order by embedding <=> ?::vector limit ?";
    int candidates = retrieval.candidateLimit();
    // HNSW returns at most ef_search rows before filters are applied; raise it so filtered queries still fill the candidate list.
    return transactions.execute(status -> {
      jdbc.queryForObject("select set_config('hnsw.ef_search', ?, true)", String.class, String.valueOf(Math.min(1000, Math.max(100, candidates))));
      if (iterativeScan()) jdbc.queryForObject("select set_config('hnsw.iterative_scan', 'relaxed_order', true)", String.class);
      return hits(jdbc.query(sql, (rs, n) -> row(rs), args.toArray()), r, true);
    });
  }

  /** pgvector 0.8 added iterative index scans, which keep filtered HNSW queries from running dry. */
  private boolean iterativeScan() {
    if (iterativeScan == null) {
      String version = jdbc.queryForObject("select extversion from pg_extension where extname = 'vector'", String.class);
      String[] parts = version == null ? new String[0] : version.split("\\.");
      iterativeScan = parts.length >= 2 && (Integer.parseInt(parts[0]) > 0 || Integer.parseInt(parts[1]) >= 8);
    }
    return iterativeScan;
  }

  private static String filters(SearchRequest r, List<Object> args) {
    StringBuilder sql = new StringBuilder();
    if (r.kind() != null) { sql.append(" and kind = ?"); args.add(r.kind()); }
    if (r.runId() != null) { sql.append(" and run_id = ?::uuid"); args.add(r.runId().toString()); }
    return sql.toString();
  }

  private record Found(String kind, UUID documentId, UUID runId, UUID chunkId, UUID id, String url, String title, Integer ordinal, String text,
      String sourceText, String recordType, float score) {}

  private static Found row(ResultSet rs) throws SQLException {
    String chunk = rs.getString("chunk_id");
    int ordinal = rs.getInt("ordinal");
    return new Found(rs.getString("kind"), UUID.fromString(rs.getString("parent_id")), UUID.fromString(rs.getString("run_id")),
        chunk == null ? null : UUID.fromString(chunk), UUID.fromString(rs.getString("id")), rs.getString("url"), rs.getString("title"),
        rs.wasNull() ? null : ordinal, rs.getString("text"), rs.getString("source_text"), rs.getString("record_type"), rs.getFloat("score"));
  }

  private List<SearchHit> hits(List<Found> found, SearchRequest r, boolean semantic) {
    Map<UUID, SearchHit> out = new LinkedHashMap<>();
    boolean first = true;
    for (Found f : found) {
      boolean chunk = f.kind().equals("chunk");
      boolean question = chunk && "question".equals(f.recordType());
      UUID id = chunk ? f.chunkId() : f.documentId();
      String retrieval = f.text() == null ? "" : f.text();
      String source = chunk && f.sourceText() != null ? f.sourceText() : retrieval;
      // A generated question is only a way to find its chunk: snippet, reranking and answer use the chunk text.
      if (question) retrieval = source;
      if (first) {
        first = false;
        metrics.count("search.top_match", r.crateId(), "type", f.recordType() == null ? f.kind() : f.recordType(), "leg", semantic ? "semantic" : "lexical");
      }
      out.putIfAbsent(id, new SearchHit(id, f.documentId(), f.runId(), f.kind(), f.title(), f.url(), chunk ? f.ordinal() : null,
          SearchSnippets.snippet(retrieval, r.query()), f.score(), semantic ? null : f.score(), semantic ? f.score() : null, null, source, retrieval));
    }
    return new ArrayList<>(out.values());
  }
}
