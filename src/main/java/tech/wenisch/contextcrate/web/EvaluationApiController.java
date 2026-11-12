package tech.wenisch.contextcrate.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.contextcrate.domain.AuditLog;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.evaluation.EvaluationAdminService;
import tech.wenisch.contextcrate.evaluation.EvaluationIgnoreService;
import tech.wenisch.contextcrate.evaluation.EvaluationSettingsService;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.repository.CrateRepository;
import tech.wenisch.contextcrate.service.CrateAccessService;

/**
 * RAG evaluation for one crate. Every endpoint requires the crate Owner (or an administrator with an
 * active elevation); members below Owner and Viewer/Editor service keys cannot read or change it.
 */
@RestController
@RequestMapping("/api/v1/crates/{crateId}/evaluation")
public class EvaluationApiController {
  private final CrateAccessService access;
  private final CrateRepository crates;
  private final EvaluationSettingsService settings;
  private final EvaluationAdminService admin;
  private final AuditLogRepository audits;

  public EvaluationApiController(CrateAccessService access, CrateRepository crates, EvaluationSettingsService settings,
      EvaluationAdminService admin, AuditLogRepository audits) {
    this.access = access; this.crates = crates; this.settings = settings; this.admin = admin; this.audits = audits;
  }

  private void requireOwner(UUID crateId) {
    access.require(crateId, CrateMember.Role.OWNER);
  }

  private void requireMutable(UUID crateId) {
    access.requireMutable(crateId, CrateMember.Role.OWNER);
  }

  private String actor() { return access.currentUser().getEmail(); }

  @GetMapping("/settings")
  public EvaluationSettingsService.View settings(@PathVariable UUID crateId) {
    requireOwner(crateId);
    return settings.view(crateId);
  }

  @PutMapping("/settings")
  public EvaluationSettingsService.View update(@PathVariable UUID crateId, @RequestBody EvaluationSettingsService.Request request) {
    requireMutable(crateId);
    var view = settings.apply(crateId, request);
    // The audit entry names what is switched on, never an endpoint secret.
    audits.save(new AuditLog(crateId, actor(), "EVALUATION_SETTINGS_CHANGED", "evaluation",
        "enabled=" + view.enabled() + ", sampling=" + view.samplingRate() + ", logQuestions=" + view.logQuestions()));
    return view;
  }

  @GetMapping("/summary")
  public EvaluationAdminService.Summary summary(@PathVariable UUID crateId, @RequestParam(defaultValue = "7") int days) {
    requireOwner(crateId);
    return admin.summary(crateId, days);
  }

  /** Report over the last {@code days} days (UTC, including today): averages, trend, top topics and tags, latest questions. */
  @GetMapping("/report")
  public EvaluationAdminService.Report report(@PathVariable UUID crateId, @RequestParam(defaultValue = "7") int days,
      @RequestParam(defaultValue = "false") boolean summary,
      @RequestParam(defaultValue = "false") boolean gaps, @RequestParam(defaultValue = "false") boolean ignored) throws Exception {
    requireOwner(crateId);
    var today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
    int window = Math.max(1, Math.min(days, 365));
    var report = admin.report(crateId, today.minusDays(window - 1L).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
        today.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), true);
    if (summary) report = report.withSummary(admin.summarize(crateId, report));
    if (gaps) report = report.withGapSummary(admin.summarizeGaps(crateId, report));
    if (ignored) report = report.withIgnored(admin.ignoredSummary(crateId,
        today.minusDays(window - 1L).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), today.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
    return report;
  }

  @GetMapping("/ignore-rules")
  public List<EvaluationIgnoreService.RuleView> ignoreRules(@PathVariable UUID crateId) {
    requireOwner(crateId);
    return admin.ignoreRules(crateId);
  }

  public record IgnoreRequest(String kind, List<String> patterns, String pattern, Boolean containsMatch) {}

  /** Adds rules for {@code pattern} or each of {@code patterns}; kind is question, topic or tag. */
  @PostMapping("/ignore-rules")
  @ResponseStatus(HttpStatus.CREATED)
  public List<EvaluationIgnoreService.RuleView> addIgnoreRule(@PathVariable UUID crateId, @RequestBody IgnoreRequest request) {
    requireMutable(crateId);
    List<String> patterns = request.patterns() != null ? request.patterns() : request.pattern() == null ? List.of() : List.of(request.pattern());
    admin.addIgnoreRules(crateId, actor(), request.kind(), patterns, Boolean.TRUE.equals(request.containsMatch()));
    return admin.ignoreRules(crateId);
  }

  @DeleteMapping("/ignore-rules/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteIgnoreRule(@PathVariable UUID crateId, @PathVariable UUID id) {
    requireMutable(crateId);
    admin.deleteIgnoreRule(crateId, actor(), id);
  }

  @GetMapping("/cases")
  public List<EvaluationAdminService.CaseView> cases(@PathVariable UUID crateId, @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "false") boolean showIgnored) {
    requireOwner(crateId);
    return admin.cases(crateId, limit, true, showIgnored);
  }

  @GetMapping("/golden")
  public List<EvaluationAdminService.GoldenView> golden(@PathVariable UUID crateId) {
    requireOwner(crateId);
    return admin.golden(crateId);
  }

  public record GoldenRequest(String question, String referenceAnswer, List<String> relevantUris) {}

  @PostMapping("/golden")
  @ResponseStatus(HttpStatus.CREATED)
  public EvaluationAdminService.GoldenView addGolden(@PathVariable UUID crateId, @RequestBody GoldenRequest request) {
    requireMutable(crateId);
    return admin.addGolden(crateId, actor(), request.question(), request.referenceAnswer(), request.relevantUris());
  }

  /** Body: a JSON array of {question, referenceAnswer, relevantUris}. */
  @PostMapping("/golden/import")
  public Map<String, Integer> importGolden(@PathVariable UUID crateId, @RequestBody String json) {
    requireMutable(crateId);
    return Map.of("imported", admin.importGolden(crateId, actor(), json));
  }

  @DeleteMapping("/golden/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteGolden(@PathVariable UUID crateId, @PathVariable UUID id) {
    requireMutable(crateId);
    admin.deleteGolden(crateId, actor(), id);
  }

  @PostMapping("/golden/run")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Integer> run(@PathVariable UUID crateId) throws Exception {
    requireMutable(crateId);
    return Map.of("queued", admin.runGolden(crateId, actor()));
  }
}
