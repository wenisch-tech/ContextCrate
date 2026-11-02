package tech.wenisch.contextcrate.evaluation;

import java.util.List;
import java.util.UUID;

/**
 * What the answer model was given for one question: the chunks in the order they were delivered,
 * how they were found, and nothing else. It is stored on the case only until the case is evaluated
 * (or for the retention period when per-question logging is on).
 */
public record EvaluationSnapshot(String mode, List<String> queries, int hydeQueries, List<Chunk> chunks) {
  public record Chunk(UUID chunkId, UUID documentId, String title, String uri, String content) {}

  public EvaluationSnapshot {
    queries = queries == null ? List.of() : List.copyOf(queries);
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
  }
}
