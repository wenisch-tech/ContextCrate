package tech.wenisch.contextcrate.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.EvaluationIgnoreRule;

public interface EvaluationIgnoreRuleRepository extends JpaRepository<EvaluationIgnoreRule, UUID> {
  List<EvaluationIgnoreRule> findByCrateIdOrderByCreatedAtAsc(UUID crateId);
  long countByCrateId(UUID crateId);
}
