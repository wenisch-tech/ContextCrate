package tech.wenisch.contextcrate.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;
import tech.wenisch.contextcrate.queue.*;
import tech.wenisch.contextcrate.repository.NormalizedDocumentRepository;

/** Re-enqueues documents whose persisted index indicator was not completed. */
@Service
public class DocumentIndexRecoveryService {
  private final NormalizedDocumentRepository documents;
  private final PipelineQueue queue;

  public DocumentIndexRecoveryService(NormalizedDocumentRepository documents, PipelineQueue queue) {
    this.documents = documents;
    this.queue = queue;
  }

  @Transactional
  public int enqueueMissing(UUID crateId) {
    var missing = documents.findByCrateIdAndIndexedFalse(crateId);
    for (var document : missing) {
      // Completed work is intentionally retained for auditability, so a recovery request needs a
      // fresh idempotency key rather than reusing the original document-processing key.
      queue.publish(PipelineMessage.create(crateId, WorkStage.INDEX,
          IngestionService.payload(crateId, document.getRunId(), document.getId()),
          document.getRunId(), crateId + ":index-recovery:" + document.getId() + ":" + UUID.randomUUID(),
          75));
    }
    return missing.size();
  }
}
