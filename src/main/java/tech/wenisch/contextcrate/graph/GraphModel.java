package tech.wenisch.contextcrate.graph;

import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** Value types of the knowledge graph. */
public final class GraphModel {
  private GraphModel() {}

  /** An entity as the model extracted it from one chunk. */
  public record Mention(UUID chunkId, UUID documentId, String name, String type, String description) {
    public String key() { return normalize(name); }
  }

  /** A relation as the model extracted it from one chunk; names are as written there. */
  public record RelationMention(UUID chunkId, UUID documentId, String source, String target, String description, int strength) {}

  /** A merged entity of the built graph. {@code chunkCount} makes hubs: an entity in many chunks is a large, central node. */
  public record Entity(UUID id, String name, String normalizedName, String type, String description, int chunkCount, int documentCount, int degree) {}

  /** An undirected weighted edge; the weight grows with co-mentions in separate documents and with extracted relations. */
  public record Edge(UUID id, UUID source, UUID target, double weight, int documentCount, int relationCount, String description) {}

  /** A community on one level of the hierarchy (0 = top); members are entity ids. */
  public record Community(UUID id, int level, UUID parentId, String title, String summary, double rating, int size, String fingerprint, List<UUID> members) {}

  /** A complete built graph of a crate. */
  public record Graph(List<Entity> entities, List<Edge> edges, Map<UUID, List<UUID>> entityChunks, List<Community> communities) {
    public static final Graph EMPTY = new Graph(List.of(), List.of(), Map.of(), List.of());
    public boolean isEmpty() { return entities.isEmpty(); }
    public int levels() { return communities.stream().mapToInt(Community::level).max().orElse(-1) + 1; }
  }

  public record BuildStatus(String status, boolean dirty, Instant startedAt, Instant finishedAt, int entities, int edges, int communities, int levels, String error) {
    public static final BuildStatus NONE = new BuildStatus("NONE", false, null, null, 0, 0, 0, 0, null);
    public boolean built() { return finishedAt != null && entities > 0; }
  }

  /** Name used to merge entities across chunks: case, accents, punctuation and spacing do not matter. */
  public static String normalize(String name) {
    if (name == null) return "";
    String value = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    value = value.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    return value.length() > 300 ? value.substring(0, 300) : value;
  }
}
