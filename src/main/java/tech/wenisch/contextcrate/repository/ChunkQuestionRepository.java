package tech.wenisch.contextcrate.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.ChunkQuestion;

public interface ChunkQuestionRepository extends JpaRepository<ChunkQuestion, UUID> {
  List<ChunkQuestion> findByChunkIdOrderByOrdinal(UUID chunkId);
  List<ChunkQuestion> findByChunkIdAndCrateIdOrderByOrdinal(UUID chunkId, UUID crateId);
  void deleteByChunkId(UUID chunkId);
}
