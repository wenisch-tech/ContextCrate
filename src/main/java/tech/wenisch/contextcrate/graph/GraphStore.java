package tech.wenisch.contextcrate.graph;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.graph.GraphModel.*;

/**
 * Persistence of the knowledge graph. Plain JDBC: the built graph is written and read as a whole, so batch
 * statements are simpler and much faster than one entity object per row.
 */
@Repository
public class GraphStore {
  private final JdbcTemplate jdbc;

  public GraphStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  // ---- per-chunk extraction cache -------------------------------------------------------------

  public Optional<String> extractionFingerprint(UUID chunkId) {
    return jdbc.query("select fingerprint from graph_chunk_extraction where chunk_id = ?", (rs, i) -> rs.getString(1), chunkId).stream().findFirst();
  }

  /** Replaces what was extracted from a chunk. */
  @Transactional
  public void saveExtraction(UUID crateId, UUID chunkId, UUID documentId, String fingerprint, String model, List<Mention> entities, List<RelationMention> relations) {
    jdbc.update("delete from graph_chunk_relation where chunk_id = ?", chunkId);
    jdbc.update("delete from graph_chunk_entity where chunk_id = ?", chunkId);
    jdbc.update("delete from graph_chunk_extraction where chunk_id = ?", chunkId);
    jdbc.update("insert into graph_chunk_extraction(chunk_id, crate_id, document_id, fingerprint, model, created_at) values (?,?,?,?,?,?)",
        chunkId, crateId, documentId, fingerprint, model, Timestamp.from(Instant.now()));
    jdbc.batchUpdate("insert into graph_chunk_entity(id, crate_id, chunk_id, name, normalized_name, type, description) values (?,?,?,?,?,?,?)",
        entities.stream().map(e -> new Object[] {UUID.randomUUID(), crateId, chunkId, e.name(), e.key(), e.type(), e.description()}).toList());
    jdbc.batchUpdate("insert into graph_chunk_relation(id, crate_id, chunk_id, source_name, target_name, description, strength) values (?,?,?,?,?,?,?)",
        relations.stream().map(r -> new Object[] {UUID.randomUUID(), crateId, chunkId, r.source(), r.target(), r.description(), r.strength()}).toList());
  }

  private static final String CURRENT_CHUNKS = " from %s x join document_chunk c on c.id = x.chunk_id join normalized_document d on d.id = c.document_id"
      + " where x.crate_id = ? and d.current_version = true";

  /** Entity mentions of the chunks of current document versions. */
  public List<Mention> mentions(UUID crateId) {
    return jdbc.query("select x.chunk_id, c.document_id, x.name, x.type, x.description" + CURRENT_CHUNKS.formatted("graph_chunk_entity"),
        (rs, i) -> new Mention(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5)), crateId);
  }

  public List<RelationMention> relations(UUID crateId) {
    return jdbc.query("select x.chunk_id, c.document_id, x.source_name, x.target_name, x.description, x.strength" + CURRENT_CHUNKS.formatted("graph_chunk_relation"),
        (rs, i) -> new RelationMention(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)), crateId);
  }

  // ---- build state -----------------------------------------------------------------------------

  /** Marks the graph of a crate as out of date; the build job picks it up once indexing is idle. */
  public void markDirty(UUID crateId) {
    if (jdbc.update("update graph_build set dirty = true where crate_id = ?", crateId) > 0) return;
    try { jdbc.update("insert into graph_build(crate_id, status, dirty) values (?, 'NONE', true)", crateId); }
    catch (DataIntegrityViolationException raced) { jdbc.update("update graph_build set dirty = true where crate_id = ?", crateId); }
  }

  public List<UUID> dirtyCrates() { return jdbc.query("select crate_id from graph_build where dirty = true", (rs, i) -> rs.getObject(1, UUID.class)); }

  /**
   * Claims the build of a crate; exactly one of several instances gets true. The dirty flag is cleared on claim, so changes
   * made while the build runs set it again and cause another build. A build that has not finished after {@code staleBefore}
   * is considered lost and may be claimed again.
   */
  public boolean claim(UUID crateId, Instant now, Instant staleBefore) {
    return jdbc.update("update graph_build set status = 'BUILDING', dirty = false, claimed_at = ?, started_at = ?, error = null"
        + " where crate_id = ? and dirty = true and (status <> 'BUILDING' or claimed_at < ?)",
        Timestamp.from(now), Timestamp.from(now), crateId, Timestamp.from(staleBefore)) == 1;
  }

  public void fail(UUID crateId, String error) {
    jdbc.update("update graph_build set status = 'FAILED', finished_at = ?, error = ? where crate_id = ?", Timestamp.from(Instant.now()),
        error == null ? null : error.length() > 2000 ? error.substring(0, 2000) : error, crateId);
  }

  public BuildStatus status(UUID crateId) {
    return jdbc.query("select status, dirty, started_at, finished_at, entities, edges, communities, levels, error from graph_build where crate_id = ?",
        (rs, i) -> new BuildStatus(rs.getString(1), rs.getBoolean(2), instant(rs.getTimestamp(3)), instant(rs.getTimestamp(4)), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getString(9)),
        crateId).stream().findFirst().orElse(BuildStatus.NONE);
  }

  private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

  // ---- the built graph ------------------------------------------------------------------------

  /** Summaries of the previous build by fingerprint, so unchanged communities are not summarised again. */
  public Map<String, Community> communitiesByFingerprint(UUID crateId) {
    Map<String, Community> out = new HashMap<>();
    jdbc.query("select id, level, parent_id, title, summary, rating, size, fingerprint from graph_community where crate_id = ?",
        rs -> { var c = community(rs, List.of()); out.putIfAbsent(c.fingerprint(), c); }, crateId);
    return out;
  }

  /** Swaps the stored graph of a crate for a new one and records the finished build. */
  @Transactional
  public void replace(UUID crateId, Graph graph) {
    for (String table : List.of("graph_community_member", "graph_community", "graph_entity_chunk", "graph_edge", "graph_entity"))
      jdbc.update("delete from " + table + " where crate_id = ?", crateId);
    jdbc.batchUpdate("insert into graph_entity(id, crate_id, name, normalized_name, type, description, chunk_count, document_count, degree) values (?,?,?,?,?,?,?,?,?)",
        graph.entities().stream().map(e -> new Object[] {e.id(), crateId, e.name(), e.normalizedName(), e.type(), e.description(), e.chunkCount(), e.documentCount(), e.degree()}).toList());
    List<Object[]> links = new ArrayList<>();
    graph.entityChunks().forEach((entity, chunks) -> chunks.forEach(chunk -> links.add(new Object[] {entity, chunk, crateId})));
    jdbc.batchUpdate("insert into graph_entity_chunk(entity_id, chunk_id, crate_id) values (?,?,?)", links);
    jdbc.batchUpdate("insert into graph_edge(id, crate_id, source_id, target_id, weight, document_count, relation_count, description) values (?,?,?,?,?,?,?,?)",
        graph.edges().stream().map(e -> new Object[] {e.id(), crateId, e.source(), e.target(), e.weight(), e.documentCount(), e.relationCount(), e.description()}).toList());
    jdbc.batchUpdate("insert into graph_community(id, crate_id, level, parent_id, title, summary, rating, size, fingerprint) values (?,?,?,?,?,?,?,?,?)",
        graph.communities().stream().map(c -> new Object[] {c.id(), crateId, c.level(), c.parentId(), c.title(), c.summary(), c.rating(), c.size(), c.fingerprint()}).toList());
    List<Object[]> members = new ArrayList<>();
    for (var c : graph.communities()) for (UUID entity : c.members()) members.add(new Object[] {c.id(), entity, crateId});
    jdbc.batchUpdate("insert into graph_community_member(community_id, entity_id, crate_id) values (?,?,?)", members);
    jdbc.update("update graph_build set status = 'READY', finished_at = ?, entities = ?, edges = ?, communities = ?, levels = ?, error = null where crate_id = ?",
        Timestamp.from(Instant.now()), graph.entities().size(), graph.edges().size(), graph.communities().size(), graph.levels(), crateId);
  }

  public Graph load(UUID crateId) {
    var entities = jdbc.query("select id, name, normalized_name, type, description, chunk_count, document_count, degree from graph_entity where crate_id = ?",
        (rs, i) -> new Entity(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8)), crateId);
    if (entities.isEmpty()) return Graph.EMPTY;
    var edges = jdbc.query("select id, source_id, target_id, weight, document_count, relation_count, description from graph_edge where crate_id = ?",
        (rs, i) -> new Edge(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getDouble(4), rs.getInt(5), rs.getInt(6), rs.getString(7)), crateId);
    Map<UUID, List<UUID>> chunks = new HashMap<>();
    jdbc.query("select entity_id, chunk_id from graph_entity_chunk where crate_id = ?",
        rs -> { chunks.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getObject(2, UUID.class)); }, crateId);
    Map<UUID, List<UUID>> members = new HashMap<>();
    jdbc.query("select community_id, entity_id from graph_community_member where crate_id = ?",
        rs -> { members.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getObject(2, UUID.class)); }, crateId);
    List<Community> communities = new ArrayList<>();
    jdbc.query("select id, level, parent_id, title, summary, rating, size, fingerprint from graph_community where crate_id = ? order by level, rating desc",
        rs -> { communities.add(community(rs, members.getOrDefault(rs.getObject(1, UUID.class), List.of()))); }, crateId);
    return new Graph(entities, edges, chunks, communities);
  }

  private static Community community(java.sql.ResultSet rs, List<UUID> members) throws java.sql.SQLException {
    return new Community(rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getDouble(6), rs.getInt(7), rs.getString(8), List.copyOf(members));
  }
}
