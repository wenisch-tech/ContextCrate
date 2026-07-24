package tech.wenisch.contextcrate.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.NormalizedDocument;

public interface NormalizedDocumentRepository extends JpaRepository<NormalizedDocument, UUID> {
  Optional<NormalizedDocument> findByRunIdAndSourceUri(UUID runId, String sourceUri);

  List<NormalizedDocument> findByRunId(UUID runId);

  List<NormalizedDocument> findTop100ByOrderByCreatedAtDesc();
  List<NormalizedDocument> findTop100ByCrateIdOrderByCreatedAtDesc(UUID crateId);
  Optional<NormalizedDocument> findByIdAndCrateId(UUID id, UUID crateId);
  List<NormalizedDocument> findByCrateId(UUID crateId);
  List<NormalizedDocument> findByCrateIdAndIndexedFalse(UUID crateId);
}
