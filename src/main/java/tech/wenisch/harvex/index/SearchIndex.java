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

  void delete(UUID documentId) throws IOException, InterruptedException;

  void commit() throws IOException, InterruptedException;

  IndexHealth health();

  record IndexHealth(String backend, boolean healthy, long documents, String detail) {}

  @Override
  default void close() throws Exception {}
}
