package tech.wenisch.harvex.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.harvex.domain.FrontierEntry;
import tech.wenisch.harvex.domain.PipelineTypes.FrontierStatus;

public interface FrontierEntryRepository extends JpaRepository<FrontierEntry, UUID> {
  Optional<FrontierEntry> findByRunIdAndCanonicalUrl(UUID runId, String canonicalUrl);

  long countByRunId(UUID runId);

  long countByRunIdAndStatus(UUID runId, FrontierStatus status);

  List<FrontierEntry> findTop100ByRunIdOrderByDiscoveredAtDesc(UUID runId);
}
