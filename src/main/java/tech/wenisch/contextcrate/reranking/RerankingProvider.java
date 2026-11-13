package tech.wenisch.contextcrate.reranking;

import java.util.List;

/** Scores query/document pairs. Higher scores are more relevant. */
public interface RerankingProvider extends AutoCloseable {
  boolean available();
  int candidateLimit();
  /** How many reranked results are returned for a request that asked for {@code requested}. */
  default int resultLimit(int requested) { return requested; }
  List<Float> rerank(String query, List<String> documents) throws Exception;
  default void downloadModel() throws Exception { throw new UnsupportedOperationException("This reranker has no local model"); }
  @Override default void close() throws Exception {}
}
