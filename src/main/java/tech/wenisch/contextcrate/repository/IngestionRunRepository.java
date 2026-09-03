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

  @org.springframework.data.jpa.repository.Query(
      "select r from IngestionRun r where r.crateId = :crateId and r.sourceId in :sourceIds "
          + "and r.startedAt = (select max(latest.startedAt) from IngestionRun latest "
          + "where latest.crateId = r.crateId and latest.sourceId = r.sourceId)")
  List<IngestionRun> findLatestBySourceIdIn(@org.springframework.data.repository.query.Param("crateId") UUID crateId,
      @org.springframework.data.repository.query.Param("sourceIds") Collection<UUID> sourceIds);

  @org.springframework.data.jpa.repository.Query(
      "select r from IngestionRun r where r.crateId = :crateId and r.ingestionJobId in :jobIds "
          + "and r.startedAt = (select max(latest.startedAt) from IngestionRun latest "
          + "where latest.crateId = r.crateId and latest.ingestionJobId = r.ingestionJobId)")
  List<IngestionRun> findLatestByIngestionJobIdIn(
      @org.springframework.data.repository.query.Param("crateId") UUID crateId,
      @org.springframework.data.repository.query.Param("jobIds") Collection<UUID> jobIds);
}
