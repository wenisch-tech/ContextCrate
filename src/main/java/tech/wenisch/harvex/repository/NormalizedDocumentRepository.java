package tech.wenisch.harvex.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.harvex.domain.NormalizedDocument;

public interface NormalizedDocumentRepository extends JpaRepository<NormalizedDocument, UUID> {
  Optional<NormalizedDocument> findByRunIdAndCanonicalUrl(UUID runId, String canonicalUrl);

  List<NormalizedDocument> findByRunId(UUID runId);

  List<NormalizedDocument> findTop100ByOrderByCreatedAtDesc();
}
