package tech.wenisch.contextcrate.repository;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tech.wenisch.contextcrate.domain.CrateMember;

public interface CrateMemberRepository extends JpaRepository<CrateMember, CrateMember.Key> {
  Optional<CrateMember> findByCrateIdAndUserId(UUID crateId, UUID userId);
  List<CrateMember> findByUserId(UUID userId);
  List<CrateMember> findByCrateId(UUID crateId);
  long countByCrateIdAndRole(UUID crateId, CrateMember.Role role);

  @Query("select m.crateId as crateId, count(m) as total from CrateMember m where m.crateId in :crateIds group by m.crateId")
  List<CrateCount> countByCrate(@Param("crateIds") Collection<UUID> crateIds);
}
