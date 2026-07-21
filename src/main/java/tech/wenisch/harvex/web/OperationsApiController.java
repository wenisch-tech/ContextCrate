package tech.wenisch.harvex.web;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.harvex.config.HarvexProperties;
import tech.wenisch.harvex.domain.PipelineTypes.*;
import tech.wenisch.harvex.index.SearchIndex;
import tech.wenisch.harvex.queue.*;
import tech.wenisch.harvex.repository.*;
import tech.wenisch.harvex.service.IndexRebuildService;
import tech.wenisch.harvex.answer.AnswerService;

@RestController
@RequestMapping("/api/v1")
public class OperationsApiController {
  private final PipelineQueue queue;
  private final SearchIndex index;
  private final HarvexProperties properties;
  private final NormalizedDocumentRepository documents;
  private final DocumentChunkRepository chunks;
  private final AuditLogRepository audits;
  private final IndexRebuildService rebuild;
  private final AnswerService answers;

  public OperationsApiController(
      PipelineQueue queue,
      SearchIndex index,
      HarvexProperties properties,
      NormalizedDocumentRepository documents,
      DocumentChunkRepository chunks,
      AuditLogRepository audits,
      IndexRebuildService rebuild,
      AnswerService answers) {
    this.queue = queue;
    this.index = index;
    this.properties = properties;
    this.documents = documents;
    this.chunks = chunks;
    this.audits = audits;
    this.rebuild = rebuild;
    this.answers = answers;
  }

  @GetMapping("/system")
  public Map<String, Object> system() {
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
        "queues",
        depths,
        "indexHealth", index.health(),
        "answeringAvailable", answers.available());
  }

  @GetMapping("/queue/dead-letters")
  public List<PipelineMessage> deadLetters() {
    return queue.deadLetters();
  }

  @PostMapping("/queue/dead-letters/{id}/requeue")
  public void requeue(@PathVariable UUID id) {
    queue.requeue(id);
  }

  @GetMapping("/documents")
  public List<tech.wenisch.harvex.domain.NormalizedDocument> documents() {
    return documents.findTop100ByOrderByCreatedAtDesc();
  }

  @GetMapping("/documents/{id}/chunks")
  public List<tech.wenisch.harvex.domain.DocumentChunk> chunks(@PathVariable UUID id) {
    return chunks.findByDocumentIdOrderByOrdinal(id);
  }

  @PostMapping("/index/commit")
  public void commit() throws Exception {
    index.commit();
  }

  @PostMapping("/index/rebuild")
  public Map<String, Long> rebuild() throws Exception {
    return Map.of("documents", rebuild.rebuild());
  }

  @GetMapping("/audit")
  public List<tech.wenisch.harvex.domain.AuditLog> audit() {
    return audits.findTop100ByOrderByCreatedAtDesc();
  }
}
