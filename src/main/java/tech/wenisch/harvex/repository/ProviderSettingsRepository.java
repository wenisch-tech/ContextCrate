package tech.wenisch.harvex.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.harvex.domain.ProviderSettings;
public interface ProviderSettingsRepository extends JpaRepository<ProviderSettings,Integer>{}
