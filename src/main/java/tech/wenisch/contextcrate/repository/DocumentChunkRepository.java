package tech.wenisch.contextcrate.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.DocumentChunk;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {
  List<DocumentChunk> findByDocumentIdOrderByOrdinal(UUID documentId);

  void deleteByDocumentId(UUID documentId);

  List<DocumentChunk> findByDocumentIdAndCrateIdOrderByOrdinal(UUID documentId, UUID crateId);
  Optional<DocumentChunk> findByIdAndCrateId(UUID id, UUID crateId);
  List<DocumentChunk> findByCrateId(UUID crateId);
}
