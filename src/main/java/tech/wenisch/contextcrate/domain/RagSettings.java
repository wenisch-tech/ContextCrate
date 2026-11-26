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
  /** HyDE: embed an LLM-written hypothetical answer instead of the question for the vector search. */
  /** off, title, title-summary or title-summary-sections: header put in front of every chunk before it is embedded. */
  @Column(name="context_header_variant",nullable=false,length=30) private String contextHeaderVariant="off";
  /** Words from the start of a document that are sent to the model to create its title. */
  @Column(name="context_header_words",nullable=false) private int contextHeaderWords=1000;
  /** Chunks before and after a matched chunk that are added to the answer context; 0 = off. */
  @Column(name="context_window",nullable=false) private int contextWindow;
  /** After retrieval a model keeps only the sentences of each chunk that are relevant for the question. */
  @Column(name="context_compression_enabled",nullable=false) private boolean contextCompressionEnabled;
  /** Document enrichment: the model writes questions per chunk; they are indexed and point back to the chunk. */
  @Column(name="enrichment_enabled",nullable=false) private boolean enrichmentEnabled;
  @Column(name="enrichment_questions",nullable=false) private int enrichmentQuestions=5;
  /** Instructions of the crate owner for the answer model; applied only while the administrator allows crate system prompts. */
  @Column(name="system_prompt",columnDefinition="text") private String systemPrompt;
  @Column(name="hyde_enabled",nullable=false) private boolean hydeEnabled;
  /** Adaptive retrieval: a model classifies the question (factual, analytical, opinion) and the retrieval strategy follows the category. */
  @Column(name="adaptive_retrieval_enabled",nullable=false) private boolean adaptiveRetrievalEnabled;
  /** Graph RAG: entities and relations are extracted per chunk and a knowledge graph with communities is built after indexing. */
  @Column(name="graph_extraction_enabled",nullable=false) private boolean graphExtractionEnabled;
  /** Graph RAG as retrieval strategy (needs a built graph); graphSearchMode is local, global or auto. */
  @Column(name="graph_retrieval_enabled",nullable=false) private boolean graphRetrievalEnabled;
  @Column(name="graph_search_mode",nullable=false,length=10) private String graphSearchMode="auto";
  public boolean isGraphExtractionEnabled(){return graphExtractionEnabled;} public boolean isGraphRetrievalEnabled(){return graphRetrievalEnabled;} public String getGraphSearchMode(){return graphSearchMode;}
  public void updateGraphRag(boolean extraction,boolean retrieval,String mode){this.graphExtractionEnabled=extraction;this.graphRetrievalEnabled=retrieval;this.graphSearchMode=mode;}
  public boolean isAdaptiveRetrievalEnabled(){return adaptiveRetrievalEnabled;} public void updateAdaptiveRetrieval(boolean enabled){this.adaptiveRetrievalEnabled=enabled;}
  /** LLM based reranking: the answer model rates every retrieved candidate from 1 to 10; the best llmRerankTopN are kept. */
  @Column(name="llm_rerank_enabled",nullable=false) private boolean llmRerankEnabled;
  @Column(name="llm_rerank_top_n",nullable=false) private int llmRerankTopN=4;
  public boolean isLlmRerankEnabled(){return llmRerankEnabled;} public int getLlmRerankTopN(){return llmRerankTopN;}
  public void updateLlmRerank(boolean enabled,int topN){this.llmRerankEnabled=enabled;this.llmRerankTopN=Math.max(1,Math.min(topN,20));}
  /** Hierarchical retrieval: section summaries are searched first; only chunks of the best sections are searched next. */
  @Column(name="hierarchical_enabled",nullable=false) private boolean hierarchicalEnabled;
  @Column(name="hierarchical_top_k",nullable=false) private int hierarchicalTopK=4;
  public boolean isHierarchicalEnabled(){return hierarchicalEnabled;} public int getHierarchicalTopK(){return hierarchicalTopK;}
  public void updateHierarchical(boolean enabled,int topK){this.hierarchicalEnabled=enabled;this.hierarchicalTopK=Math.max(1,Math.min(topK,10));}
  /** Dartboard retrieval: K x oversampling candidates are retrieved and K relevant, mutually different chunks are chosen. */
  @Column(name="dartboard_enabled",nullable=false) private boolean dartboardEnabled;
  @Column(name="dartboard_oversampling",nullable=false) private int dartboardOversampling=3;
  @Column(name="dartboard_relevance_weight",nullable=false) private double dartboardRelevanceWeight=1.0;
  @Column(name="dartboard_diversity_weight",nullable=false) private double dartboardDiversityWeight=1.0;
  @Column(name="dartboard_sigma",nullable=false) private double dartboardSigma=0.1;
  public boolean isDartboardEnabled(){return dartboardEnabled;} public int getDartboardOversampling(){return dartboardOversampling;} public double getDartboardRelevanceWeight(){return dartboardRelevanceWeight;} public double getDartboardDiversityWeight(){return dartboardDiversityWeight;} public double getDartboardSigma(){return dartboardSigma;}
  public void updateDartboard(boolean enabled,int oversampling,double relevanceWeight,double diversityWeight,double sigma){this.dartboardEnabled=enabled;this.dartboardOversampling=oversampling;this.dartboardRelevanceWeight=relevanceWeight;this.dartboardDiversityWeight=diversityWeight;this.dartboardSigma=sigma;}
  /** Answer feedback: ratings, feedback-weighted retrieval and periodic enrichment of the index with rated answers. */
  @Column(name="feedback_enabled",nullable=false) private boolean feedbackEnabled;
  @Column(name="feedback_link_ttl_hours",nullable=false) private int feedbackLinkTtlHours=168;
  @Column(name="feedback_retrieval_enabled",nullable=false) private boolean feedbackRetrievalEnabled;
  @Column(name="feedback_retrieval_max_per_document",nullable=false) private int feedbackRetrievalMaxPerDocument=10;
  @Column(name="feedback_enrichment_enabled",nullable=false) private boolean feedbackEnrichmentEnabled;
  @Column(name="feedback_enrichment_interval_hours",nullable=false) private int feedbackEnrichmentIntervalHours=24;
  @Column(name="feedback_enrichment_min_quality",nullable=false) private int feedbackEnrichmentMinQuality=4;
  @Column(name="feedback_enrichment_min_relevance",nullable=false) private int feedbackEnrichmentMinRelevance=3;
  @Column(name="feedback_enrichment_requires_approval",nullable=false) private boolean feedbackEnrichmentRequiresApproval=true;
  @Column(name="feedback_enrichment_last_run") private java.time.Instant feedbackEnrichmentLastRun;
  public boolean isFeedbackEnabled(){return feedbackEnabled;} public int getFeedbackLinkTtlHours(){return feedbackLinkTtlHours;} public boolean isFeedbackRetrievalEnabled(){return feedbackRetrievalEnabled;} public int getFeedbackRetrievalMaxPerDocument(){return feedbackRetrievalMaxPerDocument;}
  public boolean isFeedbackEnrichmentEnabled(){return feedbackEnrichmentEnabled;} public int getFeedbackEnrichmentIntervalHours(){return feedbackEnrichmentIntervalHours;} public int getFeedbackEnrichmentMinQuality(){return feedbackEnrichmentMinQuality;} public int getFeedbackEnrichmentMinRelevance(){return feedbackEnrichmentMinRelevance;} public boolean isFeedbackEnrichmentRequiresApproval(){return feedbackEnrichmentRequiresApproval;} public java.time.Instant getFeedbackEnrichmentLastRun(){return feedbackEnrichmentLastRun;}
  public void updateFeedback(boolean enabled,int linkTtlHours,boolean retrieval,int maxPerDocument,boolean enrichment,int intervalHours,int minQuality,int minRelevance,boolean requiresApproval){this.feedbackEnabled=enabled;this.feedbackLinkTtlHours=linkTtlHours;this.feedbackRetrievalEnabled=retrieval;this.feedbackRetrievalMaxPerDocument=maxPerDocument;this.feedbackEnrichmentEnabled=enrichment;this.feedbackEnrichmentIntervalHours=intervalHours;this.feedbackEnrichmentMinQuality=minQuality;this.feedbackEnrichmentMinRelevance=minRelevance;this.feedbackEnrichmentRequiresApproval=requiresApproval;}
  public void feedbackEnrichmentRan(java.time.Instant at){this.feedbackEnrichmentLastRun=at;}
  /** Captions: the answer model describes tables and diagram code; the caption is indexed next to the chunk. */
  @Column(name="captions_enabled",nullable=false) private boolean captionsEnabled;
  public boolean isCaptionsEnabled(){return captionsEnabled;} public void updateCaptions(boolean enabled){this.captionsEnabled=enabled;}
  /** Retrieval mode "fusion": weight of the vector leg (0 to 1); the keyword leg gets the rest. */
  @Column(name="fusion_vector_weight",nullable=false) private double fusionVectorWeight=0.5;
  @Column(name="image_indexing_enabled",nullable=false) private boolean imageIndexingEnabled=true;
  @Column(name="image_description_enabled",nullable=false) private boolean imageDescriptionEnabled;
  @Column(name="image_vision_model_id") private java.util.UUID imageVisionModelId;
  @Column(name="image_max_per_run",nullable=false) private int imageMaxPerRun=200;
  @Column(name="image_max_bytes",nullable=false) private int imageMaxBytes=5_242_880;
  @Column(name="image_min_size",nullable=false) private int imageMinSize=64;
  @Column(name="image_skip_patterns",nullable=false,length=500) private String imageSkipPatterns="";
  /** Attach the pictures of retrieved image sources to the answer request as base64 {@code image_url} parts. */
  @Column(name="send_images_to_model",nullable=false) private boolean sendImagesToModel=true;
  /** Let the answer model place a picture of a source in its answer; the client receives it as a base64 Markdown image. */
  @Column(name="include_images_in_answer",nullable=false) private boolean includeImagesInAnswer=true;
  public boolean isIncludeImagesInAnswer(){return includeImagesInAnswer;} public void updateIncludeImages(boolean enabled){this.includeImagesInAnswer=enabled;}
  public boolean isSendImagesToModel(){return sendImagesToModel;} public void updateSendImages(boolean enabled){this.sendImagesToModel=enabled;}
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
  public String getContextHeaderVariant(){return contextHeaderVariant;} public int getContextHeaderWords(){return contextHeaderWords;} public void updateContextHeader(String variant,int words){this.contextHeaderVariant=variant;this.contextHeaderWords=words;}
  public String getSystemPrompt(){return systemPrompt;} public void updateSystemPrompt(String prompt){this.systemPrompt=prompt;}
  public boolean isEnrichmentEnabled(){return enrichmentEnabled;} public int getEnrichmentQuestions(){return enrichmentQuestions;} public void updateEnrichment(boolean enabled,int questions){this.enrichmentEnabled=enabled;this.enrichmentQuestions=questions;}
  public boolean isContextCompressionEnabled(){return contextCompressionEnabled;} public void updateContextCompression(boolean enabled){this.contextCompressionEnabled=enabled;}
  public int getContextWindow(){return contextWindow;} public void updateContextWindow(int radius){this.contextWindow=radius;}
  public double getFusionVectorWeight(){return fusionVectorWeight;} public void updateFusionVectorWeight(double weight){this.fusionVectorWeight=weight;}
  public boolean isHydeEnabled(){return hydeEnabled;} public void updateHyde(boolean enabled){this.hydeEnabled=enabled;}
  public void updateQueryTransformation(boolean enabled,String strategies){this.queryTransformationEnabled=enabled;this.queryTransformationStrategies=strategies;}
  public boolean isStrictGrounding(){return strictGrounding;} public boolean isAllowClientHistory(){return allowClientHistory;} public boolean isInlineCitations(){return inlineCitations;} public boolean isStructuredSources(){return structuredSources;} public boolean isGradingEnabled(){return gradingEnabled;} public boolean isAnswerVerificationEnabled(){return answerVerificationEnabled;} public String getAnswerVerificationFailureAction(){return answerVerificationFailureAction;} public String getRetrievalMode(){return retrievalMode;} public String getRetrievalStrategy(){return retrievalStrategy;} public String getPropositionFailurePolicy(){return propositionFailurePolicy;} public int getSourceLimit(){return sourceLimit;}
  public void update(boolean strictGrounding,boolean allowClientHistory,boolean inlineCitations,boolean structuredSources,boolean gradingEnabled,boolean answerVerificationEnabled,String answerVerificationFailureAction,String retrievalMode,String retrievalStrategy,String propositionFailurePolicy,int sourceLimit){this.strictGrounding=strictGrounding;this.allowClientHistory=allowClientHistory;this.inlineCitations=inlineCitations;this.structuredSources=structuredSources;this.gradingEnabled=gradingEnabled;this.answerVerificationEnabled=answerVerificationEnabled;this.answerVerificationFailureAction=answerVerificationFailureAction;this.retrievalMode=retrievalMode;this.retrievalStrategy=retrievalStrategy;this.propositionFailurePolicy=propositionFailurePolicy;this.sourceLimit=sourceLimit;}
}
