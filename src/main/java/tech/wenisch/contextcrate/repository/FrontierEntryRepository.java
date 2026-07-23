package tech.wenisch.contextcrate.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.FrontierEntry;
import tech.wenisch.contextcrate.domain.PipelineTypes.FrontierStatus;

public interface FrontierEntryRepository extends JpaRepository<FrontierEntry, UUID> {
  Optional<FrontierEntry> findByRunIdAndCanonicalUrl(UUID runId, String canonicalUrl);

  long countByRunId(UUID runId);

  long countByRunIdAndStatus(UUID runId, FrontierStatus status);

  List<FrontierEntry> findTop100ByRunIdOrderByDiscoveredAtDesc(UUID runId);
  List<FrontierEntry> findByCrateId(UUID crateId);
}
