package tech.wenisch.contextcrate.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.contextcrate.domain.AuditLog;
import tech.wenisch.contextcrate.domain.SharedModel;
import tech.wenisch.contextcrate.evaluation.EvaluationAdminService;
import tech.wenisch.contextcrate.evaluation.EvaluationSettingsService;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.service.CrateService;
import tech.wenisch.contextcrate.service.SharedModelService;

/** The RAG evaluation page of one crate. Only crate owners (and administrators with an active elevation) can open it. */
@Controller
@RequestMapping("/crates/{crateId}/evaluation")
public class EvaluationUiController {
  private final CrateAccessService access;
  private final CrateService crateService;
  private final EvaluationSettingsService settings;
  private final EvaluationAdminService admin;
  private final SharedModelService sharedModels;
  private final AuditLogRepository audits;

  public EvaluationUiController(CrateAccessService access, CrateService crateService, EvaluationSettingsService settings,
      EvaluationAdminService admin, SharedModelService sharedModels, AuditLogRepository audits) {
    this.access = access; this.crateService = crateService; this.settings = settings; this.admin = admin;
    this.sharedModels = sharedModels; this.audits = audits;
  }

  @ModelAttribute
  void crate(@PathVariable UUID crateId, Model model) {
    model.addAttribute("crate", crateService.require(crateId, CrateMember.Role.OWNER));
    model.addAttribute("crateRole", access.require(crateId, CrateMember.Role.OWNER));
    model.addAttribute("crates", crateService.accessible());
    model.addAttribute("adminElevation", access.activeElevation(crateId).orElse(null));
    model.addAttribute("isAdmin", access.isAdmin());
    model.addAttribute("currentUser", access.currentUser());
  }

  private void mutable(UUID crateId) { access.requireMutable(crateId, CrateMember.Role.OWNER); }

  private String actor() { return access.currentUser().getEmail(); }

  @GetMapping
  String page(@PathVariable UUID crateId, @RequestParam(defaultValue = "7") int days,
      @RequestParam(defaultValue = "false") boolean showIgnored, Model model) {
    var view = settings.view(crateId);
    model.addAttribute("settings", view);
    model.addAttribute("summary", admin.summary(crateId, days));
    model.addAttribute("cases", admin.cases(crateId, 50, true, showIgnored));
    model.addAttribute("showIgnored", showIgnored);
    model.addAttribute("ignoreRules", admin.ignoreRules(crateId));
    model.addAttribute("golden", admin.golden(crateId));
    model.addAttribute("judgeModels", sharedModels.options(SharedModel.Kind.JUDGE, view.judgeSharedModelId()));
    model.addAttribute("days", days);
    return "evaluation";
  }

  /**
   * The report page. {@code range} is a preset (7d, 30d, 90d) or {@code custom} with {@code from}/{@code to}
   * (ISO dates, both inclusive, UTC). {@code print=true} renders the same page without navigation, for "save as PDF".
   */
  @GetMapping("/report")
  String report(@PathVariable UUID crateId, @RequestParam(defaultValue = "7d") String range,
      @RequestParam(required = false) String from, @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "false") boolean print, @RequestParam(defaultValue = "false") boolean summary,
      @RequestParam(defaultValue = "false") boolean gaps, @RequestParam(defaultValue = "false") boolean ignored, Model model) {
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
    java.time.LocalDate start, end = today;
    String selected = range;
    switch (range) {
      case "30d" -> start = today.minusDays(29);
      case "90d" -> start = today.minusDays(89);
      case "custom" -> {
        try {
          start = java.time.LocalDate.parse(from);
          end = java.time.LocalDate.parse(to);
        } catch (RuntimeException e) {
          start = today.minusDays(6); end = today; selected = "7d";
        }
        if (end.isBefore(start)) { var swap = start; start = end; end = swap; }
        if (java.time.temporal.ChronoUnit.DAYS.between(start, end) > 365) start = end.minusDays(365);
      }
      default -> { start = today.minusDays(6); selected = "7d"; }
    }
    var instantFrom = start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    var instantTo = end.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    model.addAttribute("settings", settings.view(crateId));
    var report = admin.report(crateId, instantFrom, instantTo, true);
    if (summary) {
      try {
        report = report.withSummary(admin.summarize(crateId, report));
      } catch (IllegalStateException e) {
        model.addAttribute("summaryError", e.getMessage());
      } catch (Exception e) {
        model.addAttribute("summaryError", "The summary could not be generated. Check the judge model and try again.");
      }
    }
    if (gaps) {
      try {
        report = report.withGapSummary(admin.summarizeGaps(crateId, report));
      } catch (IllegalStateException e) {
        model.addAttribute("gapError", e.getMessage());
      } catch (Exception e) {
        model.addAttribute("gapError", "The analysis could not be generated. Check the judge model and try again.");
      }
    }
    if (ignored) report = report.withIgnored(admin.ignoredSummary(crateId, instantFrom, instantTo));
    model.addAttribute("report", report);
    model.addAttribute("summaryRequested", summary);
    model.addAttribute("range", selected);
    model.addAttribute("from", start);
    model.addAttribute("to", end);
    model.addAttribute("print", print);
    model.addAttribute("generatedAt", java.time.Instant.now());
    return "evaluation-report";
  }

  /**
   * Adds ignore rules: one per {@code pattern} (a topic cluster sends all its wordings). {@code back} is the page to
   * return to; only pages of this crate's evaluation area are accepted.
   */
  @PostMapping("/ignore")
  String ignore(@PathVariable UUID crateId, RedirectAttributes redirect, @RequestParam String kind,
      @RequestParam java.util.List<String> pattern, @RequestParam(defaultValue = "false") boolean containsMatch,
      @RequestParam(defaultValue = "") String back) {
    mutable(crateId);
    try {
      admin.addIgnoreRules(crateId, actor(), kind, pattern, containsMatch);
      redirect.addFlashAttribute("ignoreNotice", "Ignore rule added. Matching messages are hidden and no longer counted.");
    } catch (IllegalArgumentException e) {
      redirect.addFlashAttribute("evaluationError", e.getMessage());
    }
    return "redirect:" + backTarget(crateId, back);
  }

  @PostMapping("/ignore/{id}/delete")
  String deleteIgnore(@PathVariable UUID crateId, @PathVariable UUID id, RedirectAttributes redirect, @RequestParam(defaultValue = "") String back) {
    mutable(crateId);
    admin.deleteIgnoreRule(crateId, actor(), id);
    redirect.addFlashAttribute("ignoreNotice", "Ignore rule removed. The messages it caught count again.");
    return "redirect:" + backTarget(crateId, back);
  }

  /** Only a path inside this crate's evaluation area is a valid return target; anything else goes to the evaluation page. */
  static String backTarget(UUID crateId, String back) {
    String base = "/crates/" + crateId + "/evaluation";
    boolean valid = back != null && back.startsWith(base) && !back.contains("//") && !back.contains("\\") && !back.contains("\n") && !back.contains("\r")
        && (back.length() == base.length() || "/?#".indexOf(back.charAt(base.length())) >= 0);
    return valid ? back : base + "#ignore";
  }

  @PostMapping("/settings")
  String save(@PathVariable UUID crateId, RedirectAttributes redirect,
      @RequestParam(defaultValue = "false") boolean enabled, @RequestParam(defaultValue = "1.0") double samplingRate,
      @RequestParam(defaultValue = "5") int topK, @RequestParam(defaultValue = "20") int poolSize,
      @RequestParam(defaultValue = "false") boolean context, @RequestParam(defaultValue = "false") boolean faithfulness,
      @RequestParam(defaultValue = "false") boolean answerRelevance, @RequestParam(defaultValue = "false") boolean completeness,
      @RequestParam(defaultValue = "false") boolean logQuestions, @RequestParam(defaultValue = "30") int retentionDays,
      @RequestParam(defaultValue = "false") boolean storeTopic, @RequestParam(defaultValue = "false") boolean storeTags,
      @RequestParam(defaultValue = "false") boolean trackUnanswered,
      @RequestParam(defaultValue = "") String judgeSharedModelId, @RequestParam(defaultValue = "") String judgeBaseUrl,
      @RequestParam(defaultValue = "") String judgeModel, @RequestParam(defaultValue = "") String judgeApiKey,
      @RequestParam(defaultValue = "120") int judgeTimeoutSeconds) {
    return attempt(crateId, redirect, "saved", () -> {
      UUID shared = judgeSharedModelId.isBlank() ? null : UUID.fromString(judgeSharedModelId);
      var view = settings.apply(crateId, new EvaluationSettingsService.Request(enabled, samplingRate, topK, poolSize, context,
          faithfulness, answerRelevance, completeness, logQuestions, retentionDays, shared, shared == null ? judgeBaseUrl : null,
          shared == null ? judgeModel : null, judgeApiKey, judgeTimeoutSeconds, storeTopic, storeTags, trackUnanswered));
      audits.save(new AuditLog(crateId, actor(), "EVALUATION_SETTINGS_CHANGED", "evaluation",
          "enabled=" + view.enabled() + ", sampling=" + view.samplingRate() + ", logQuestions=" + view.logQuestions()
              + ", storeTopic=" + view.storeTopic() + ", storeTags=" + view.storeTags() + ", trackUnanswered=" + view.trackUnanswered()));
    });
  }

  @PostMapping("/golden")
  String addGolden(@PathVariable UUID crateId, RedirectAttributes redirect, @RequestParam String question,
      @RequestParam(defaultValue = "") String referenceAnswer, @RequestParam(defaultValue = "") String relevantUris) {
    List<String> uris = new ArrayList<>(Arrays.asList(relevantUris.split("\\R")));
    return attempt(crateId, redirect, "goldenSaved", () -> admin.addGolden(crateId, actor(), question, referenceAnswer, uris));
  }

  @PostMapping("/golden/import")
  String importGolden(@PathVariable UUID crateId, RedirectAttributes redirect, @RequestParam String json) {
    return attempt(crateId, redirect, "goldenSaved", () -> admin.importGolden(crateId, actor(), json));
  }

  @PostMapping("/golden/{id}/delete")
  String deleteGolden(@PathVariable UUID crateId, @PathVariable UUID id) {
    mutable(crateId);
    admin.deleteGolden(crateId, actor(), id);
    return "redirect:/crates/" + crateId + "/evaluation?goldenSaved#golden";
  }

  @PostMapping("/golden/run")
  String run(@PathVariable UUID crateId, RedirectAttributes redirect) {
    return attempt(crateId, redirect, "goldenRun", () -> admin.runGolden(crateId, actor()));
  }

  private interface Action { void run() throws Exception; }

  private String attempt(UUID crateId, RedirectAttributes redirect, String flag, Action action) {
    mutable(crateId);
    String base = "redirect:/crates/" + crateId + "/evaluation";
    try {
      action.run();
      return base + "?" + flag;
    } catch (IllegalArgumentException | IllegalStateException e) {
      redirect.addFlashAttribute("evaluationError", e.getMessage());
      return base;
    } catch (Exception e) {
      redirect.addFlashAttribute("evaluationError", "The action failed");
      return base;
    }
  }
}
