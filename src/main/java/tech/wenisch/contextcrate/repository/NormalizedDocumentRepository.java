package tech.wenisch.contextcrate.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tech.wenisch.contextcrate.domain.NormalizedDocument;

public interface NormalizedDocumentRepository extends JpaRepository<NormalizedDocument, UUID>,
    NormalizedDocumentRepositoryCustom {
  Optional<NormalizedDocument> findByRunIdAndSourceUri(UUID runId, String sourceUri);

  Optional<NormalizedDocument> findTopByCrateIdAndSourceIdAndIdentityUriOrderByVersionNumberDesc(
      UUID crateId, UUID sourceId, String identityUri);
  List<NormalizedDocument> findByCrateIdAndSourceIdAndIdentityUriOrderByVersionNumberDesc(
      UUID crateId, UUID sourceId, String identityUri);
  List<NormalizedDocument> findByCrateIdAndSourceIdAndIdentityUriAndCurrentVersionFalse(
      UUID crateId, UUID sourceId, String identityUri);

  List<NormalizedDocument> findByRunId(UUID runId);

  List<NormalizedDocument> findTop100ByOrderByCreatedAtDesc();
  List<NormalizedDocument> findTop100ByCrateIdOrderByCreatedAtDesc(UUID crateId);
  List<NormalizedDocument> findTop100ByCrateIdAndCurrentVersionTrueOrderByCreatedAtDesc(UUID crateId);
  Optional<NormalizedDocument> findByIdAndCrateId(UUID id, UUID crateId);
  List<NormalizedDocument> findByCrateId(UUID crateId);
  List<NormalizedDocument> findByCrateIdAndIndexedFalse(UUID crateId);
  List<NormalizedDocument> findByCrateIdAndCurrentVersionTrue(UUID crateId);
  List<NormalizedDocument> findByCrateIdAndCurrentVersionTrueAndIndexedFalse(UUID crateId);
  List<NormalizedDocument> findByCrateIdAndCurrentVersionTrueAndCreatedAtGreaterThanEqual(
      UUID crateId, Instant createdAt);
  List<NormalizedDocument> findByCrateIdAndIndexedAtGreaterThanEqual(UUID crateId, Instant indexedAt);
  long countByCrateIdAndCurrentVersionTrue(UUID crateId);
  long countByCrateIdAndCurrentVersionTrueAndIndexedFalse(UUID crateId);

  @Query(
      """
      select d.crateId as crateId, count(d) as total from NormalizedDocument d
      where d.currentVersion = true and d.crateId in :crateIds group by d.crateId
      """)
  List<CrateCount> countCurrentByCrate(@Param("crateIds") Collection<UUID> crateIds);
}
