package tech.wenisch.contextcrate.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.CrateBackendSettings;

public interface CrateBackendSettingsRepository extends JpaRepository<CrateBackendSettings, UUID> {}
