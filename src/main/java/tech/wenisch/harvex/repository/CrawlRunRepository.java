package tech.wenisch.harvex.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.harvex.domain.CrawlRun;

public interface CrawlRunRepository extends JpaRepository<CrawlRun, UUID> {
  List<CrawlRun> findTop20ByOrderByStartedAtDesc();
}
