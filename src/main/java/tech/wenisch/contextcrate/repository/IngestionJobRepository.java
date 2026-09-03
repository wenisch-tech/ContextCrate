package tech.wenisch.contextcrate.repository;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.IngestionJob;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {
  List<IngestionJob> findBySourceIdOrderByCreatedAtDesc(UUID sourceId);
  List<IngestionJob> findByCrateIdOrderByCreatedAtDesc(UUID crateId);
  Optional<IngestionJob> findByIdAndSourceIdAndCrateId(UUID id, UUID sourceId, UUID crateId);
  Optional<IngestionJob> findByIdAndCrateId(UUID id, UUID crateId);
  List<IngestionJob> findByCrateId(UUID crateId);
  long countBySourceId(UUID sourceId);
  long countByCrateId(UUID crateId);
}
