package tech.wenisch.contextcrate.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.ChunkCaption;

public interface ChunkCaptionRepository extends JpaRepository<ChunkCaption, UUID> {
  Optional<ChunkCaption> findByChunkId(UUID chunkId);
  void deleteByChunkId(UUID chunkId);
}
