package tech.wenisch.harvex.repository;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.harvex.domain.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
  List<AuditLog> findTop100ByOrderByCreatedAtDesc();
}
