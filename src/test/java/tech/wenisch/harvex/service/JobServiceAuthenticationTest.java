package tech.wenisch.harvex.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.harvex.crawl.UrlPolicy;
import tech.wenisch.harvex.domain.CrawlConfiguration;
import tech.wenisch.harvex.domain.CrawlJob;
import tech.wenisch.harvex.queue.PipelineQueue;
import tech.wenisch.harvex.repository.*;

class JobServiceAuthenticationTest {
  private final CrawlJobRepository jobs = mock(CrawlJobRepository.class);
  private final CrawlRunRepository runs = mock(CrawlRunRepository.class);
  private final FrontierEntryRepository frontier = mock(FrontierEntryRepository.class);
  private final PipelineQueue queue = mock(PipelineQueue.class);
  private final AuditLogRepository audits = mock(AuditLogRepository.class);
  private final ConfigurationCodec codec = new ConfigurationCodec(new ObjectMapper());
  private JobService service;
  private UUID id;
  private CrawlJob job;

  @BeforeEach
  void setUp() {
    service =
        new JobService(jobs, runs, frontier, queue, codec, new UrlPolicy(true), audits);
    id = UUID.randomUUID();
    job = new CrawlJob(id, "job", codec.write(configuration(form("stored-secret"))));
    job.update("job", job.getConfigurationJson(), false);
    when(jobs.findById(id)).thenReturn(Optional.of(job));
    when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void blankPasswordRetainsStoredSecretAndEnabledStateCanRemainDisabled() {
    service.update(id, "renamed", configuration(form(null)), false);
    CrawlConfiguration saved = codec.read(job.getConfigurationJson());
    assertThat(saved.loginConfiguration().password()).isEqualTo("stored-secret");
    assertThat(job.isEnabled()).isFalse();
  }

  @Test
  void selectingNoneClearsStoredAuthentication() {
    service.update(
        id,
        "renamed",
        configuration(CrawlConfiguration.LoginConfiguration.defaults()),
        false);
    CrawlConfiguration saved = codec.read(job.getConfigurationJson());
    assertThat(saved.loginConfiguration().authMethod())
        .isEqualTo(CrawlConfiguration.AuthMethod.NONE);
    assertThat(saved.loginConfiguration().password()).isNull();
  }

  @Test
  void blankOauthPasswordRetainsPasswordGrantSecretWithoutUsingClientSecret() {
    job.update(
        "job",
        codec.write(configuration(oauthPassword("stored-password"))),
        false);

    service.update(id, "renamed", configuration(oauthPassword(null)), false);

    CrawlConfiguration saved = codec.read(job.getConfigurationJson());
    assertThat(saved.loginConfiguration().username()).isEqualTo("alice");
    assertThat(saved.loginConfiguration().password()).isEqualTo("stored-password");
    assertThat(saved.loginConfiguration().clientSecret()).isNull();
  }

  @Test
  void blankOauthClientSecretRetainsServiceAccountSecret() {
    job.update(
        "job",
        codec.write(configuration(oauthClient("stored-client-secret"))),
        false);

    service.update(id, "renamed", configuration(oauthClient(null)), false);

    CrawlConfiguration saved = codec.read(job.getConfigurationJson());
    assertThat(saved.loginConfiguration().username()).isNull();
    assertThat(saved.loginConfiguration().clientSecret()).isEqualTo("stored-client-secret");
  }

  private static CrawlConfiguration configuration(
      CrawlConfiguration.LoginConfiguration login) {
    return new CrawlConfiguration(null, null, null, null, login);
  }

  private static CrawlConfiguration.LoginConfiguration form(String password) {
    return new CrawlConfiguration.LoginConfiguration(
        "https://example.com/login",
        "alice",
        password,
        "username",
        "password",
        "button[type='submit']",
        null,
        false,
        null,
        null,
        null,
        null,
        CrawlConfiguration.AuthMethod.FORM);
  }

  private static CrawlConfiguration.LoginConfiguration oauthPassword(String password) {
    return new CrawlConfiguration.LoginConfiguration(
        null,
        "alice",
        password,
        "username",
        "password",
        "button[type='submit']",
        null,
        false,
        "https://keycloak.example.com",
        "crawler",
        null,
        "realm",
        CrawlConfiguration.AuthMethod.OAUTH2);
  }

  private static CrawlConfiguration.LoginConfiguration oauthClient(String clientSecret) {
    return new CrawlConfiguration.LoginConfiguration(
        null,
        null,
        null,
        "username",
        "password",
        "button[type='submit']",
        null,
        false,
        "https://keycloak.example.com",
        "crawler",
        clientSecret,
        "realm",
        CrawlConfiguration.AuthMethod.OAUTH2);
  }
}
