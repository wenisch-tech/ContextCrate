package tech.wenisch.contextcrate.repository;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.CrateIndexGeneration;
public interface CrateIndexGenerationRepository extends JpaRepository<CrateIndexGeneration,CrateIndexGeneration.Key>{
  List<CrateIndexGeneration> findByCrateIdOrderByGenerationDesc(UUID crateId);
  List<CrateIndexGeneration> findByCrateIdAndStatus(UUID crateId,CrateIndexGeneration.Status status);
  Optional<CrateIndexGeneration> findTopByCrateIdOrderByGenerationDesc(UUID crateId);
}
