package tech.wenisch.contextcrate.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tech.wenisch.contextcrate.domain.PipelineTypes.*;
import tech.wenisch.contextcrate.domain.PipelineWorkItem;

public interface PipelineWorkItemRepository extends JpaRepository<PipelineWorkItem, UUID> {
  boolean existsByStageAndIdempotencyKey(WorkStage stage, String idempotencyKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select w from PipelineWorkItem w where w.stage=:stage and ((w.status in :ready and"
          + " w.availableAt<=:now) or (w.status=:processing and w.leaseUntil<:now)) order by"
          + " w.priority desc,w.createdAt")
  List<PipelineWorkItem> claimable(
      @Param("stage") WorkStage stage,
      @Param("ready") List<WorkStatus> ready,
      @Param("processing") WorkStatus processing,
      @Param("now") Instant now,
      Pageable pageable);

  long countByStageAndStatus(WorkStage stage, WorkStatus status);

  long countByCorrelationIdAndStatusIn(UUID correlationId, List<WorkStatus> statuses);

  long countByCorrelationIdAndStatus(UUID correlationId, WorkStatus status);

  List<PipelineWorkItem> findTop100ByCorrelationIdOrderByUpdatedAtDesc(UUID correlationId);

  List<PipelineWorkItem> findTop100ByStatusOrderByUpdatedAtDesc(WorkStatus status);

  long deleteByCrateId(UUID crateId);
}
