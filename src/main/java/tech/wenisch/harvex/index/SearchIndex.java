package tech.wenisch.harvex.index;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import tech.wenisch.harvex.domain.DocumentChunk;
import tech.wenisch.harvex.domain.NormalizedDocument;

public interface SearchIndex extends AutoCloseable {
  void initialize() throws IOException, InterruptedException;

  void upsert(NormalizedDocument document, List<DocumentChunk> chunks)
      throws IOException, InterruptedException;

  SearchResults search(SearchRequest request) throws IOException, InterruptedException;

  void delete(UUID documentId) throws IOException, InterruptedException;

  void commit() throws IOException, InterruptedException;

  IndexHealth health();

  record IndexHealth(String backend, boolean healthy, long documents, String detail) {}

  record SearchRequest(String query, int limit, UUID runId, String kind, String mode) {
    public SearchRequest(String query, int limit, UUID runId, String kind) { this(query, limit, runId, kind, null); }
    public SearchRequest {
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
