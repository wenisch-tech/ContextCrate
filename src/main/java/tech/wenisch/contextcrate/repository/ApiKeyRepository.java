package tech.wenisch.contextcrate.repository;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.ApiKey;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
  Optional<ApiKey> findByKeyHashAndRevokedFalse(String hash);
  java.util.List<ApiKey> findByUserIdOrderByCreatedAtDesc(UUID userId);
  java.util.List<ApiKey> findByCrateIdOrderByCreatedAtDesc(UUID crateId);
}
