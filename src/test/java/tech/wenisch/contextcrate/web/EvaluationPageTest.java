package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.ConcurrentModel;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import tech.wenisch.contextcrate.domain.AppUser;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.evaluation.EvaluationAdminService;
import tech.wenisch.contextcrate.evaluation.EvaluationSettingsService;
import tech.wenisch.contextcrate.repository.AuditLogRepository;
import tech.wenisch.contextcrate.service.CrateService;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.service.SharedModelService;

/** Renders the real template and checks the access rule of the controller. */
class EvaluationPageTest {
  private final UUID crateId = UUID.randomUUID();
  private final CrateAccessService access = mock(CrateAccessService.class);
  private final CrateService crateService = mock(CrateService.class);
  private final EvaluationSettingsService settings = mock(EvaluationSettingsService.class);
  private final EvaluationAdminService admin = mock(EvaluationAdminService.class);
  private final SharedModelService sharedModels = mock(SharedModelService.class);
  private final EvaluationUiController controller = new EvaluationUiController(access, crateService, settings, admin, sharedModels, mock(AuditLogRepository.class));

  private static EvaluationAdminService.Averages averages(long cases, Double v) {
    return new EvaluationAdminService.Averages(cases, v, v, v, v, v, v, v, v, null);
  }

  @Test
  void membersBelowOwnerAreRejectedBeforeAnythingIsLoaded() {
    when(crateService.require(crateId, tech.wenisch.contextcrate.domain.CrateMember.Role.OWNER)).thenThrow(new AccessDeniedException("Insufficient crate access"));

    assertThatThrownBy(() -> controller.crate(crateId, new ConcurrentModel())).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(settings, admin);
  }

  @Test
  void theRealTemplateRendersScoresSettingsAndLoggedQuestions() throws Exception {
    when(access.currentUser()).thenReturn(mock(AppUser.class));
    when(access.require(crateId, tech.wenisch.contextcrate.domain.CrateMember.Role.OWNER)).thenReturn(tech.wenisch.contextcrate.domain.CrateMember.Role.OWNER);
    when(access.activeElevation(crateId)).thenReturn(java.util.Optional.empty());
    when(crateService.require(crateId, tech.wenisch.contextcrate.domain.CrateMember.Role.OWNER)).thenReturn(new Crate(crateId, "Handbook", null, null));
    when(settings.view(crateId)).thenReturn(new EvaluationSettingsService.View(true, 0.5, 5, 20, true, true, true, true, true, 30,
        null, null, "https://judge.example/v1", "judge-1", true, 120, true, true, true, true));
    when(admin.summary(crateId, 7)).thenReturn(new EvaluationAdminService.Summary(7, 1, 0, averages(12, 0.8), averages(0, null),
        List.of(new EvaluationAdminService.Day(LocalDate.of(2026, 11, 1), averages(12, 0.8)))));
    var text = new EvaluationAdminService.TextView("Who is on call?", "The secondary.", new ObjectMapper().readValue("{\"chunks\":[{\"uri\":\"u1\",\"content\":\"alpha\"}]}", java.util.Map.class), new ObjectMapper().readValue("{\"errors\":[]}", java.util.Map.class), "{ \"errors\" : [ ] }");
    when(admin.cases(crateId, 50, true, false)).thenReturn(List.of(new EvaluationAdminService.CaseView(UUID.randomUUID(), Instant.now(), "LIVE", "DONE", 5,
        0.6, 0.5, 1.0, 0.7, 0.9, 0.75, 4, 3, 0.8, 0.6, null, "judge-1", null, "Who covers on-call duty", List.of("operations", "oncall"), false, text)));
    when(admin.ignoreRules(crateId)).thenReturn(List.of(new tech.wenisch.contextcrate.evaluation.EvaluationIgnoreService.RuleView(UUID.randomUUID(),
        "QUESTION", "what is 1+1", false, "owner@example.test", Instant.now(), 12)));
    when(admin.golden(crateId)).thenReturn(List.of(new EvaluationAdminService.GoldenView(UUID.randomUUID(), "How to escalate?", "Page the secondary.", List.of("docs/oncall.md"), Instant.now())));
    when(sharedModels.options(any(), any())).thenReturn(List.of());
    var model = new ConcurrentModel();
    controller.crate(crateId, model);
    controller.page(crateId, 7, false, model);

    var resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setCharacterEncoding("UTF-8");
    var engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);
    var exchange = org.thymeleaf.web.servlet.JakartaServletWebApplication
        .buildApplication(new org.springframework.mock.web.MockServletContext())
        .buildExchange(new org.springframework.mock.web.MockHttpServletRequest(), new org.springframework.mock.web.MockHttpServletResponse());
    var context = new org.thymeleaf.context.WebContext(exchange, java.util.Locale.ENGLISH, model.asMap());
    context.setVariable("_csrf", Map.of("parameterName", "_csrf", "token", "t"));
    context.setVariable("currentPath", "/admin/crates/" + crateId + "/evaluation");
    context.setVariable("applicationVersion", "test");
    String html = engine.process("evaluation", context);

    assertThat(html).contains("RAG evaluation", "Handbook", "0.80", "Precision@K", "NDCG", "Log every question", "judge-1", "How to escalate?", "docs/oncall.md");
    // The owner sees the text of logged questions.
    assertThat(html).contains("Who is on call?", "The secondary.", "alpha");
    // Topic and tags are shown and configurable.
    assertThat(html).contains("Who covers on-call duty", "oncall", "Store request topic", "Store tags", "Open report");
    // Ignore rules and the buttons to create them.
    assertThat(html).contains("Ignore rules", "what is 1+1", "Messages caught", "Show ignored", "Add rule", "Ignore this question", "Ignore this topic", "Click to ignore this tag");
    assertThat(html).doesNotContain("Hide ignored");
    // The stored API key never reaches the page, only the fact that one is stored.
    assertThat(html).contains("Stored").doesNotContain("secret-key");
  }

  private String render(String template, ConcurrentModel model) {
    var resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setCharacterEncoding("UTF-8");
    var engine = new SpringTemplateEngine();
    engine.setTemplateResolver(resolver);
    var exchange = org.thymeleaf.web.servlet.JakartaServletWebApplication
        .buildApplication(new org.springframework.mock.web.MockServletContext())
        .buildExchange(new org.springframework.mock.web.MockHttpServletRequest(), new org.springframework.mock.web.MockHttpServletResponse());
    var context = new org.thymeleaf.context.WebContext(exchange, java.util.Locale.ENGLISH, model.asMap());
    context.setVariable("_csrf", Map.of("parameterName", "_csrf", "token", "t"));
    context.setVariable("currentPath", "/crates/" + crateId + "/evaluation/report");
    context.setVariable("applicationVersion", "test");
    return engine.process(template, context);
  }

  @Test
  void theReportPageShowsTopicsTagsAndTheGlossaryAndHasAPrintableVariant() throws Exception {
    when(access.currentUser()).thenReturn(mock(AppUser.class));
    when(access.require(crateId, tech.wenisch.contextcrate.domain.CrateMember.Role.OWNER)).thenReturn(tech.wenisch.contextcrate.domain.CrateMember.Role.OWNER);
    when(access.activeElevation(crateId)).thenReturn(java.util.Optional.empty());
    when(crateService.require(crateId, tech.wenisch.contextcrate.domain.CrateMember.Role.OWNER)).thenReturn(new Crate(crateId, "Handbook", null, null));
    when(settings.view(crateId)).thenReturn(new EvaluationSettingsService.View(true, 0.5, 5, 20, true, true, true, true, false, 30,
        null, null, "https://judge.example/v1", "judge-1", true, 120, true, true, true, true));
    var now = Instant.now();
    var row = new EvaluationAdminService.CaseView(UUID.randomUUID(), now, "LIVE", "DONE", 5, 0.6, 0.5, 1.0, 0.7, 0.9, 0.75, 4, 3,
        0.8, 0.6, null, "judge-1", null, "Deploying with Docker", List.of("development", "docker"), false, null);
    var gaps = new EvaluationAdminService.GapReport(10, 2, 1, 2, 1,
        List.of(new EvaluationAdminService.GapTopic("Rotating API keys", 3, 2, 0, 1, List.of("Key rotation steps"), List.of("security"), now)),
        List.of(new EvaluationAdminService.GapQuestion(now, "UNANSWERED", "no-sources", "Rotating API keys", List.of("security"), "How do I rotate my key?")), null);
    when(admin.report(eq(crateId), any(), any(), eq(true))).thenReturn(new EvaluationAdminService.Report(now.minusSeconds(86400), now, 12, 0, false,
        averages(12, 0.8), averages(0, null), List.of(new EvaluationAdminService.Day(LocalDate.of(2026, 11, 1), averages(12, 0.8))),
        List.of(new EvaluationAdminService.Ranked("Deploying with Docker", 7, 0.8, 0.7, 0.6, List.of("Docker deployment steps"))),
        List.of(new EvaluationAdminService.Ranked("docker", 7, 0.8, 0.7, 0.6, List.of())), List.of(row), 12, "embedding", null, gaps, null));

    var normal = new ConcurrentModel();
    controller.crate(crateId, normal);
    assertThat(controller.report(crateId, "30d", null, null, false, false, false, false, normal)).isEqualTo("evaluation-report");
    String html = render("evaluation-report", normal);
    assertThat(html).contains("Evaluation report", "Handbook", "Deploying with Docker", "development", "Most requested topics",
        "What the metrics mean", "Faithfulness", "Printable view", "Last 30 days", "sidebar", "also asked as:", "Docker deployment steps", "matched with embeddings", "Generate summary");
    // The dedicated section for questions that could not be answered.
    assertThat(html).contains("Questions the knowledge base could not answer", "Not answered", "Partly answered", "Topics without a good answer",
        "Rotating API keys", "Key rotation steps", "nothing relevant found", "How do I rotate my key?", "Analyze gaps", "30 %");
    // Ignoring from the report, and the optional summary of ignored messages.
    assertThat(html).contains("Show ignored summary", "value=\"Deploying with Docker\"", "value=\"Docker deployment steps\"", "/evaluation/ignore");
    assertThat(html).doesNotContain("Ignored messages");
    assertThat(html).doesNotContain("Print / Save as PDF");

    var print = new ConcurrentModel();
    controller.crate(crateId, print);
    var summary = new EvaluationAdminService.ReportSummary("People mostly ask about Docker.", List.of("Docker: 7x, faithfulness 0.80"), List.of("Passwords: 2x, faithfulness 0.30"),
        List.of("Document password resets"), "judge-1", Instant.now());
    when(admin.summarize(eq(crateId), any())).thenReturn(summary);
    when(admin.summarizeGaps(eq(crateId), any())).thenReturn(new EvaluationAdminService.GapSummary("Key management is undocumented.",
        List.of("API keys: 3 questions"), List.of("No page on rotation"), List.of("Write a key rotation guide"), "judge-1", Instant.now()));
    when(admin.ignoredSummary(eq(crateId), any(), any())).thenReturn(new tech.wenisch.contextcrate.evaluation.EvaluationIgnoreService.Summary(1440,
        List.of(new tech.wenisch.contextcrate.evaluation.EvaluationIgnoreService.RuleCount("QUESTION", "what is 1+1", false, 1440))));
    controller.report(crateId, "custom", "2026-11-01", "2026-11-07", true, true, true, true, print);
    String printable = render("evaluation-report", print);
    assertThat(printable).contains("Print / Save as PDF", "2026-11-01", "2026-11-07", "report-print", "What the metrics mean");
    assertThat(printable).doesNotContain("class=\"sidebar").contains("People mostly ask about Docker.", "Needs attention", "Document password resets", "Key management is undocumented.", "Write a key rotation guide", "Likely causes").doesNotContain("Generate summary").doesNotContain("Analyze gaps");
    // The ignored summary: rule, kind and total only; no ignore buttons in the printable view.
    assertThat(printable).contains("Ignored messages", "what is 1+1", "Question", "1440", "Total").doesNotContain("Show ignored summary").doesNotContain("/evaluation/ignore");
  }
}
