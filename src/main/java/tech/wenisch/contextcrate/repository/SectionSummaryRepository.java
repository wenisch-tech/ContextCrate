package tech.wenisch.contextcrate.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.SectionSummary;

public interface SectionSummaryRepository extends JpaRepository<SectionSummary, UUID> {
  List<SectionSummary> findByDocumentId(UUID documentId);
  List<SectionSummary> findByCrateIdAndIdIn(UUID crateId, Collection<UUID> ids);
}
