package tech.wenisch.harvex.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.harvex.index.SearchIndex;
import tech.wenisch.harvex.repository.*;

@Service
public class DocumentIndexer {
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final SearchIndex index;

  public DocumentIndexer(
      NormalizedDocumentRepository documents, DocumentChunkRepository chunks, SearchIndex index) {
    this.documents = documents;
    this.chunks = chunks;
    this.index = index;
  }

  @Transactional
  public void index(PipelinePayload payload) throws Exception {
    var document = documents.findById(payload.entityId()).orElseThrow();
    index.upsert(document, chunks.findByDocumentIdOrderByOrdinal(document.getId()));
    document.indexed();
    documents.save(document);
  }
}
