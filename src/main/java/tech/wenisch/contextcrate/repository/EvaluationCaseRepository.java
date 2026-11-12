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
  List<EvaluationCase> findByCrateIdAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(UUID crateId, EvaluationCase.Status status, Instant from, Instant to, Pageable page);
  // Variants that leave out cases caught by an ignore rule.
  List<EvaluationCase> findByCrateIdAndIgnoredRuleIdIsNullOrderByCreatedAtDesc(UUID crateId, Pageable page);
  List<EvaluationCase> findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtAfter(UUID crateId, EvaluationCase.Status status, Instant after);
  List<EvaluationCase> findByCrateIdAndStatusAndIgnoredRuleIdIsNullAndCreatedAtBetweenOrderByCreatedAtDesc(UUID crateId, EvaluationCase.Status status, Instant from, Instant to, Pageable page);
  List<EvaluationCase> findByCrateIdAndIgnoredRuleIdIsNull(UUID crateId);

  /** Rows of {ruleId, count} for the cases caught within a timeframe. */
  @Query("select c.ignoredRuleId, count(c) from EvaluationCase c where c.crateId = :crateId and c.ignoredRuleId is not null and c.createdAt >= :from and c.createdAt < :to group by c.ignoredRuleId")
  List<Object[]> countIgnoredBetween(@Param("crateId") UUID crateId, @Param("from") Instant from, @Param("to") Instant to);

  /** Rows of {ruleId, count} over all time. */
  @Query("select c.ignoredRuleId, count(c) from EvaluationCase c where c.crateId = :crateId and c.ignoredRuleId is not null group by c.ignoredRuleId")
  List<Object[]> countIgnored(@Param("crateId") UUID crateId);

  @Modifying
  @Query("update EvaluationCase c set c.ignoredRuleId = null where c.crateId = :crateId and c.ignoredRuleId = :ruleId")
  int clearIgnored(@Param("crateId") UUID crateId, @Param("ruleId") UUID ruleId);

  List<EvaluationCase> findByStatusAndCreatedAtBefore(EvaluationCase.Status status, Instant before);
  List<EvaluationCase> findByQuestionIsNotNullAndStatusNot(EvaluationCase.Status status);
  long countByCrateIdAndStatus(UUID crateId, EvaluationCase.Status status);
  void deleteByCrateId(UUID crateId);

  @Modifying
  @Query("delete from EvaluationCase c where c.crateId = :crateId and c.createdAt < :before")
  int deleteOlderThan(@Param("crateId") UUID crateId, @Param("before") Instant before);
}
