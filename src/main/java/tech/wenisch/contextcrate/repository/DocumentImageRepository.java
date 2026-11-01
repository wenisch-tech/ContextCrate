package tech.wenisch.contextcrate.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.DocumentImage;

public interface DocumentImageRepository extends JpaRepository<DocumentImage, UUID> {
  List<DocumentImage> findByDocumentIdAndCrateIdOrderByCreatedAt(UUID documentId, UUID crateId);
  List<DocumentImage> findByCrateId(UUID crateId);
  long countByRunId(UUID runId);
  Optional<DocumentImage> findFirstByCrateIdAndContentHashAndStatus(
      UUID crateId, String contentHash, DocumentImage.Status status);
  void deleteByDocumentId(UUID documentId);
}
