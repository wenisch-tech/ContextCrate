package tech.wenisch.harvex.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.harvex.domain.FetchRecord;

public interface FetchRecordRepository extends JpaRepository<FetchRecord, UUID> {
  List<FetchRecord> findTop100ByRunIdOrderByFetchedAtDesc(UUID runId);
}
