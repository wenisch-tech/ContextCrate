package tech.wenisch.harvex.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.harvex.domain.DocumentChunk;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {
  List<DocumentChunk> findByDocumentIdOrderByOrdinal(UUID documentId);

  void deleteByDocumentId(UUID documentId);
}
