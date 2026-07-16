package tech.wenisch.harvex.web;

import static tech.wenisch.harvex.domain.PipelineTypes.ExtractionType;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.harvex.domain.*;
import tech.wenisch.harvex.service.*;

@RestController
@RequestMapping("/api/v1")
public class ExtractionApiController {
  private final ExtractionService service;

  public ExtractionApiController(ExtractionService service) {
    this.service = service;
  }

  @GetMapping("/extraction-rules")
  public List<RuleResponse> rules() {
    return service.rules().stream().map(this::rule).toList();
  }

  @PostMapping("/extraction-rules")
  @ResponseStatus(HttpStatus.CREATED)
  public RuleResponse createRule(@RequestBody RuleRequest request) {
    return rule(
        service.createRule(request.name(), request.type(), request.pattern(), request.enabled()));
  }

  @PutMapping("/extraction-rules/{id}")
  public RuleResponse updateRule(@PathVariable UUID id, @RequestBody RuleRequest request) {
    return rule(
        service.updateRule(id, request.name(), request.type(), request.pattern(), request.enabled()));
  }

  @DeleteMapping("/extraction-rules/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteRule(@PathVariable UUID id) {
    service.deleteRule(id);
  }

  @PostMapping("/extraction-rules/test")
  public List<MatchResponse> test(@RequestBody TestRuleRequest request) {
    return service.test(request.type(), request.pattern(), request.text()).stream()
        .map(m -> new MatchResponse(m.value(), m.start(), m.end()))
        .toList();
  }

  @GetMapping("/extraction-rules/{ruleId}/results")
  public Page<ResultResponse> ruleResults(
      @PathVariable UUID ruleId,
      @RequestParam(required = false) UUID runId,
      @RequestParam(required = false) UUID documentId,
      @RequestParam(required = false) UUID chunkId,
      @RequestParam(required = false) String value,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    int pageSize = Math.max(1, Math.min(size, 200));
    Pageable pageable = PageRequest.of(Math.max(0, page), pageSize);
    return service.search(runId, documentId, chunkId, ruleId, value, pageable).map(this::result);
  }

  @GetMapping("/extraction-results")
  public Page<ResultResponse> results(
      @RequestParam(required = false) UUID runId,
      @RequestParam(required = false) UUID documentId,
      @RequestParam(required = false) UUID chunkId,
      @RequestParam(required = false) UUID ruleId,
      @RequestParam(required = false) String value,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    int pageSize = Math.max(1, Math.min(size, 200));
    Pageable pageable = PageRequest.of(Math.max(0, page), pageSize);
    return service.search(runId, documentId, chunkId, ruleId, value, pageable).map(this::result);
  }

  @PostMapping("/runs/{runId}/extractions/rebuild")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void rebuildRun(@PathVariable UUID runId) {
    service.rebuildRun(runId);
  }

  @PostMapping("/documents/{documentId}/extractions/rebuild")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void rebuildDocument(@PathVariable UUID documentId) {
    service.rebuildDocument(documentId);
  }

  public record RuleRequest(String name, ExtractionType type, String pattern, boolean enabled) {}

  public record TestRuleRequest(ExtractionType type, String pattern, String text) {}

  public record MatchResponse(String value, int start, int end) {}

  public record RuleResponse(
      UUID id,
      String name,
      ExtractionType type,
      String pattern,
      boolean enabled,
      long resultCount,
      Instant createdAt,
      Instant updatedAt) {}

  public record ResultResponse(
      UUID id,
      UUID ruleId,
      UUID runId,
      UUID documentId,
      UUID chunkId,
      int chunkOrdinal,
      String matchedValue,
      int matchStart,
      int matchEnd,
      String contextBefore,
      String contextAfter,
      Instant extractedAt) {}

  private RuleResponse rule(ExtractionRule rule) {
    return new RuleResponse(
        rule.getId(),
        rule.getName(),
        rule.getType(),
        rule.getPattern(),
        rule.isEnabled(),
        service.countResults(rule.getId()),
        rule.getCreatedAt(),
        rule.getUpdatedAt());
  }

  private ResultResponse result(ExtractionResult result) {
    return new ResultResponse(
        result.getId(),
        result.getRuleId(),
        result.getRunId(),
        result.getDocumentId(),
        result.getChunkId(),
        result.getChunkOrdinal(),
        result.getMatchedValue(),
        result.getMatchStart(),
        result.getMatchEnd(),
        result.getContextBefore(),
        result.getContextAfter(),
        result.getExtractedAt());
  }
}