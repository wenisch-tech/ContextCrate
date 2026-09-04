package tech.wenisch.contextcrate.repository;

import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.contextcrate.domain.DocumentChunk;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {
  interface ChunkCount {
    UUID getDocumentId();

    long getChunkCount();
  }

  List<DocumentChunk> findByDocumentIdOrderByOrdinal(UUID documentId);

  void deleteByDocumentId(UUID documentId);

  List<DocumentChunk> findByDocumentIdAndCrateIdOrderByOrdinal(UUID documentId, UUID crateId);
  Optional<DocumentChunk> findByIdAndCrateId(UUID id, UUID crateId);
  List<DocumentChunk> findByCrateId(UUID crateId);
  long countByCrateId(UUID crateId);

  @Query("select c.crateId as crateId, count(c) as total from DocumentChunk c where c.crateId in :crateIds group by c.crateId")
  List<CrateCount> countByCrate(@Param("crateIds") Collection<UUID> crateIds);

  @org.springframework.data.jpa.repository.Query(
      "select c.documentId as documentId, count(c) as chunkCount from DocumentChunk c "
          + "where c.documentId in :documentIds group by c.documentId")
  List<ChunkCount> countByDocumentIdIn(@org.springframework.data.repository.query.Param("documentIds")
      Collection<UUID> documentIds);
}
