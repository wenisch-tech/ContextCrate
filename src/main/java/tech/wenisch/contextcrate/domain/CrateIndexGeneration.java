package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="crate_index_generation")
@IdClass(CrateIndexGeneration.Key.class)
public class CrateIndexGeneration {
  public enum Status { BUILDING, ACTIVE, FAILED, RETIRED }
  @Id @Column(name="crate_id") private UUID crateId;
  @Id private int generation;
  @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private Status status;
  @Column(name="configuration_fingerprint",nullable=false,length=128) private String configurationFingerprint;
  @Column(length=500) private String model;
  private Integer dimensions;
  @Column(name="document_count",nullable=false) private long documentCount;
  @Column(name="error_message",columnDefinition="text") private String errorMessage;
  @Column(name="created_at",nullable=false) private Instant createdAt;
  @Column(name="activated_at") private Instant activatedAt;
  protected CrateIndexGeneration(){}
  public CrateIndexGeneration(UUID crateId,int generation,String fingerprint,String model,Integer dimensions){
    this.crateId=crateId;this.generation=generation;this.configurationFingerprint=fingerprint;
    this.model=model;this.dimensions=dimensions;status=Status.BUILDING;createdAt=Instant.now();
  }
  public void activate(long count){status=Status.ACTIVE;documentCount=count;activatedAt=Instant.now();errorMessage=null;}
  public void fail(Exception error){status=Status.FAILED;errorMessage=error.getMessage();}
  public void retire(){status=Status.RETIRED;}
  public UUID getCrateId(){return crateId;} public int getGeneration(){return generation;}
  public Status getStatus(){return status;} public String getConfigurationFingerprint(){return configurationFingerprint;}
  public String getModel(){return model;} public Integer getDimensions(){return dimensions;}
  public long getDocumentCount(){return documentCount;} public String getErrorMessage(){return errorMessage;}
  public Instant getCreatedAt(){return createdAt;} public Instant getActivatedAt(){return activatedAt;}
  public record Key(UUID crateId,int generation) implements Serializable{}
}
