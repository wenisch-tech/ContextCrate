package tech.wenisch.contextcrate.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.*;

@Service
public class DocumentIndexer {
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final SearchIndex index;
  private final CrateIndexGenerationRepository generations;

  public DocumentIndexer(
      NormalizedDocumentRepository documents, DocumentChunkRepository chunks, SearchIndex index,
      CrateIndexGenerationRepository generations) {
    this.documents = documents;
    this.chunks = chunks;
    this.index = index;
    this.generations = generations;
  }

  @Transactional
  public void index(PipelinePayload payload) throws Exception {
    var document = documents.findById(payload.entityId()).orElseThrow();
    if (payload.crateId() != null && !payload.crateId().equals(document.getCrateId()))
      throw new IllegalArgumentException("Pipeline message crosses crate boundary");
    index.upsert(document, chunks.findByDocumentIdOrderByOrdinal(document.getId()));
    for (var generation : generations.findByCrateIdAndStatus(
        document.getCrateId(), tech.wenisch.contextcrate.domain.CrateIndexGeneration.Status.BUILDING))
      index.upsertGeneration(document.getCrateId(), generation.getGeneration(), document,
          chunks.findByDocumentIdOrderByOrdinal(document.getId()));
    document.indexed();
    documents.save(document);
  }
}
