package tech.wenisch.contextcrate.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.EvaluationGoldenItem;

public interface EvaluationGoldenItemRepository extends JpaRepository<EvaluationGoldenItem, UUID> {
  List<EvaluationGoldenItem> findByCrateIdOrderByCreatedAtAsc(UUID crateId);
  long countByCrateId(UUID crateId);
}
