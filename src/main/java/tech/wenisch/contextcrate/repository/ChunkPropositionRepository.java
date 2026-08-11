package tech.wenisch.contextcrate.repository;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.ChunkProposition;
public interface ChunkPropositionRepository extends JpaRepository<ChunkProposition,UUID>{List<ChunkProposition> findByChunkIdOrderByOrdinal(UUID chunkId);List<ChunkProposition> findByChunkIdAndAcceptedTrueOrderByOrdinal(UUID chunkId);List<ChunkProposition> findByCrateId(UUID crateId);}
