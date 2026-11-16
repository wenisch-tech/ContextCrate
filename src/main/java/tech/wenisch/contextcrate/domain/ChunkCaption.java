package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Caption of a table or diagram chunk, written by the answer model. Only successful generations are stored. */
@Entity
@Table(name = "chunk_caption")
public class ChunkCaption {
  @Id private UUID id;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Column(name = "chunk_id", nullable = false, unique = true) private UUID chunkId;
  @Column(nullable = false, length = 16) private String kind;
  @Column(nullable = false, columnDefinition = "text") private String caption;
  @Column(nullable = false, length = 64) private String fingerprint;
  @Column(length = 500) private String model;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  protected ChunkCaption() {}
  public ChunkCaption(UUID id, UUID crateId, UUID chunkId, String kind, String caption, String fingerprint, String model) {
    this.id = id; this.crateId = crateId; this.chunkId = chunkId; this.kind = kind; this.caption = caption;
    this.fingerprint = fingerprint; this.model = model; this.createdAt = Instant.now();
  }
  public UUID getId() { return id; } public UUID getCrateId() { return crateId; } public UUID getChunkId() { return chunkId; }
  public String getKind() { return kind; } public String getCaption() { return caption; }
  public String getFingerprint() { return fingerprint; } public String getModel() { return model; } public Instant getCreatedAt() { return createdAt; }
}
