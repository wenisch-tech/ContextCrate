package tech.wenisch.contextcrate.repository;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.AdminElevation;

public interface AdminElevationRepository extends JpaRepository<AdminElevation, UUID> {
  List<AdminElevation> findByAdminUserIdAndCrateIdAndEndedAtIsNullAndExpiresAtAfter(
      UUID adminUserId, UUID crateId, Instant now);
  List<AdminElevation> findByEndedAtIsNullAndExpiresAtBefore(Instant now);
}
