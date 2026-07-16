package tech.wenisch.harvex.repository;

import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import tech.wenisch.harvex.domain.ExtractionResult;

public interface ExtractionResultRepository extends JpaRepository<ExtractionResult, UUID> {
  @Query(
      """
      select r from ExtractionResult r
      where (:runId is null or r.runId = :runId)
        and (:documentId is null or r.documentId = :documentId)
        and (:chunkId is null or r.chunkId = :chunkId)
        and (:ruleId is null or r.ruleId = :ruleId)
        and (:value is null or lower(r.matchedValue) like lower(concat('%', :value, '%')))
      order by r.extractedAt desc
      """)
  Page<ExtractionResult> search(
      @Param("runId") UUID runId,
      @Param("documentId") UUID documentId,
      @Param("chunkId") UUID chunkId,
      @Param("ruleId") UUID ruleId,
      @Param("value") String value,
      Pageable pageable);

  long countByRuleId(UUID ruleId);

  void deleteByRunId(UUID runId);

  void deleteByDocumentId(UUID documentId);
}