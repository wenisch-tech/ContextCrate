package tech.wenisch.contextcrate.repository;

import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import tech.wenisch.contextcrate.domain.ExtractionResult;

public interface ExtractionResultRepository extends JpaRepository<ExtractionResult, UUID> {
  @Query(
      """
      select r from ExtractionResult r
      where r.crateId = :crateId
        and (:runId is null or r.runId = :runId)
        and (:documentId is null or r.documentId = :documentId)
        and (:chunkId is null or r.chunkId = :chunkId)
        and (:ruleId is null or r.ruleId = :ruleId)
        and (:value is null or lower(r.matchedValue) like lower(concat('%', :value, '%')))
      order by r.extractedAt desc
      """)
  Page<ExtractionResult> search(
      @Param("crateId") UUID crateId,
      @Param("runId") UUID runId,
      @Param("documentId") UUID documentId,
      @Param("chunkId") UUID chunkId,
      @Param("ruleId") UUID ruleId,
      @Param("value") String value,
      Pageable pageable);

  default Page<ExtractionResult> search(
      UUID runId, UUID documentId, UUID chunkId, UUID ruleId, String value, Pageable pageable) {
    return search(tech.wenisch.contextcrate.domain.CrateIds.LEGACY,
        runId, documentId, chunkId, ruleId, value, pageable);
  }

  long countByRuleId(UUID ruleId);

  void deleteByRunId(UUID runId);

  void deleteByDocumentId(UUID documentId);
  java.util.List<ExtractionResult> findByCrateId(UUID crateId);
}
