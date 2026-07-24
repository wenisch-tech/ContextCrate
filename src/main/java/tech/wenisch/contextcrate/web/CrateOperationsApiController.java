package tech.wenisch.contextcrate.web;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.*;

@RestController
@RequestMapping("/api/v1/crates/{crateId}")
public class CrateOperationsApiController {
  private final CrateAccessService access;private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;private final AuditLogRepository audits;
  private final SearchIndex index;private final IndexRebuildService rebuild;
  private final CrateIndexGenerationRepository generations;
  public CrateOperationsApiController(CrateAccessService access,NormalizedDocumentRepository documents,
      DocumentChunkRepository chunks,AuditLogRepository audits,SearchIndex index,
      IndexRebuildService rebuild,CrateIndexGenerationRepository generations){
    this.access=access;this.documents=documents;this.chunks=chunks;this.audits=audits;
    this.index=index;this.rebuild=rebuild;this.generations=generations;
  }
  @GetMapping("/documents") public List<NormalizedDocument> documents(@PathVariable UUID crateId){
    access.require(crateId,CrateMember.Role.VIEWER);
    return documents.findTop100ByCrateIdOrderByCreatedAtDesc(crateId);
  }
  @GetMapping("/documents/{id}/chunks") public List<DocumentChunk> chunks(@PathVariable UUID crateId,@PathVariable UUID id){
    access.require(crateId,CrateMember.Role.VIEWER);
    documents.findByIdAndCrateId(id,crateId).orElseThrow();
    return chunks.findByDocumentIdAndCrateIdOrderByOrdinal(id,crateId);
  }
  @GetMapping("/index") public Map<String,Object> index(@PathVariable UUID crateId){
    access.require(crateId,CrateMember.Role.VIEWER);
    return Map.of("health",index.health(crateId),"generations",generations.findByCrateIdOrderByGenerationDesc(crateId));
  }
  @PostMapping("/index/commit") public void commit(@PathVariable UUID crateId)throws Exception{
    access.requireMutable(crateId,CrateMember.Role.EDITOR);index.commit(crateId);
  }
  @PostMapping("/index/rebuild") @ResponseStatus(HttpStatus.ACCEPTED)
  public void rebuild(@PathVariable UUID crateId){
    access.requireMutable(crateId,CrateMember.Role.EDITOR);rebuild.rebuildAsync(crateId);
  }
  @GetMapping("/audit") public List<AuditLog> audit(@PathVariable UUID crateId){
    access.require(crateId,CrateMember.Role.OWNER);return audits.findTop100ByCrateIdOrderByCreatedAtDesc(crateId);
  }
}
