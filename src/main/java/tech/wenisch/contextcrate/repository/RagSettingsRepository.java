package tech.wenisch.contextcrate.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.RagSettings;
public interface RagSettingsRepository extends JpaRepository<RagSettings,java.util.UUID> {}
