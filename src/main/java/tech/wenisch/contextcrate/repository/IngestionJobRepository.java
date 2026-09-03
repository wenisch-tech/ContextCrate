package tech.wenisch.contextcrate.repository;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.IngestionJob;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {
  interface SourceJobCount {
    UUID getSourceId();
    long getJobs();
  }

  List<IngestionJob> findBySourceIdOrderByCreatedAtDesc(UUID sourceId);
  List<IngestionJob> findByCrateIdOrderByCreatedAtDesc(UUID crateId);
  Optional<IngestionJob> findByIdAndSourceIdAndCrateId(UUID id, UUID sourceId, UUID crateId);
  Optional<IngestionJob> findByIdAndCrateId(UUID id, UUID crateId);
  List<IngestionJob> findByCrateId(UUID crateId);
  List<IngestionJob> findByMode(tech.wenisch.contextcrate.domain.IngestionJobMode mode);
  long countBySourceId(UUID sourceId);
  long countByCrateId(UUID crateId);

  @org.springframework.data.jpa.repository.Query(
      "select j.sourceId as sourceId, count(j) as jobs from IngestionJob j "
          + "where j.crateId = :crateId and j.sourceId in :sourceIds group by j.sourceId")
  List<SourceJobCount> countBySource(@org.springframework.data.repository.query.Param("crateId") UUID crateId,
      @org.springframework.data.repository.query.Param("sourceIds") Collection<UUID> sourceIds);
}
