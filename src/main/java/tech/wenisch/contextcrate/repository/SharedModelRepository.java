package tech.wenisch.contextcrate.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.SharedModel;

public interface SharedModelRepository extends JpaRepository<SharedModel, UUID> {
  List<SharedModel> findAllByOrderByKindAscNameAsc();
  List<SharedModel> findByKindAndAvailableToUsersTrueOrderByNameAsc(SharedModel.Kind kind);
  List<SharedModel> findByKindAndDefaultForNewCratesTrue(SharedModel.Kind kind);
  boolean existsByKindAndNameIgnoreCase(SharedModel.Kind kind, String name);
}
