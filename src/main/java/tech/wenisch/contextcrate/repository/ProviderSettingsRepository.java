package tech.wenisch.contextcrate.repository;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.ProviderSettings;
public interface ProviderSettingsRepository extends JpaRepository<ProviderSettings,java.util.UUID>{
  List<ProviderSettings> findByEmbeddingSharedModelId(java.util.UUID id);
  List<ProviderSettings> findByRerankingSharedModelId(java.util.UUID id);
  List<ProviderSettings> findByAnsweringSharedModelId(java.util.UUID id);
}
