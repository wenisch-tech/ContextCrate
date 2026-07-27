package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.queue.PipelineMessage;
import tech.wenisch.contextcrate.queue.PipelineQueue;
import tech.wenisch.contextcrate.repository.NormalizedDocumentRepository;

class DocumentIndexRecoveryServiceTest {
  @Test
  void requeuesEachUnindexedDocumentWithARecoveryKey() {
    NormalizedDocumentRepository documents = mock(NormalizedDocumentRepository.class);
    PipelineQueue queue = mock(PipelineQueue.class);
    UUID crateId = UUID.randomUUID(), runId = UUID.randomUUID(), acquisitionId = UUID.randomUUID();
    NormalizedDocument document = new NormalizedDocument(UUID.randomUUID(), runId, acquisitionId,
        "https://example.test/doc", "Document", null, null, null, "Body", "hash", "{}");
    document.assignCrate(crateId);
    document.version(UUID.randomUUID(), "https://example.test/doc", 1);
    when(documents.findByCrateIdAndCurrentVersionTrueAndIndexedFalse(crateId)).thenReturn(List.of(document));

    int queued = new DocumentIndexRecoveryService(documents, queue).enqueueMissing(crateId);

    assertThat(queued).isEqualTo(1);
    verify(queue).publish(argThat(message -> isIndexRecovery(message, crateId, document.getId())));
  }

  private static boolean isIndexRecovery(PipelineMessage message, UUID crateId, UUID documentId) {
    return message.crateId().equals(crateId)
        && message.idempotencyKey().startsWith(crateId + ":index-recovery:" + documentId + ":");
  }
}
