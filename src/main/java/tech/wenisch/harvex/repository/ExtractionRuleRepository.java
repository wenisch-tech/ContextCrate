package tech.wenisch.harvex.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.harvex.domain.ExtractionRule;

public interface ExtractionRuleRepository extends JpaRepository<ExtractionRule, UUID> {
  List<ExtractionRule> findByEnabledTrueOrderByNameAsc();

  List<ExtractionRule> findAllByOrderByCreatedAtDesc();
}