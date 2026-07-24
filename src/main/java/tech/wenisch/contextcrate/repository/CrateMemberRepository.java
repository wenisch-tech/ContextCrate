package tech.wenisch.contextcrate.repository;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.CrateMember;

public interface CrateMemberRepository extends JpaRepository<CrateMember, CrateMember.Key> {
  Optional<CrateMember> findByCrateIdAndUserId(UUID crateId, UUID userId);
  List<CrateMember> findByUserId(UUID userId);
  List<CrateMember> findByCrateId(UUID crateId);
  long countByCrateIdAndRole(UUID crateId, CrateMember.Role role);
}
