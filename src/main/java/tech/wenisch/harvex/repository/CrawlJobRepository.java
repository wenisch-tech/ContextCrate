package tech.wenisch.harvex.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.harvex.domain.CrawlJob;

public interface CrawlJobRepository extends JpaRepository<CrawlJob, UUID> {}
