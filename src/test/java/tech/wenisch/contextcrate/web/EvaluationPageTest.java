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
        null, null, "https://judge.example/v1", "judge-1", true, 120, true));
    when(admin.summary(crateId, 7)).thenReturn(new EvaluationAdminService.Summary(7, 1, 0, averages(12, 0.8), averages(0, null),
        List.of(new EvaluationAdminService.Day(LocalDate.of(2026, 11, 1), averages(12, 0.8)))));
    var text = new EvaluationAdminService.TextView("Who is on call?", "The secondary.", new ObjectMapper().readTree("{\"chunks\":[{\"uri\":\"u1\",\"content\":\"alpha\"}]}"), new ObjectMapper().readTree("{\"errors\":[]}"));
    when(admin.cases(crateId, 50, true)).thenReturn(List.of(new EvaluationAdminService.CaseView(UUID.randomUUID(), Instant.now(), "LIVE", "DONE", 5,
        0.6, 0.5, 1.0, 0.7, 0.9, 0.75, 4, 3, 0.8, 0.6, null, "judge-1", null, text)));
    when(admin.golden(crateId)).thenReturn(List.of(new EvaluationAdminService.GoldenView(UUID.randomUUID(), "How to escalate?", "Page the secondary.", List.of("docs/oncall.md"), Instant.now())));
    when(sharedModels.options(any(), any())).thenReturn(List.of());
    var model = new ConcurrentModel();
    controller.crate(crateId, model);
    controller.page(crateId, 7, model);

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
    // The stored API key never reaches the page, only the fact that one is stored.
    assertThat(html).contains("Stored").doesNotContain("secret-key");
  }
}
