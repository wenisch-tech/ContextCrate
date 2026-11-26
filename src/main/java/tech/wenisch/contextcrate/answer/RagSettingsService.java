package tech.wenisch.contextcrate.answer;

import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.RagSettings;
import tech.wenisch.contextcrate.repository.RagSettingsRepository;

@Service
public class RagSettingsService {
  private final RagSettingsRepository settings; private final ContextCrateProperties.Answering config;private final tech.wenisch.contextcrate.config.RuntimeProviderSettings providers;
  public RagSettingsService(RagSettingsRepository settings, ContextCrateProperties properties){this(settings,properties,null);}
  @org.springframework.beans.factory.annotation.Autowired public RagSettingsService(RagSettingsRepository settings,ContextCrateProperties properties,tech.wenisch.contextcrate.config.RuntimeProviderSettings providers){this.settings=settings;this.config=properties.answering();this.providers=providers;}
  @Transactional public RagSettings current(){return current(tech.wenisch.contextcrate.config.CrateContext.current());}
  @Transactional public RagSettings current(java.util.UUID crateId){return settings.findById(crateId).orElseGet(()->settings.save(new RagSettings(crateId,false,true,true,true,true,true,"revise-once",config.retrievalMode(),"standard","fail-indexing",config.sourceLimit())));}
  @Transactional public RagSettings update(boolean strict,boolean history,boolean inline,boolean structured,boolean grading,boolean verification,String verificationAction,String mode,int limit){return update(tech.wenisch.contextcrate.config.CrateContext.current(),strict,history,inline,structured,grading,verification,verificationAction,mode,limit);}
  @Transactional public RagSettings update(java.util.UUID crateId,boolean strict,boolean history,boolean inline,boolean structured,boolean grading,boolean verification,String verificationAction,String mode,int limit){var value=current(crateId);return update(crateId,strict,history,inline,structured,grading,verification,verificationAction,mode,value.getRetrievalStrategy(),value.getPropositionFailurePolicy(),limit);}
  @Transactional public RagSettings update(java.util.UUID crateId,boolean strict,boolean history,boolean inline,boolean structured,boolean grading,boolean verification,String verificationAction,String mode,String strategy,String failurePolicy,int limit){if(!Set.of("lexical","semantic","hybrid","fusion").contains(mode))throw new IllegalArgumentException("retrieval mode must be lexical, semantic, hybrid, or fusion");if(!Set.of("standard","proposition").contains(strategy))throw new IllegalArgumentException("retrieval strategy must be standard or proposition");if(!Set.of("fail-indexing","skip-chunk","embed-source-chunk").contains(failurePolicy))throw new IllegalArgumentException("invalid proposition failure policy");if(!Set.of("revise-once","block-answer","return-warning").contains(verificationAction))throw new IllegalArgumentException("answer verification action must be revise-once, block-answer, or return-warning");if(limit<1||limit>config.sourceLimit())throw new IllegalArgumentException("source limit must be between 1 and "+config.sourceLimit());if("proposition".equals(strategy))validateProviders(crateId);var value=current(crateId);value.update(strict,history,inline,structured,grading,verification,verificationAction,mode,strategy,failurePolicy,limit);return settings.save(value);}
  /**
   * Stores the query-transformation switch and strategies. Strategies are validated and stored in
   * canonical order (rewrite, step-back, sub-queries) or as the single value {@code auto}. When the
   * feature is switched off and no strategy is supplied the previous selection is kept.
   */
  @Transactional public RagSettings updateQueryTransformation(java.util.UUID crateId,boolean enabled,java.util.Collection<String> strategies){
    var value=current(crateId);
    String normalized=strategies==null||strategies.stream().allMatch(s->s==null||s.isBlank())?null:QueryTransformationService.normalize(strategies);
    if(normalized==null){if(enabled)throw new IllegalArgumentException("select at least one query transformation strategy");normalized=value.getQueryTransformationStrategies();}
    value.updateQueryTransformation(enabled,normalized);return settings.save(value);
  }
  /** Validates and stores the contextual chunk header variant; the caller decides whether the change needs an index rebuild. */
  @Transactional public RagSettings updateContextHeader(java.util.UUID crateId,String variant,int words){
    String normalized=variant==null?"off":variant.strip().toLowerCase(java.util.Locale.ROOT);
    if(!tech.wenisch.contextcrate.service.ContextHeaderService.VARIANTS.contains(normalized))throw new IllegalArgumentException("context header variant must be off, title, title-summary or title-summary-sections");
    if(words<tech.wenisch.contextcrate.service.ContextHeaderService.MIN_WORDS||words>tech.wenisch.contextcrate.service.ContextHeaderService.MAX_WORDS)throw new IllegalArgumentException("context header words must be between "+tech.wenisch.contextcrate.service.ContextHeaderService.MIN_WORDS+" and "+tech.wenisch.contextcrate.service.ContextHeaderService.MAX_WORDS);
    var value=current(crateId);value.updateContextHeader(normalized,words);return settings.save(value);
  }
  /** Chunks before and after a matched chunk (0 = off); takes effect on the next question, no rebuild. */
  /** Stores the crate's system prompt (cleaned, length-checked). Whether it is allowed is decided by the caller. */
  @Transactional public RagSettings updateSystemPrompt(java.util.UUID crateId,String prompt){var value=current(crateId);value.updateSystemPrompt(SystemPrompts.validated(prompt));return settings.save(value);}
  public static final int MIN_ENRICHMENT_QUESTIONS=3,MAX_ENRICHMENT_QUESTIONS=50,DEFAULT_ENRICHMENT_QUESTIONS=5;
  @Transactional public RagSettings updateEnrichment(java.util.UUID crateId,boolean enabled,int questions){
    if(questions<MIN_ENRICHMENT_QUESTIONS||questions>MAX_ENRICHMENT_QUESTIONS)throw new IllegalArgumentException("enrichment questions must be between "+MIN_ENRICHMENT_QUESTIONS+" and "+MAX_ENRICHMENT_QUESTIONS);
    var value=current(crateId);value.updateEnrichment(enabled,questions);return settings.save(value);
  }
  @Transactional public RagSettings updateContextCompression(java.util.UUID crateId,boolean enabled){var value=current(crateId);value.updateContextCompression(enabled);return settings.save(value);}
  @Transactional public RagSettings updateContextWindow(java.util.UUID crateId,int radius){
    if(radius<0||radius>ContextWindowService.MAX_RADIUS)throw new IllegalArgumentException("context window must be between 0 and "+ContextWindowService.MAX_RADIUS);
    var value=current(crateId);value.updateContextWindow(radius);return settings.save(value);
  }
  @Transactional public RagSettings updateFusion(java.util.UUID crateId,double vectorWeight){
    if(Double.isNaN(vectorWeight)||vectorWeight<0||vectorWeight>1)throw new IllegalArgumentException("fusion vector weight must be between 0 and 1");
    var value=current(crateId);value.updateFusionVectorWeight(vectorWeight);return settings.save(value);}
  @Transactional public RagSettings updateIncludeImages(java.util.UUID crateId,boolean enabled){var value=current(crateId);value.updateIncludeImages(enabled);return settings.save(value);}
  @Transactional public RagSettings updateSendImages(java.util.UUID crateId,boolean enabled){var value=current(crateId);value.updateSendImages(enabled);return settings.save(value);}
  @Transactional public RagSettings updateLlmRerank(java.util.UUID crateId,boolean enabled,int topN){var value=current(crateId);value.updateLlmRerank(enabled,topN);return settings.save(value);}
  /** Captions for tables and diagrams. Changing the switch needs an index rebuild (the caller schedules it). */
  @Transactional public RagSettings updateCaptions(java.util.UUID crateId,boolean enabled){var value=current(crateId);value.updateCaptions(enabled);return settings.save(value);}
  public static final int MIN_FEEDBACK_TTL_HOURS=1,MAX_FEEDBACK_TTL_HOURS=720,MAX_FEEDBACK_PER_DOCUMENT=50;
  /** Answer feedback switches and thresholds; takes effect immediately, no rebuild. */
  @Transactional public RagSettings updateFeedback(java.util.UUID crateId,boolean enabled,int linkTtlHours,boolean retrieval,int maxPerDocument,boolean enrichment,int intervalHours,int minQuality,int minRelevance,boolean requiresApproval){
    if(linkTtlHours<MIN_FEEDBACK_TTL_HOURS||linkTtlHours>MAX_FEEDBACK_TTL_HOURS)throw new IllegalArgumentException("feedback link validity must be between "+MIN_FEEDBACK_TTL_HOURS+" and "+MAX_FEEDBACK_TTL_HOURS+" hours");
    if(intervalHours<MIN_FEEDBACK_TTL_HOURS||intervalHours>MAX_FEEDBACK_TTL_HOURS)throw new IllegalArgumentException("feedback enrichment interval must be between "+MIN_FEEDBACK_TTL_HOURS+" and "+MAX_FEEDBACK_TTL_HOURS+" hours");
    if(maxPerDocument<1||maxPerDocument>MAX_FEEDBACK_PER_DOCUMENT)throw new IllegalArgumentException("feedback per document must be between 1 and "+MAX_FEEDBACK_PER_DOCUMENT);
    if(minQuality<1||minQuality>5||minRelevance<1||minRelevance>5)throw new IllegalArgumentException("feedback thresholds must be between 1 and 5");
    var value=current(crateId);value.updateFeedback(enabled,linkTtlHours,retrieval,maxPerDocument,enrichment,intervalHours,minQuality,minRelevance,requiresApproval);return settings.save(value);
  }
  public static final int MIN_DARTBOARD_OVERSAMPLING=2,MAX_DARTBOARD_OVERSAMPLING=10,DEFAULT_DARTBOARD_OVERSAMPLING=3;
  public static final double MIN_DARTBOARD_SIGMA=0.01,MAX_DARTBOARD_SIGMA=1.0,DEFAULT_DARTBOARD_SIGMA=0.1,MAX_DARTBOARD_WEIGHT=10.0,DEFAULT_DARTBOARD_WEIGHT=1.0;
  /** Dartboard retrieval; takes effect on the next question, no rebuild. */
  @Transactional public RagSettings updateDartboard(java.util.UUID crateId,boolean enabled,int oversampling,double relevanceWeight,double diversityWeight,double sigma){
    if(oversampling<MIN_DARTBOARD_OVERSAMPLING||oversampling>MAX_DARTBOARD_OVERSAMPLING)throw new IllegalArgumentException("dartboard oversampling must be between "+MIN_DARTBOARD_OVERSAMPLING+" and "+MAX_DARTBOARD_OVERSAMPLING);
    if(Double.isNaN(relevanceWeight)||Double.isNaN(diversityWeight)||relevanceWeight<0||diversityWeight<0||relevanceWeight>MAX_DARTBOARD_WEIGHT||diversityWeight>MAX_DARTBOARD_WEIGHT)throw new IllegalArgumentException("dartboard weights must be between 0 and "+(int)MAX_DARTBOARD_WEIGHT);
    if(relevanceWeight==0&&diversityWeight==0)throw new IllegalArgumentException("dartboard relevance and diversity weight must not both be 0");
    if(Double.isNaN(sigma)||sigma<MIN_DARTBOARD_SIGMA||sigma>MAX_DARTBOARD_SIGMA)throw new IllegalArgumentException("dartboard sigma must be between "+MIN_DARTBOARD_SIGMA+" and "+MAX_DARTBOARD_SIGMA);
    var value=current(crateId);value.updateDartboard(enabled,oversampling,relevanceWeight,diversityWeight,sigma);return settings.save(value);
  }
  public static final int MIN_HIERARCHICAL_TOP_K=1,MAX_HIERARCHICAL_TOP_K=10,DEFAULT_HIERARCHICAL_TOP_K=4;
  /** Hierarchical retrieval: switch and number of sections taken from the summary level. Changing the switch needs an index rebuild. */
  @Transactional public RagSettings updateHierarchical(java.util.UUID crateId,boolean enabled,int topK){
    if(topK<MIN_HIERARCHICAL_TOP_K||topK>MAX_HIERARCHICAL_TOP_K)throw new IllegalArgumentException("hierarchical sections must be between "+MIN_HIERARCHICAL_TOP_K+" and "+MAX_HIERARCHICAL_TOP_K);
    var value=current(crateId);value.updateHierarchical(enabled,topK);return settings.save(value);
  }
  @Transactional public RagSettings updateAdaptiveRetrieval(java.util.UUID crateId,boolean enabled){var value=current(crateId);value.updateAdaptiveRetrieval(enabled);return settings.save(value);}
  @Transactional public RagSettings updateHyde(java.util.UUID crateId,boolean enabled){var value=current(crateId);value.updateHyde(enabled);return settings.save(value);}
  @Transactional public RagSettings updateImages(java.util.UUID crateId,boolean indexing,boolean description,java.util.UUID visionModelId,int maxPerRun,int maxBytes,int minSize,String skipPatterns){
    var value=current(crateId);
    if(indexing&&description){
      if(providers!=null&&!providers.allowVisionDescriptions())throw new IllegalArgumentException("Image descriptions are disabled by the administrator");
      if(visionModelId==null&&providers!=null){var a=providers.effectiveAnswer(crateId);if(!a.enabled()||a.baseUrl()==null||a.baseUrl().isBlank()||a.model()==null||a.model().isBlank())throw new IllegalArgumentException("Select a vision model or configure the answer model first");}
    }
    value.updateImages(indexing,description,visionModelId,maxPerRun,maxBytes,minSize,skipPatterns);return settings.save(value);
  }
  public void validateProviders(java.util.UUID crateId){if(providers==null)return;var embedding=providers.effectiveEmbedding(crateId);var answer=providers.effectiveAnswer(crateId);boolean embeddingConfigured=embedding.enabled()&&("local".equals(embedding.provider())||embedding.openaiBaseUrl()!=null&&!embedding.openaiBaseUrl().isBlank()&&embedding.openaiModel()!=null&&!embedding.openaiModel().isBlank());if(!embeddingConfigured)throw new IllegalArgumentException("Proposition retrieval requires configured embeddings");if(!answer.enabled()||answer.baseUrl()==null||answer.baseUrl().isBlank()||answer.model()==null||answer.model().isBlank())throw new IllegalArgumentException("Proposition retrieval requires an enabled and configured answer model");}
}
