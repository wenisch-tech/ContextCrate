package tech.wenisch.contextcrate.repository;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.*;

public interface SourceItemRepository extends JpaRepository<SourceItem, UUID> {
  Optional<SourceItem> findByRunIdAndSourceUri(UUID runId, String sourceUri);
  long countByRunId(UUID runId);
  long countByRunIdAndStatus(UUID runId, PipelineTypes.FrontierStatus status);
  List<SourceItem> findTop100ByRunIdOrderByDiscoveredAtDesc(UUID runId);
  List<SourceItem> findByCrateId(UUID crateId);
}
