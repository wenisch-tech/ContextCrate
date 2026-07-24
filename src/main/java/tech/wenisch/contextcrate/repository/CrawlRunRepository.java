package tech.wenisch.contextcrate.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.CrawlRun;

public interface CrawlRunRepository extends JpaRepository<CrawlRun, UUID> {
  List<CrawlRun> findTop20ByOrderByStartedAtDesc();
  List<CrawlRun> findTop20ByCrateIdOrderByStartedAtDesc(UUID crateId);
  Optional<CrawlRun> findByIdAndCrateId(UUID id, UUID crateId);
  List<CrawlRun> findByCrateId(UUID crateId);
}
