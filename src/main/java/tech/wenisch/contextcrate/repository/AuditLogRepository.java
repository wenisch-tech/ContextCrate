package tech.wenisch.contextcrate.repository;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
  List<AuditLog> findTop100ByOrderByCreatedAtDesc();
  List<AuditLog> findTop100ByCrateIdOrderByCreatedAtDesc(UUID crateId);
}
