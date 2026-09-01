package tech.wenisch.contextcrate.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the standard {@code spring.security.oauth2.client.*} tree ourselves. Boot's own
 * {@code OAuth2ClientProperties} bean is only registered under conditions we do not control here,
 * so {@link OidcTrustConfig} reads the same properties directly instead of depending on it.
 */
@ConfigurationProperties(prefix = "spring.security.oauth2.client")
public class ContextCrateOAuth2ClientProperties {
  private Map<String, Registration> registration = new LinkedHashMap<>();
  private Map<String, Provider> provider = new LinkedHashMap<>();

  public Map<String, Registration> getRegistration() {
    return registration;
  }

  public void setRegistration(Map<String, Registration> registration) {
    this.registration = registration;
  }

  public Map<String, Provider> getProvider() {
    return provider;
  }

  public void setProvider(Map<String, Provider> provider) {
    this.provider = provider;
  }

  public static class Registration {
    private String provider;
    private String clientId;
    private String clientSecret;
    private String clientName;
    private Set<String> scope;

    public String getProvider() {
      return provider;
    }

    public void setProvider(String provider) {
      this.provider = provider;
    }

    public String getClientId() {
      return clientId;
    }

    public void setClientId(String clientId) {
      this.clientId = clientId;
    }

    public String getClientSecret() {
      return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
      this.clientSecret = clientSecret;
    }

    public String getClientName() {
      return clientName;
    }

    public void setClientName(String clientName) {
      this.clientName = clientName;
    }

    public Set<String> getScope() {
      return scope;
    }

    public void setScope(Set<String> scope) {
      this.scope = scope;
    }
  }

  public static class Provider {
    private String issuerUri;

    public String getIssuerUri() {
      return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
      this.issuerUri = issuerUri;
    }
  }
}
