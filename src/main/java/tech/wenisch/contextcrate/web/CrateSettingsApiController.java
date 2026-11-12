package tech.wenisch.contextcrate.web;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.service.*;

@RestController
@RequestMapping("/api/v1/crates/{crateId}/settings")
public class CrateSettingsApiController {
  private final CrateAccessService access;private final RagSettingsService rag;
  private final RuntimeProviderSettings providers;private final IndexRebuildService rebuild;private final SharedModelService sharedModels;
  public CrateSettingsApiController(CrateAccessService access,RagSettingsService rag,
      RuntimeProviderSettings providers,IndexRebuildService rebuild,SharedModelService sharedModels){this.sharedModels=sharedModels;
    this.access=access;this.rag=rag;this.providers=providers;this.rebuild=rebuild;
  }
  private SystemPromptService systemPrompts;
  @org.springframework.beans.factory.annotation.Autowired(required=false) void setSystemPrompts(SystemPromptService systemPrompts){this.systemPrompts=systemPrompts;}
  /** The crate's system prompt and whether the administrator allows it (owners). */
  @GetMapping("/system-prompt") public SystemPromptService.View systemPrompt(@PathVariable UUID crateId){return systemPrompts.view(crateId);}
  /** Sets the crate's system prompt; refused while the administrator has not enabled crate system prompts. */
  @PutMapping("/system-prompt") public SystemPromptService.View systemPrompt(@PathVariable UUID crateId,@RequestBody SystemPromptRequest r){return systemPrompts.update(crateId,r.systemPrompt());}
  public record SystemPromptRequest(String systemPrompt){}
  @GetMapping("/rag") public RagSettings rag(@PathVariable UUID crateId){
    access.require(crateId,CrateMember.Role.VIEWER);return rag.current(crateId);
  }
  @PutMapping("/rag") public RagSettings rag(@PathVariable UUID crateId,@RequestBody RagRequest r){
    access.requireMutable(crateId,CrateMember.Role.OWNER);
    var current=rag.current(crateId);
    String oldStrategy=current.getRetrievalStrategy(),oldPolicy=current.getPropositionFailurePolicy();
    String strategy=r.retrievalStrategy()==null?oldStrategy:r.retrievalStrategy(),policy=r.propositionFailurePolicy()==null?oldPolicy:r.propositionFailurePolicy();
    RagSettings updated=rag.update(crateId,r.strictGrounding(),r.allowClientHistory(),r.inlineCitations(),r.structuredSources(),r.gradingEnabled()==null?current.isGradingEnabled():r.gradingEnabled(),r.answerVerificationEnabled()==null?current.isAnswerVerificationEnabled():r.answerVerificationEnabled(),r.answerVerificationFailureAction()==null?current.getAnswerVerificationFailureAction():r.answerVerificationFailureAction(),r.retrievalMode(),strategy,policy,r.sourceLimit());
    if(r.queryTransformationEnabled()!=null||r.queryTransformationStrategies()!=null){
      boolean enabled=r.queryTransformationEnabled()==null?current.isQueryTransformationEnabled():r.queryTransformationEnabled();
      updated=rag.updateQueryTransformation(crateId,enabled,r.queryTransformationStrategies()==null?java.util.List.of(current.getQueryTransformationStrategies()):r.queryTransformationStrategies());
    }
    if(r.fusionVectorWeight()!=null)updated=rag.updateFusion(crateId,r.fusionVectorWeight());
    if(r.hydeEnabled()!=null)updated=rag.updateHyde(crateId,r.hydeEnabled());
    boolean enrichmentChanged=false;
    if(r.enrichmentEnabled()!=null||r.enrichmentQuestions()!=null){
      boolean on=r.enrichmentEnabled()==null?current.isEnrichmentEnabled():r.enrichmentEnabled();
      int questions=r.enrichmentQuestions()==null?current.getEnrichmentQuestions():r.enrichmentQuestions();
      boolean wasOn=current.isEnrichmentEnabled();int wasQuestions=current.getEnrichmentQuestions();
      updated=rag.updateEnrichment(crateId,on,questions);
      enrichmentChanged=on!=wasOn||(on&&questions!=wasQuestions);
    }
    if(r.contextWindow()!=null)updated=rag.updateContextWindow(crateId,r.contextWindow());
    if(r.contextCompression()!=null)updated=rag.updateContextCompression(crateId,r.contextCompression());
    boolean headerChanged=false;
    if(r.contextHeaderVariant()!=null||r.contextHeaderWords()!=null){
      String variant=r.contextHeaderVariant()==null?current.getContextHeaderVariant():r.contextHeaderVariant().strip().toLowerCase(java.util.Locale.ROOT);
      int words=r.contextHeaderWords()==null?current.getContextHeaderWords():r.contextHeaderWords();
      String oldVariant=current.getContextHeaderVariant();int oldWords=current.getContextHeaderWords();
      updated=rag.updateContextHeader(crateId,variant,words);
      headerChanged=!variant.equals(oldVariant)||(tech.wenisch.contextcrate.service.ContextHeaderService.enabled(variant)&&words!=oldWords);
    }
    if(r.imageIndexingEnabled()!=null||r.imageDescriptionEnabled()!=null||r.imageVisionModelId()!=null||r.imageMaxPerRun()!=null||r.imageMaxBytes()!=null||r.imageMinSize()!=null||r.imageSkipPatterns()!=null){
      updated=rag.updateImages(crateId,r.imageIndexingEnabled()==null?current.isImageIndexingEnabled():r.imageIndexingEnabled(),r.imageDescriptionEnabled()==null?current.isImageDescriptionEnabled():r.imageDescriptionEnabled(),r.imageVisionModelId()==null?current.getImageVisionModelId():r.imageVisionModelId(),r.imageMaxPerRun()==null?current.getImageMaxPerRun():r.imageMaxPerRun(),r.imageMaxBytes()==null?current.getImageMaxBytes():r.imageMaxBytes(),r.imageMinSize()==null?current.getImageMinSize():r.imageMinSize(),r.imageSkipPatterns()==null?current.getImageSkipPatterns():r.imageSkipPatterns());
    }
    if(r.sendImagesToModel()!=null)updated=rag.updateSendImages(crateId,r.sendImagesToModel());
    if(r.includeImagesInAnswer()!=null)updated=rag.updateIncludeImages(crateId,r.includeImagesInAnswer());
    if(!strategy.equals(oldStrategy)||!policy.equals(oldPolicy)||headerChanged||enrichmentChanged)rebuild.rebuildAsync(crateId);return updated;
  }
  @GetMapping("/providers") public ProviderView providers(@PathVariable UUID crateId){
    access.requireMutable(crateId,CrateMember.Role.OWNER);
    var e=providers.displayEmbedding(crateId);var r=providers.displayReranking(crateId);var a=providers.displayAnswer(crateId);var sel=providers.selection(crateId);
    return new ProviderView(e.enabled(),e.provider(),e.localModelId(),e.localRevision(),
        e.localDownloadUrl(),e.localCachePath().toString(),e.localModelPath()==null?null:e.localModelPath().toString(),
        e.openaiBaseUrl(),e.openaiModel(),e.openaiDimensions(),e.openaiMaxInputCharacters(),e.automaticLimitRecovery(),e.effectiveOpenaiMaxInputCharacters(),r.enabled(),r.provider(),r.candidateLimit(),r.localModelId(),r.localRevision(),r.localDownloadUrl(),r.localCachePath().toString(),r.localModelPath()==null?null:r.localModelPath().toString(),r.cohereBaseUrl(),r.cohereModel(),r.cohereMaxInputCharacters(),r.cohereTimeoutSeconds(),a.enabled(),a.baseUrl(),a.model(),sel.embedding(),sel.reranking(),sel.answering());
  }
  @GetMapping("/shared-models") public SharedModelOptions sharedModels(@PathVariable UUID crateId){
    access.requireMutable(crateId,CrateMember.Role.OWNER);var sel=providers.selection(crateId);
    return new SharedModelOptions(sharedModels.options(SharedModel.Kind.EMBEDDING,sel.embedding()),sharedModels.options(SharedModel.Kind.RERANKING,sel.reranking()),sharedModels.options(SharedModel.Kind.ANSWER,sel.answering()));
  }
  @PutMapping("/providers") public ProviderView providers(@PathVariable UUID crateId,@RequestBody RuntimeProviderSettings.ProviderForm form){
    access.require(crateId,CrateMember.Role.OWNER);
    var previous=providers.effectiveEmbedding(crateId);var previousAnswer=providers.effectiveAnswer(crateId);providers.update(crateId,form);
    var current=providers.effectiveEmbedding(crateId);
    if(!previous.toString().equals(current.toString())||(("proposition".equals(rag.current(crateId).getRetrievalStrategy())||rag.current(crateId).isEnrichmentEnabled()||tech.wenisch.contextcrate.service.ContextHeaderService.enabled(rag.current(crateId).getContextHeaderVariant()))&&!previousAnswer.toString().equals(providers.effectiveAnswer(crateId).toString())))rebuild.rebuildAsync(crateId);
    return providers(crateId);
  }
  public record RagRequest(boolean strictGrounding,boolean allowClientHistory,boolean inlineCitations,
      boolean structuredSources,Boolean gradingEnabled,Boolean answerVerificationEnabled,String answerVerificationFailureAction,String retrievalMode,String retrievalStrategy,String propositionFailurePolicy,int sourceLimit,Boolean queryTransformationEnabled,java.util.List<String> queryTransformationStrategies,Boolean hydeEnabled,Boolean enrichmentEnabled,Integer enrichmentQuestions,Integer contextWindow,Boolean contextCompression,String contextHeaderVariant,Integer contextHeaderWords,
      Boolean imageIndexingEnabled,Boolean imageDescriptionEnabled,UUID imageVisionModelId,Integer imageMaxPerRun,Integer imageMaxBytes,Integer imageMinSize,String imageSkipPatterns,Double fusionVectorWeight,Boolean sendImagesToModel,Boolean includeImagesInAnswer){}
  public record ProviderView(boolean embeddingsEnabled,String embeddingProvider,String localModelId,
      String localRevision,String localDownloadUrl,String localCachePath,String localModelPath,
      String openaiBaseUrl,String openaiModel,int openaiDimensions,int openaiMaxInputCharacters,boolean embeddingAutomaticLimitRecovery,int embeddingEffectiveMaxInputCharacters,boolean rerankingEnabled,String rerankingProvider,int rerankingCandidateLimit,String rerankingLocalModelId,String rerankingLocalRevision,String rerankingLocalDownloadUrl,String rerankingLocalCachePath,String rerankingLocalModelPath,String rerankingCohereBaseUrl,String rerankingCohereModel,int rerankingCohereMaxInputCharacters,int rerankingCohereTimeoutSeconds,boolean answeringEnabled,
      String answeringBaseUrl,String answeringModel,UUID embeddingSharedModelId,UUID rerankingSharedModelId,UUID answeringSharedModelId){}
  /** Administrator-managed models this crate may select. Only id and name — never endpoints or keys. */
  public record SharedModelOptions(java.util.List<SharedModelService.Option> embedding,java.util.List<SharedModelService.Option> reranking,java.util.List<SharedModelService.Option> answer){}
}
