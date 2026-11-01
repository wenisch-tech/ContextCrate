package tech.wenisch.contextcrate.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "crate_rag_settings")
public class RagSettings {
  @Id @Column(name="crate_id") private java.util.UUID crateId;
  @Column(name="strict_grounding",nullable=false) private boolean strictGrounding;
  @Column(name="allow_client_history",nullable=false) private boolean allowClientHistory;
  @Column(name="inline_citations",nullable=false) private boolean inlineCitations;
  @Column(name="structured_sources",nullable=false) private boolean structuredSources;
  @Column(name="grading_enabled",nullable=false) private boolean gradingEnabled;
  @Column(name="answer_verification_enabled",nullable=false) private boolean answerVerificationEnabled;
  @Column(name="answer_verification_failure_action",nullable=false,length=20) private String answerVerificationFailureAction;
  @Column(name="retrieval_mode",nullable=false,length=20) private String retrievalMode;
  @Column(name="retrieval_strategy",nullable=false,length=20) private String retrievalStrategy;
  @Column(name="proposition_failure_policy",nullable=false,length=30) private String propositionFailurePolicy;
  @Column(name="source_limit",nullable=false) private int sourceLimit;
  @Column(name="query_transformation_enabled",nullable=false) private boolean queryTransformationEnabled;
  /** Comma-separated canonical list of rewrite, step-back and sub-queries, or the single value auto. */
  @Column(name="query_transformation_strategies",nullable=false,length=60) private String queryTransformationStrategies="auto";
  @Column(name="image_indexing_enabled",nullable=false) private boolean imageIndexingEnabled=true;
  @Column(name="image_description_enabled",nullable=false) private boolean imageDescriptionEnabled;
  @Column(name="image_vision_model_id") private java.util.UUID imageVisionModelId;
  @Column(name="image_max_per_run",nullable=false) private int imageMaxPerRun=200;
  @Column(name="image_max_bytes",nullable=false) private int imageMaxBytes=5_242_880;
  @Column(name="image_min_size",nullable=false) private int imageMinSize=64;
  @Column(name="image_skip_patterns",nullable=false,length=500) private String imageSkipPatterns="";
  public boolean isImageIndexingEnabled(){return imageIndexingEnabled;} public boolean isImageDescriptionEnabled(){return imageDescriptionEnabled;}
  public java.util.UUID getImageVisionModelId(){return imageVisionModelId;} public int getImageMaxPerRun(){return imageMaxPerRun;} public int getImageMaxBytes(){return imageMaxBytes;}
  public int getImageMinSize(){return imageMinSize;} public String getImageSkipPatterns(){return imageSkipPatterns;}
  public void updateImages(boolean indexing,boolean description,java.util.UUID visionModelId,int maxPerRun,int maxBytes,int minSize,String skipPatterns){
    this.imageIndexingEnabled=indexing;this.imageDescriptionEnabled=indexing&&description;this.imageVisionModelId=visionModelId;
    this.imageMaxPerRun=Math.max(1,Math.min(maxPerRun,5000));this.imageMaxBytes=Math.max(10_000,Math.min(maxBytes,20_000_000));
    this.imageMinSize=Math.max(1,Math.min(minSize,2000));this.imageSkipPatterns=skipPatterns==null?"":skipPatterns.strip();
  }
  protected RagSettings() {}
  public RagSettings(boolean strictGrounding, boolean allowClientHistory, boolean inlineCitations, boolean structuredSources, boolean gradingEnabled, boolean answerVerificationEnabled, String answerVerificationFailureAction, String retrievalMode, int sourceLimit) { this(CrateIds.LEGACY,strictGrounding,allowClientHistory,inlineCitations,structuredSources,gradingEnabled,answerVerificationEnabled,answerVerificationFailureAction,retrievalMode,"standard","fail-indexing",sourceLimit); }
  public RagSettings(java.util.UUID crateId,boolean strictGrounding, boolean allowClientHistory, boolean inlineCitations, boolean structuredSources, boolean gradingEnabled, boolean answerVerificationEnabled, String answerVerificationFailureAction, String retrievalMode, int sourceLimit) { this(crateId,strictGrounding,allowClientHistory,inlineCitations,structuredSources,gradingEnabled,answerVerificationEnabled,answerVerificationFailureAction,retrievalMode,"standard","fail-indexing",sourceLimit); }
  public RagSettings(java.util.UUID crateId,boolean strictGrounding,boolean allowClientHistory,boolean inlineCitations,boolean structuredSources,boolean gradingEnabled,boolean answerVerificationEnabled,String answerVerificationFailureAction,String retrievalMode,String retrievalStrategy,String propositionFailurePolicy,int sourceLimit){this.crateId=crateId;this.strictGrounding=strictGrounding;this.allowClientHistory=allowClientHistory;this.inlineCitations=inlineCitations;this.structuredSources=structuredSources;this.gradingEnabled=gradingEnabled;this.answerVerificationEnabled=answerVerificationEnabled;this.answerVerificationFailureAction=answerVerificationFailureAction;this.retrievalMode=retrievalMode;this.retrievalStrategy=retrievalStrategy;this.propositionFailurePolicy=propositionFailurePolicy;this.sourceLimit=sourceLimit;}
  public java.util.UUID getCrateId(){return crateId;}
  public boolean isQueryTransformationEnabled(){return queryTransformationEnabled;} public String getQueryTransformationStrategies(){return queryTransformationStrategies;}
  public void updateQueryTransformation(boolean enabled,String strategies){this.queryTransformationEnabled=enabled;this.queryTransformationStrategies=strategies;}
  public boolean isStrictGrounding(){return strictGrounding;} public boolean isAllowClientHistory(){return allowClientHistory;} public boolean isInlineCitations(){return inlineCitations;} public boolean isStructuredSources(){return structuredSources;} public boolean isGradingEnabled(){return gradingEnabled;} public boolean isAnswerVerificationEnabled(){return answerVerificationEnabled;} public String getAnswerVerificationFailureAction(){return answerVerificationFailureAction;} public String getRetrievalMode(){return retrievalMode;} public String getRetrievalStrategy(){return retrievalStrategy;} public String getPropositionFailurePolicy(){return propositionFailurePolicy;} public int getSourceLimit(){return sourceLimit;}
  public void update(boolean strictGrounding,boolean allowClientHistory,boolean inlineCitations,boolean structuredSources,boolean gradingEnabled,boolean answerVerificationEnabled,String answerVerificationFailureAction,String retrievalMode,String retrievalStrategy,String propositionFailurePolicy,int sourceLimit){this.strictGrounding=strictGrounding;this.allowClientHistory=allowClientHistory;this.inlineCitations=inlineCitations;this.structuredSources=structuredSources;this.gradingEnabled=gradingEnabled;this.answerVerificationEnabled=answerVerificationEnabled;this.answerVerificationFailureAction=answerVerificationFailureAction;this.retrievalMode=retrievalMode;this.retrievalStrategy=retrievalStrategy;this.propositionFailurePolicy=propositionFailurePolicy;this.sourceLimit=sourceLimit;}
}
