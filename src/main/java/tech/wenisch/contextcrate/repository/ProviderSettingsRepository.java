package tech.wenisch.contextcrate.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.ProviderSettings;
public interface ProviderSettingsRepository extends JpaRepository<ProviderSettings,java.util.UUID>{}
