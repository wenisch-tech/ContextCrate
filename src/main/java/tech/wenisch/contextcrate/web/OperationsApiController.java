package tech.wenisch.contextcrate.web;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.domain.PipelineTypes.*;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.queue.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.service.IndexRebuildService;
import tech.wenisch.contextcrate.answer.AnswerService;
import tech.wenisch.contextcrate.service.CrateAccessService;

@RestController
@RequestMapping("/api/v1/admin")
public class OperationsApiController {
  private final PipelineQueue queue;
  private final SearchIndex index;
  private final ContextCrateProperties properties;
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final AuditLogRepository audits;
  private final IndexRebuildService rebuild;
  private final AnswerService answers;
  private final CrateAccessService access;

  public OperationsApiController(
      PipelineQueue queue,
      SearchIndex index,
      ContextCrateProperties properties,
      NormalizedDocumentRepository documents,
      DocumentChunkRepository chunks,
      AuditLogRepository audits,
      IndexRebuildService rebuild,
      AnswerService answers, CrateAccessService access) {
    this.queue = queue;
    this.index = index;
    this.properties = properties;
    this.documents = documents;
    this.chunks = chunks;
    this.audits = audits;
    this.rebuild = rebuild;
    this.answers = answers;
    this.access = access;
  }

  @GetMapping("/system")
  public Map<String, Object> system() {
    requireAdmin();
    Map<String, Object> depths = new LinkedHashMap<>();
    for (var stage : WorkStage.values()) depths.put(stage.name(), queue.depth(stage));
    return Map.of(
        "profile",
        properties.profile(),
        "role",
        properties.role(),
        "backends",
        Map.of(
            "queue",
            properties.queue().backend(),
            "database",
            properties.database().backend(),
            "artifacts",
            properties.artifacts().backend(),
            "index",
            properties.index().backend()),
        "queues", depths);
  }

  @GetMapping("/queue/dead-letters")
  public List<PipelineMessage> deadLetters() {
    requireAdmin();
    return queue.deadLetters();
  }

  @PostMapping("/queue/dead-letters/{id}/requeue")
  public void requeue(@PathVariable UUID id) {
    requireAdmin();
    queue.requeue(id);
  }

  private void requireAdmin(){if(!access.isAdmin())throw new org.springframework.security.access.AccessDeniedException("Administrator required");}
}
