package tech.wenisch.contextcrate.web;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.graph.GraphModel.*;
import tech.wenisch.contextcrate.graph.GraphSearchService;
import tech.wenisch.contextcrate.graph.GraphStore;
import tech.wenisch.contextcrate.repository.DocumentChunkRepository;
import tech.wenisch.contextcrate.repository.NormalizedDocumentRepository;
import tech.wenisch.contextcrate.service.CrateAccessService;

/** The knowledge graph of a crate for the graph explorer. Readable by every crate member; rebuilding needs the Owner. */
@RestController
@RequestMapping("/api/v1/crates/{crateId}/graph")
public class KnowledgeGraphApiController {
  static final int DEFAULT_NODES = 2_000, MAX_NODES = 10_000, MAX_NEIGHBOURS = 25, MAX_CHUNKS = 10;
  private final CrateAccessService access;
  private final GraphStore store;
  private final GraphSearchService graphs;
  private final DocumentChunkRepository chunks;
  private final NormalizedDocumentRepository documents;

  public KnowledgeGraphApiController(CrateAccessService access, GraphStore store, GraphSearchService graphs, DocumentChunkRepository chunks, NormalizedDocumentRepository documents) {
    this.access = access; this.store = store; this.graphs = graphs; this.chunks = chunks; this.documents = documents;
  }

  public record NodeView(UUID id, String name, String type, int size, int documents, int degree, List<UUID> communities) {}
  public record EdgeView(UUID id, UUID source, UUID target, double weight) {}
  public record CommunityView(UUID id, int level, UUID parent, String title, double rating, int size) {}
  public record GraphView(BuildStatus status, boolean truncated, int totalNodes, int levels, List<NodeView> nodes, List<EdgeView> edges, List<CommunityView> communities) {}

  /** Nodes (the most mentioned first, capped), the edges between them, and all communities. */
  @GetMapping
  public GraphView graph(@PathVariable UUID crateId, @RequestParam(defaultValue = "" + DEFAULT_NODES) int limit) {
    access.require(crateId, CrateMember.Role.VIEWER);
    var status = store.status(crateId);
    var graph = graphs.graph(crateId);
    int cap = Math.max(1, Math.min(limit, MAX_NODES));
    var kept = graph.entities().stream().sorted(Comparator.comparingInt(Entity::chunkCount).thenComparingInt(Entity::degree).reversed()).limit(cap).toList();
    Set<UUID> ids = kept.stream().map(Entity::id).collect(Collectors.toSet());
    // Community of every entity per level, ordered from level 0 to the deepest.
    Map<UUID, TreeMap<Integer, UUID>> byLevel = new HashMap<>();
    for (var c : graph.communities()) for (UUID m : c.members()) byLevel.computeIfAbsent(m, k -> new TreeMap<>()).put(c.level(), c.id());
    var nodes = kept.stream().map(e -> new NodeView(e.id(), e.name(), e.type(), e.chunkCount(), e.documentCount(), e.degree(),
        List.copyOf(byLevel.getOrDefault(e.id(), new TreeMap<>()).values()))).toList();
    var edges = graph.edges().stream().filter(e -> ids.contains(e.source()) && ids.contains(e.target())).map(e -> new EdgeView(e.id(), e.source(), e.target(), e.weight())).toList();
    var communities = graph.communities().stream().map(c -> new CommunityView(c.id(), c.level(), c.parentId(), c.title(), c.rating(), c.size())).toList();
    return new GraphView(status, kept.size() < graph.entities().size(), graph.entities().size(), graph.levels(), nodes, edges, communities);
  }

  public record Neighbour(UUID id, String name, String type, double weight, int documents, String description) {}
  public record ChunkView(UUID chunkId, UUID documentId, String title, String sourceUri, String heading, String snippet, String url) {}
  public record EntityDetail(UUID id, String name, String type, String description, int chunkCount, int documentCount, int degree,
      List<CommunityView> communities, List<Neighbour> neighbours, List<ChunkView> chunks) {}

  @GetMapping("/entities/{entityId}")
  public EntityDetail entity(@PathVariable UUID crateId, @PathVariable UUID entityId) {
    access.require(crateId, CrateMember.Role.VIEWER);
    var graph = graphs.graph(crateId);
    Map<UUID, Entity> byId = graph.entities().stream().collect(Collectors.toMap(Entity::id, e -> e));
    var entity = byId.get(entityId);
    if (entity == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "entity not found");
    var neighbours = graph.edges().stream().filter(e -> e.source().equals(entityId) || e.target().equals(entityId))
        .sorted(Comparator.comparingDouble(Edge::weight).reversed()).limit(MAX_NEIGHBOURS)
        .map(e -> { var other = byId.get(e.source().equals(entityId) ? e.target() : e.source());
          return new Neighbour(other.id(), other.name(), other.type(), e.weight(), e.documentCount(), e.description()); }).toList();
    var communities = graph.communities().stream().filter(c -> c.members().contains(entityId)).sorted(Comparator.comparingInt(Community::level))
        .map(c -> new CommunityView(c.id(), c.level(), c.parentId(), c.title(), c.rating(), c.size())).toList();
    List<ChunkView> sources = new ArrayList<>();
    for (UUID chunkId : graph.entityChunks().getOrDefault(entityId, List.of())) {
      if (sources.size() == MAX_CHUNKS) break;
      chunks.findByIdAndCrateId(chunkId, crateId).ifPresent(chunk -> {
        var document = documents.findById(chunk.getDocumentId()).orElse(null);
        String text = chunk.getContent() == null ? "" : chunk.getContent().strip();
        sources.add(new ChunkView(chunk.getId(), chunk.getDocumentId(), document == null ? null : document.getTitle(), document == null ? null : document.getSourceUri(),
            chunk.getHeading(), text.length() > 280 ? text.substring(0, 280) + "…" : text, "/crates/" + crateId + "/documents/" + chunk.getDocumentId()));
      });
    }
    return new EntityDetail(entity.id(), entity.name(), entity.type(), entity.description(), entity.chunkCount(), entity.documentCount(), entity.degree(), communities, neighbours, sources);
  }

  public record Member(UUID id, String name, String type, int size) {}
  public record CommunityDetail(UUID id, int level, CommunityView parent, String title, String summary, double rating, int size, List<Member> members, List<CommunityView> children) {}

  @GetMapping("/communities/{communityId}")
  public CommunityDetail community(@PathVariable UUID crateId, @PathVariable UUID communityId) {
    access.require(crateId, CrateMember.Role.VIEWER);
    var graph = graphs.graph(crateId);
    var community = graph.communities().stream().filter(c -> c.id().equals(communityId)).findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "community not found"));
    Map<UUID, Entity> byId = graph.entities().stream().collect(Collectors.toMap(Entity::id, e -> e));
    var members = community.members().stream().map(byId::get).filter(Objects::nonNull).sorted(Comparator.comparingInt(Entity::chunkCount).reversed())
        .map(e -> new Member(e.id(), e.name(), e.type(), e.chunkCount())).toList();
    var parent = community.parentId() == null ? null : graph.communities().stream().filter(c -> c.id().equals(community.parentId())).findFirst()
        .map(c -> new CommunityView(c.id(), c.level(), c.parentId(), c.title(), c.rating(), c.size())).orElse(null);
    var children = graph.communities().stream().filter(c -> communityId.equals(c.parentId()))
        .map(c -> new CommunityView(c.id(), c.level(), c.parentId(), c.title(), c.rating(), c.size())).toList();
    return new CommunityDetail(community.id(), community.level(), parent, community.title(), community.summary(), community.rating(), community.size(), members, children);
  }

  /** Builds the graph again once indexing is idle. */
  @PostMapping("/rebuild")
  public BuildStatus rebuild(@PathVariable UUID crateId) {
    access.requireMutable(crateId, CrateMember.Role.OWNER);
    store.markDirty(crateId);
    return store.status(crateId);
  }
}
