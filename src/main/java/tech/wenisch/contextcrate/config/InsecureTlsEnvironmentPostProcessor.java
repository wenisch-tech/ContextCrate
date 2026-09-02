package tech.wenisch.contextcrate.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import tech.wenisch.contextcrate.util.InsecureSsl;

/**
 * Wires {@code contextcrate.tls.trust-all-certificates} into a process-wide TLS validation
 * bypass. When enabled:
 *
 * <ul>
 *   <li>installs a trust-all {@link javax.net.ssl.SSLContext} as the JVM default, before any
 *       {@code HttpClient} or {@code HttpsURLConnection} is created;
 *   <li>ORs on {@code contextcrate.security.oidc.trust-all-certificates}, so the existing OIDC
 *       trust-relaxed wiring in {@link OidcTrustConfig} activates without a separate flag;
 *   <li>relaxes a {@code jdbc:postgresql:} datasource URL so the JDBC driver stops validating the
 *       server certificate too.
 * </ul>
 *
 * <p>Per-feature flags (git ingestion, web crawler, OIDC) are untouched and keep working
 * independently — this only ORs global trust-all on top of them.
 */
public class InsecureTlsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
  private static final Pattern SSLMODE = Pattern.compile("sslmode=(verify-ca|verify-full)");

  @Override
  public int getOrder() {
    // Must run before OidcTrustAutoConfigurationExcluder (unordered -> LOWEST_PRECEDENCE) so the
    // OIDC property it depends on is already set, but after ConfigDataEnvironmentPostProcessor so
    // application.yml has been loaded.
    return Ordered.LOWEST_PRECEDENCE - 100;
  }

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    boolean trustAllCertificates =
        environment.getProperty("contextcrate.tls.trust-all-certificates", Boolean.class, false);
    if (!trustAllCertificates) return;

    InsecureSsl.installGlobalTrustAll();

    Map<String, Object> overrides = new LinkedHashMap<>();
    overrides.put("contextcrate.security.oidc.trust-all-certificates", "true");

    String datasourceUrl = environment.getProperty("spring.datasource.url");
    if (datasourceUrl != null && datasourceUrl.startsWith("jdbc:postgresql:")) {
      overrides.put("spring.datasource.url", relaxPostgresUrl(datasourceUrl));
    }

    environment.getPropertySources().addFirst(new MapPropertySource("contextcrate-tls-trust-all", overrides));
  }

  private static String relaxPostgresUrl(String url) {
    Matcher matcher = SSLMODE.matcher(url);
    String relaxed = matcher.replaceAll("sslmode=require");
    String separator = relaxed.indexOf('?') >= 0 ? "&" : "?";
    return relaxed + separator + "sslfactory=org.postgresql.ssl.NonValidatingFactory";
  }
}
