package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="proposition_evaluation")
public class PropositionEvaluation {
  @Id private UUID id;
  @Column(name="crate_id",nullable=false) private UUID crateId;
  @Column(name="chunk_id",nullable=false,unique=true) private UUID chunkId;
  @Column(name="content_hash",nullable=false,length=64) private String contentHash;
  @Column(nullable=false,length=64) private String fingerprint;
  @Column(length=500) private String model;
  @Column(nullable=false,length=20) private String status;
  @Column(name="error_message",columnDefinition="text") private String errorMessage;
  @Column(name="created_at",nullable=false) private Instant createdAt;
  protected PropositionEvaluation() {}
  public PropositionEvaluation(UUID id,UUID crateId,UUID chunkId,String contentHash,String fingerprint,String model,String status,String errorMessage){this.id=id;this.crateId=crateId;this.chunkId=chunkId;this.contentHash=contentHash;this.fingerprint=fingerprint;this.model=model;this.status=status;this.errorMessage=errorMessage;this.createdAt=Instant.now();}
  public UUID getId(){return id;} public UUID getCrateId(){return crateId;} public UUID getChunkId(){return chunkId;} public String getContentHash(){return contentHash;} public String getFingerprint(){return fingerprint;} public String getModel(){return model;} public String getStatus(){return status;} public String getErrorMessage(){return errorMessage;} public Instant getCreatedAt(){return createdAt;}
}
