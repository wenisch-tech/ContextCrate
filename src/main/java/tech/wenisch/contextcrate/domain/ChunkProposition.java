package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name="chunk_proposition")
public class ChunkProposition {
  @Id private UUID id;
  @Column(name="evaluation_id",nullable=false) private UUID evaluationId;
  @Column(name="crate_id",nullable=false) private UUID crateId;
  @Column(name="chunk_id",nullable=false) private UUID chunkId;
  @Column(nullable=false) private int ordinal;
  @Column(nullable=false,columnDefinition="text") private String proposition;
  @Column(name="fidelity_score",nullable=false) private int fidelityScore;
  @Column(name="context_score",nullable=false) private int contextScore;
  @Column(name="completeness_score",nullable=false) private int completenessScore;
  @Column(name="focus_score",nullable=false) private int focusScore;
  @Column(nullable=false) private boolean accepted;
  protected ChunkProposition() {}
  public ChunkProposition(UUID id,UUID evaluationId,UUID crateId,UUID chunkId,int ordinal,String proposition,int fidelityScore,int contextScore,int completenessScore,int focusScore){this.id=id;this.evaluationId=evaluationId;this.crateId=crateId;this.chunkId=chunkId;this.ordinal=ordinal;this.proposition=proposition;this.fidelityScore=fidelityScore;this.contextScore=contextScore;this.completenessScore=completenessScore;this.focusScore=focusScore;this.accepted=fidelityScore>7&&contextScore>7&&completenessScore>7&&focusScore>7;}
  public UUID getId(){return id;} public UUID getEvaluationId(){return evaluationId;} public UUID getCrateId(){return crateId;} public UUID getChunkId(){return chunkId;} public int getOrdinal(){return ordinal;} public String getProposition(){return proposition;} public int getFidelityScore(){return fidelityScore;} public int getContextScore(){return contextScore;} public int getCompletenessScore(){return completenessScore;} public int getFocusScore(){return focusScore;} public boolean isAccepted(){return accepted;}
}
