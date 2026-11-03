package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A question that a chunk answers, written by the model for document enrichment. */
@Entity
@Table(name = "chunk_question")
public class ChunkQuestion {
  @Id private UUID id;
  @Column(name = "crate_id", nullable = false) private UUID crateId;
  @Column(name = "chunk_id", nullable = false) private UUID chunkId;
  @Column(nullable = false) private int ordinal;
  @Column(nullable = false, columnDefinition = "text") private String question;
  @Column(nullable = false, length = 64) private String fingerprint;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  protected ChunkQuestion() {}
  public ChunkQuestion(UUID id, UUID crateId, UUID chunkId, int ordinal, String question, String fingerprint) {
    this.id = id; this.crateId = crateId; this.chunkId = chunkId; this.ordinal = ordinal;
    this.question = question; this.fingerprint = fingerprint; this.createdAt = Instant.now();
  }
  public UUID getId() { return id; } public UUID getCrateId() { return crateId; } public UUID getChunkId() { return chunkId; }
  public int getOrdinal() { return ordinal; } public String getQuestion() { return question; }
  public String getFingerprint() { return fingerprint; } public Instant getCreatedAt() { return createdAt; }
}
