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
  private final RuntimeProviderSettings providers;private final IndexRebuildService rebuild;
  public CrateSettingsApiController(CrateAccessService access,RagSettingsService rag,
      RuntimeProviderSettings providers,IndexRebuildService rebuild){
    this.access=access;this.rag=rag;this.providers=providers;this.rebuild=rebuild;
  }
  @GetMapping("/rag") public RagSettings rag(@PathVariable UUID crateId){
    access.require(crateId,CrateMember.Role.VIEWER);return rag.current(crateId);
  }
  @PutMapping("/rag") public RagSettings rag(@PathVariable UUID crateId,@RequestBody RagRequest r){
    access.requireMutable(crateId,CrateMember.Role.OWNER);
    return rag.update(crateId,r.strictGrounding(),r.allowClientHistory(),r.inlineCitations(),
        r.structuredSources(),r.retrievalMode(),r.sourceLimit());
  }
  @GetMapping("/providers") public ProviderView providers(@PathVariable UUID crateId){
    access.requireMutable(crateId,CrateMember.Role.OWNER);
    var e=providers.effectiveEmbedding(crateId);var a=providers.effectiveAnswer(crateId);
    return new ProviderView(e.enabled(),e.provider(),e.localModelId(),e.localRevision(),
        e.localDownloadUrl(),e.localCachePath().toString(),e.localModelPath()==null?null:e.localModelPath().toString(),
        e.openaiBaseUrl(),e.openaiModel(),e.openaiDimensions(),e.openaiMaxInputCharacters(),a.enabled(),a.baseUrl(),a.model());
  }
  @PutMapping("/providers") public ProviderView providers(@PathVariable UUID crateId,@RequestBody RuntimeProviderSettings.ProviderForm form){
    access.require(crateId,CrateMember.Role.OWNER);
    var previous=providers.effectiveEmbedding(crateId);providers.update(crateId,form);
    var current=providers.effectiveEmbedding(crateId);
    if(!previous.toString().equals(current.toString()))rebuild.rebuildAsync(crateId);
    return providers(crateId);
  }
  public record RagRequest(boolean strictGrounding,boolean allowClientHistory,boolean inlineCitations,
      boolean structuredSources,String retrievalMode,int sourceLimit){}
  public record ProviderView(boolean embeddingsEnabled,String embeddingProvider,String localModelId,
      String localRevision,String localDownloadUrl,String localCachePath,String localModelPath,
      String openaiBaseUrl,String openaiModel,int openaiDimensions,int openaiMaxInputCharacters,boolean answeringEnabled,
      String answeringBaseUrl,String answeringModel){}
}
