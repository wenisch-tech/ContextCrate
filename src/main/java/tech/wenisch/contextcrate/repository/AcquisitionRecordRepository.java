package tech.wenisch.contextcrate.repository;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.AcquisitionRecord;

public interface AcquisitionRecordRepository extends JpaRepository<AcquisitionRecord, UUID> {
  List<AcquisitionRecord> findTop100ByRunIdOrderByFetchedAtDesc(UUID runId);
  List<AcquisitionRecord> findByCrateId(UUID crateId);
}
