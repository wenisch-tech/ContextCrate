package tech.wenisch.contextcrate.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Boot's own OAuth2 client autoconfiguration eagerly resolves the OIDC issuer at startup with the
 * default (JVM-trusted) HTTP client, regardless of the {@code ClientRegistrationRepository} bean
 * supplied by {@link OidcTrustConfig}. When trust-all-certificates is requested, disable it so
 * only ContextCrate's own trust-relaxed wiring runs.
 */
public class OidcTrustAutoConfigurationExcluder implements EnvironmentPostProcessor {
  private static final String EXCLUDES =
      "org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration,"
          + "org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    boolean oidcEnabled = environment.getProperty(
        "contextcrate.security.oidc.enabled", Boolean.class, false);
    boolean trustAllCertificates = environment.getProperty(
        "contextcrate.security.oidc.trust-all-certificates", Boolean.class, false);
    if (!oidcEnabled || !trustAllCertificates) return;

    String existing = environment.getProperty("spring.autoconfigure.exclude");
    String combined = existing == null || existing.isBlank() ? EXCLUDES : existing + "," + EXCLUDES;
    Map<String, Object> overrides = new LinkedHashMap<>();
    overrides.put("spring.autoconfigure.exclude", combined);
    environment.getPropertySources().addFirst(new MapPropertySource("contextcrate-oidc-trust", overrides));
  }
}
