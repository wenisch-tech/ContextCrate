package tech.wenisch.harvex.service;

import org.springframework.stereotype.Service;
import tech.wenisch.harvex.index.SearchIndex;
import tech.wenisch.harvex.repository.DocumentChunkRepository;
import tech.wenisch.harvex.repository.NormalizedDocumentRepository;

@Service
public class IndexRebuildService {
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final SearchIndex index;
  public IndexRebuildService(NormalizedDocumentRepository documents, DocumentChunkRepository chunks, SearchIndex index) {
    this.documents = documents; this.chunks = chunks; this.index = index;
  }
  /** Rebuilds the derived v2 index from canonical records; crawler data is never changed. */
  public synchronized long rebuild() throws Exception {
    index.initialize(); long count=0;
    for (var document : documents.findAll()) { index.upsert(document, chunks.findByDocumentIdOrderByOrdinal(document.getId())); count++; }
    index.commit(); return count;
  }
}
