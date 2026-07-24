package tech.wenisch.contextcrate.index;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import tech.wenisch.contextcrate.domain.DocumentChunk;
import tech.wenisch.contextcrate.domain.NormalizedDocument;

public interface SearchIndex extends AutoCloseable {
  void initialize(UUID crateId) throws IOException, InterruptedException;

  void upsert(NormalizedDocument document, List<DocumentChunk> chunks)
      throws IOException, InterruptedException;

  void upsertGeneration(UUID crateId, int generation, NormalizedDocument document,
      List<DocumentChunk> chunks) throws IOException, InterruptedException;

  SearchResults search(SearchRequest request) throws IOException, InterruptedException;

  void delete(UUID crateId, UUID documentId) throws IOException, InterruptedException;

  void commit(UUID crateId) throws IOException, InterruptedException;

  void commitGeneration(UUID crateId, int generation) throws IOException, InterruptedException;

  void deleteGeneration(UUID crateId, int generation) throws IOException, InterruptedException;

  IndexHealth health(UUID crateId);

  void deleteNamespace(UUID crateId) throws IOException, InterruptedException;

  record IndexHealth(UUID crateId, String backend, boolean healthy, long documents, String detail) {}

  record SearchRequest(UUID crateId, String query, int limit, UUID runId, String kind, String mode) {
    public SearchRequest(UUID crateId, String query, int limit, UUID runId, String kind) {
      this(crateId, query, limit, runId, kind, null);
    }
    public SearchRequest {
      crateId = java.util.Objects.requireNonNull(crateId, "crateId");
      query = query == null ? "" : query.trim();
      limit = Math.max(1, Math.min(limit, 100));
      kind = kind == null || kind.isBlank() ? null : kind.trim().toLowerCase(java.util.Locale.ROOT);
      mode = mode == null || mode.isBlank() ? null : mode.trim().toLowerCase(java.util.Locale.ROOT);
      if (mode != null && !java.util.Set.of("lexical", "semantic", "hybrid").contains(mode))
        throw new IllegalArgumentException("mode must be lexical, semantic, or hybrid");
    }
  }

  record SearchHit(
      UUID id,
      UUID documentId,
      UUID runId,
      String kind,
      String title,
      String canonicalUrl,
      Integer chunkOrdinal,
      String snippet,
      float score,
      Float lexicalScore,
      Float semanticScore) {
    public SearchHit(UUID id, UUID documentId, UUID runId, String kind, String title, String canonicalUrl,
        Integer chunkOrdinal, String snippet, float score) {
      this(id, documentId, runId, kind, title, canonicalUrl, chunkOrdinal, snippet, score, null, null);
    }
  }

  record SearchResults(String query, String mode, List<SearchHit> hits) {
    public SearchResults(String query, List<SearchHit> hits) { this(query, "lexical", hits); }
  }

  @Override
  default void close() throws Exception {}
}
