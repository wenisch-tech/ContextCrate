package tech.wenisch.contextcrate.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tech.wenisch.contextcrate.domain.AnswerFeedback;

public interface AnswerFeedbackRepository extends JpaRepository<AnswerFeedback, UUID> {
  Optional<AnswerFeedback> findByTokenHash(String tokenHash);
  Optional<AnswerFeedback> findByIdAndCrateId(UUID id, UUID crateId);
  List<AnswerFeedback> findByCrateIdOrderByCreatedAtDesc(UUID crateId, Pageable page);
  List<AnswerFeedback> findByCrateIdAndEnrichmentStatus(UUID crateId, AnswerFeedback.Enrichment status);

  /** Rated feedback that touched one of the documents (by identity), newest first. */
  @Query("select distinct f from AnswerFeedback f, AnswerFeedbackSource s where s.feedbackId = f.id and f.crateId = :crateId and s.identityUri in :identities and f.relevance is not null order by f.ratedAt desc")
  List<AnswerFeedback> findRatedByIdentities(@Param("crateId") UUID crateId, @Param("identities") Collection<String> identities, Pageable page);

  @Modifying @org.springframework.transaction.annotation.Transactional
  @Query("delete from AnswerFeedback f where f.expiresAt < :now and f.ratedAt is null")
  int deleteExpiredUnrated(@Param("now") Instant now);
}
