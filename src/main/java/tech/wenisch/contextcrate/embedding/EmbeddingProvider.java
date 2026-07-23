package tech.wenisch.contextcrate.embedding;

import java.util.List;

/** Produces normalized vectors for index passages and user queries. */
public interface EmbeddingProvider extends AutoCloseable {
  ModelDescriptor descriptor();

  boolean available();

  List<float[]> embedDocuments(List<String> texts) throws Exception;

  default float[] embedQuery(String text) throws Exception { return embedQueries(List.of(text)).getFirst(); }

  default List<float[]> embedQueries(List<String> texts) throws Exception { return embedDocuments(texts); }

  record ModelDescriptor(String provider, String modelId, String version, int dimensions,
                         boolean normalized, String queryPrefix, String documentPrefix) {}

  @Override default void close() throws Exception {}
}
