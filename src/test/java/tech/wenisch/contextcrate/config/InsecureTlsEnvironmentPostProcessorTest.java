package tech.wenisch.contextcrate.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import tech.wenisch.contextcrate.util.InsecureSsl;

class InsecureTlsEnvironmentPostProcessorTest {
  private final InsecureTlsEnvironmentPostProcessor processor = new InsecureTlsEnvironmentPostProcessor();
  private final SpringApplication application = new SpringApplication();

  @Test
  void leavesEnvironmentUntouchedWhenFlagIsUnset() {
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/contextcrate");

    processor.postProcessEnvironment(environment, application);

    assertThat(environment.getProperty("contextcrate.security.oidc.trust-all-certificates"))
        .isNull();
    assertThat(environment.getProperty("spring.datasource.url"))
        .isEqualTo("jdbc:postgresql://localhost:5432/contextcrate");
  }

  @Test
  void orsOnOidcTrustAllAndRelaxesPostgresUrlWhenFlagIsSet() {
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("contextcrate.tls.trust-all-certificates", "true");
    environment.setProperty(
        "spring.datasource.url",
        "jdbc:postgresql://db.internal:5432/contextcrate?sslmode=verify-full");

    processor.postProcessEnvironment(environment, application);

    assertThat(environment.getProperty("contextcrate.security.oidc.trust-all-certificates"))
        .isEqualTo("true");
    assertThat(environment.getProperty("spring.datasource.url"))
        .isEqualTo(
            "jdbc:postgresql://db.internal:5432/contextcrate?sslmode=require"
                + "&sslfactory=org.postgresql.ssl.NonValidatingFactory");
    assertThat(InsecureSsl.globalTrustAll()).isTrue();
  }

  @Test
  void leavesNonPostgresDatasourceUrlUntouched() {
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("contextcrate.tls.trust-all-certificates", "true");
    environment.setProperty("spring.datasource.url", "jdbc:h2:mem:test");

    processor.postProcessEnvironment(environment, application);

    assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:h2:mem:test");
  }
}
