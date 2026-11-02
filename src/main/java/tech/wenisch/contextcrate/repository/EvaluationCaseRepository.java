package tech.wenisch.contextcrate.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tech.wenisch.contextcrate.domain.EvaluationCase;

public interface EvaluationCaseRepository extends JpaRepository<EvaluationCase, UUID> {
  List<EvaluationCase> findByCrateIdAndStatusOrderByCreatedAtDesc(UUID crateId, EvaluationCase.Status status, Pageable page);
  List<EvaluationCase> findByCrateIdOrderByCreatedAtDesc(UUID crateId, Pageable page);
  List<EvaluationCase> findByCrateIdAndStatusAndCreatedAtAfter(UUID crateId, EvaluationCase.Status status, Instant after);
  List<EvaluationCase> findByStatusAndCreatedAtBefore(EvaluationCase.Status status, Instant before);
  List<EvaluationCase> findByQuestionIsNotNullAndStatusNot(EvaluationCase.Status status);
  long countByCrateIdAndStatus(UUID crateId, EvaluationCase.Status status);
  void deleteByCrateId(UUID crateId);

  @Modifying
  @Query("delete from EvaluationCase c where c.crateId = :crateId and c.createdAt < :before")
  int deleteOlderThan(@Param("crateId") UUID crateId, @Param("before") Instant before);
}
