package tech.wenisch.contextcrate.repository;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tech.wenisch.contextcrate.domain.RagSettings;
public interface RagSettingsRepository extends JpaRepository<RagSettings,java.util.UUID> {
  java.util.List<RagSettings> findByFeedbackEnrichmentEnabledTrue();
  /** Claims the next enrichment run of a crate; exactly one of several instances gets 1 back. */
  @Modifying @org.springframework.transaction.annotation.Transactional
  @Query("update RagSettings r set r.feedbackEnrichmentLastRun = :now where r.crateId = :crateId and (r.feedbackEnrichmentLastRun is null or r.feedbackEnrichmentLastRun <= :due)")
  int claimFeedbackEnrichment(@Param("crateId") java.util.UUID crateId,@Param("now") Instant now,@Param("due") Instant due);
}
