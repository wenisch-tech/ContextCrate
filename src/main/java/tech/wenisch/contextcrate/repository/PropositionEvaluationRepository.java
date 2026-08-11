package tech.wenisch.contextcrate.repository;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.PropositionEvaluation;
public interface PropositionEvaluationRepository extends JpaRepository<PropositionEvaluation,UUID>{Optional<PropositionEvaluation> findByChunkId(UUID chunkId);List<PropositionEvaluation> findByCrateId(UUID crateId);void deleteByChunkId(UUID chunkId);}
