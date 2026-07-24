package tech.wenisch.contextcrate.repository;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.Source;

public interface SourceRepository extends JpaRepository<Source, UUID> {
  List<Source> findByCrateIdOrderByCreatedAtDesc(UUID crateId);
  Optional<Source> findByIdAndCrateId(UUID id, UUID crateId);
  List<Source> findByCrateId(UUID crateId);
}
