package tech.wenisch.contextcrate.repository;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tech.wenisch.contextcrate.domain.Source;

public interface SourceRepository extends JpaRepository<Source, UUID> {
  List<Source> findByCrateIdOrderByCreatedAtDesc(UUID crateId);
  Optional<Source> findByIdAndCrateId(UUID id, UUID crateId);
  List<Source> findByCrateId(UUID crateId);

  @Query("select s.crateId as crateId, count(s) as total from Source s where s.crateId in :crateIds group by s.crateId")
  List<CrateCount> countByCrate(@Param("crateIds") Collection<UUID> crateIds);
}
