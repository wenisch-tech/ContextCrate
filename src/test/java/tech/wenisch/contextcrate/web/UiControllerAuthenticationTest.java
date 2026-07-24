package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.bind.annotation.GetMapping;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.config.ContextCrateProperties;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.domain.CrawlConfiguration;
import tech.wenisch.contextcrate.embedding.LocalOnnxEmbeddingProvider;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.queue.PipelineQueue;
import tech.wenisch.contextcrate.repository.FetchRecordRepository;
import tech.wenisch.contextcrate.repository.FrontierEntryRepository;
import tech.wenisch.contextcrate.repository.NormalizedDocumentRepository;
import tech.wenisch.contextcrate.service.ConfigurationCodec;
import tech.wenisch.contextcrate.service.CrateAccessService;
import tech.wenisch.contextcrate.service.CrateService;
import tech.wenisch.contextcrate.service.ExtractionService;
import tech.wenisch.contextcrate.service.IndexRebuildService;
import tech.wenisch.contextcrate.service.JobService;

class UiControllerAuthenticationTest {
  private JobService jobs;
  private UiController controller;
  private final UUID crateId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    jobs = mock(JobService.class);
    controller =
        new UiController(
            jobs,
            mock(ConfigurationCodec.class),
            mock(ContextCrateProperties.class),
            mock(PipelineQueue.class),
            mock(SearchIndex.class),
            mock(NormalizedDocumentRepository.class),
            mock(ExtractionService.class),
            mock(RagSettingsService.class),
            mock(RuntimeProviderSettings.class),
            mock(LocalOnnxEmbeddingProvider.class),
            mock(FetchRecordRepository.class),
            mock(FrontierEntryRepository.class),
            mock(CrateService.class),
            mock(CrateAccessService.class),
            mock(IndexRebuildService.class));
  }

  @Test
  void dashboardAcceptsBothCrateUrlForms() throws NoSuchMethodException {
    GetMapping mapping =
        UiController.class
            .getDeclaredMethod("dashboard", UUID.class, String.class, org.springframework.ui.Model.class)
            .getAnnotation(GetMapping.class);

    assertThat(Arrays.asList(mapping.value())).containsExactly("", "/");
  }

  @Test
  void formSubmissionUsesOnlyFormCredentials() {
    create(
        "form-user",
        "form-password",
        "https://wordpress.example.com/protected",
        CrawlConfiguration.AuthMethod.FORM,
        "oauth-user",
        "oauth-password");

    CrawlConfiguration.LoginConfiguration login = capturedConfiguration().loginConfiguration();
    assertThat(login.authMethod()).isEqualTo(CrawlConfiguration.AuthMethod.FORM);
    assertThat(login.username()).isEqualTo("form-user");
    assertThat(login.password()).isEqualTo("form-password");
    assertThat(login.loginPageUrl())
        .isEqualTo("https://wordpress.example.com/protected");
    assertThat(login.submitSelector()).contains("input[type='submit']");
    assertThat(login.authServerUrl()).isNull();
  }

  @Test
  void oauthSubmissionUsesOnlyOauthCredentials() {
    create(
        "form-user",
        "form-password",
        "https://wordpress.example.com/protected",
        CrawlConfiguration.AuthMethod.OAUTH2,
        "oauth-user",
        "oauth-password");

    CrawlConfiguration.LoginConfiguration login = capturedConfiguration().loginConfiguration();
    assertThat(login.authMethod()).isEqualTo(CrawlConfiguration.AuthMethod.OAUTH2);
    assertThat(login.username()).isEqualTo("oauth-user");
    assertThat(login.password()).isEqualTo("oauth-password");
    assertThat(login.loginPageUrl()).isNull();
    assertThat(login.authServerUrl()).isEqualTo("https://keycloak.example.com");
  }

  @Test
  void jobTemplatesUseDistinctFormAndOauthCredentialControls() throws IOException {
    for (String template : new String[] {"job-form.html", "job-edit.html"}) {
      String html;
      try (var input =
          getClass().getResourceAsStream("/templates/" + template)) {
        assertThat(input).isNotNull();
        html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      }
      assertThat(html)
          .contains("name=\"formAuthUsername\"")
          .contains("name=\"formAuthPassword\"")
          .contains("name=\"oauthUsername\"")
          .contains("name=\"oauthPassword\"")
          .doesNotContain("name=\"authUsername\"")
          .doesNotContain("name=\"authPassword\"");
    }
  }

  private void create(
      String formUsername,
      String formPassword,
      String entryUrl,
      CrawlConfiguration.AuthMethod method,
      String oauthUsername,
      String oauthPassword) {
    controller.create(
        crateId,
        "job",
        "https://wordpress.example.com/protected",
        "wordpress.example.com",
        3,
        100,
        false,
        "Test",
        "",
        false,
        0,
        5000,
        3,
        1000,
        10,
        CrawlConfiguration.RenderMode.AUTO,
        30,
        "",
        2000,
        200,
        formUsername,
        formPassword,
        entryUrl,
        false,
        method,
        oauthUsername,
        oauthPassword,
        "https://keycloak.example.com",
        "realm",
        "client",
        "");
  }

  private CrawlConfiguration capturedConfiguration() {
    ArgumentCaptor<CrawlConfiguration> configuration =
        ArgumentCaptor.forClass(CrawlConfiguration.class);
    verify(jobs).create(org.mockito.ArgumentMatchers.eq(crateId), org.mockito.ArgumentMatchers.eq("job"), configuration.capture());
    return configuration.getValue();
  }
}
