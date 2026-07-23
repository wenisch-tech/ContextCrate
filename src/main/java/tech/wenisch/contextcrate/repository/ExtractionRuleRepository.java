package tech.wenisch.contextcrate.repository;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.ExtractionRule;

public interface ExtractionRuleRepository extends JpaRepository<ExtractionRule, UUID> {
  List<ExtractionRule> findByEnabledTrueOrderByNameAsc();
  List<ExtractionRule> findByCrateIdAndEnabledTrueOrderByNameAsc(UUID crateId);

  List<ExtractionRule> findAllByOrderByCreatedAtDesc();
  List<ExtractionRule> findByCrateIdOrderByCreatedAtDesc(UUID crateId);
  Optional<ExtractionRule> findByIdAndCrateId(UUID id, UUID crateId);
}
