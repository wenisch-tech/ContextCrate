package tech.wenisch.contextcrate.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.EvaluationSettings;

public interface EvaluationSettingsRepository extends JpaRepository<EvaluationSettings, UUID> {
  List<EvaluationSettings> findByJudgeSharedModelId(UUID id);
}
