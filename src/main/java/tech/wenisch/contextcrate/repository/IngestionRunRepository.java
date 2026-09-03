package tech.wenisch.contextcrate.repository;

import java.util.*;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.IngestionRun;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, UUID> {
  List<IngestionRun> findTop20ByOrderByStartedAtDesc();
  List<IngestionRun> findTop20ByCrateIdOrderByStartedAtDesc(UUID crateId);
  List<IngestionRun> findTop20BySourceIdOrderByStartedAtDesc(UUID sourceId);
  Optional<IngestionRun> findByIdAndCrateId(UUID id, UUID crateId);
  List<IngestionRun> findByCrateId(UUID crateId);
  List<IngestionRun> findByCrateIdAndStartedAtGreaterThanEqual(UUID crateId, Instant startedAt);
  long countByCrateIdAndStatusIn(UUID crateId, Collection<tech.wenisch.contextcrate.domain.PipelineTypes.RunStatus> statuses);
}
