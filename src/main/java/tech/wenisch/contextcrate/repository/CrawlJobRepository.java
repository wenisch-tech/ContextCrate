package tech.wenisch.contextcrate.repository;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.CrawlJob;

public interface CrawlJobRepository extends JpaRepository<CrawlJob, UUID> {
  List<CrawlJob> findByCrateIdOrderByCreatedAtDesc(UUID crateId);
  Optional<CrawlJob> findByIdAndCrateId(UUID id, UUID crateId);
}
