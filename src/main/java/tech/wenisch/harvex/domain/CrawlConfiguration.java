package tech.wenisch.harvex.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record CrawlConfiguration(
    @Valid Scope scope,
    @Valid Politeness politeness,
    @Valid Reliability reliability,
    @Valid Output output,
    @Valid LoginConfiguration loginConfiguration) {

  public CrawlConfiguration {
    scope = scope == null ? Scope.defaults() : scope;
    politeness = politeness == null ? Politeness.defaults() : politeness;
    reliability = reliability == null ? Reliability.defaults() : reliability;
    output = output == null ? Output.defaults() : output;
    loginConfiguration = loginConfiguration == null ? LoginConfiguration.defaults() : loginConfiguration;
  }

  public record Scope(
      @NotBlank String seedUrl,
      Set<String> allowedHosts,
      List<String> includePatterns,
      List<String> excludePatterns,
      @Min(0) @Max(1000) int maxDepth,
      @Min(1) int maxPages,
      boolean allowSubdomains,
      boolean discoverSitemaps) {
    public Scope {
      allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
      includePatterns = includePatterns == null ? List.of() : List.copyOf(includePatterns);
      excludePatterns = excludePatterns == null ? List.of() : List.copyOf(excludePatterns);
      maxDepth = maxDepth < 0 ? 0 : maxDepth;
      maxPages = maxPages < 1 ? 1000 : maxPages;
    }

    public static Scope defaults() {
      return new Scope(
          "https://example.com", Set.of("example.com"), List.of(), List.of(), 3, 1000, false, true);
    }
  }

  public record Politeness(
      String userAgent,
      String contact,
      boolean honorRobots,
      @Min(1) int perHostConcurrency,
      @Min(0) long minimumDelayMillis,
      @Min(100) int timeoutMillis) {
    public Politeness {
      userAgent = userAgent == null || userAgent.isBlank() ? "HarvexBot/0.1" : userAgent;
      perHostConcurrency = perHostConcurrency < 1 ? 1 : perHostConcurrency;
      minimumDelayMillis = Math.max(0, minimumDelayMillis);
      timeoutMillis = timeoutMillis < 100 ? 15000 : timeoutMillis;
    }

    public static Politeness defaults() {
      return new Politeness("HarvexBot/0.1", "", true, 1, 1000, 15000);
    }
  }

  public record Reliability(
      @Min(1) @Max(20) int maxAttempts,
      @Min(0) long initialBackoffMillis,
      @Min(1024) long maxBodyBytes,
      boolean deduplicateContent,
      RenderMode renderMode) {
    public Reliability {
      maxAttempts = maxAttempts < 1 ? 3 : maxAttempts;
      initialBackoffMillis = Math.max(0, initialBackoffMillis);
      maxBodyBytes = maxBodyBytes < 1024 ? 10_000_000 : maxBodyBytes;
      renderMode = renderMode == null ? RenderMode.AUTO : renderMode;
    }

    public static Reliability defaults() {
      return new Reliability(3, 1000, 10_000_000, true, RenderMode.AUTO);
    }
  }

  public record Output(
      int rawRetentionDays,
      String contentSelector,
      List<String> removeSelectors,
      @Min(200) int chunkSize,
      @Min(0) int chunkOverlap,
      String logicalIndex) {
    public Output {
      rawRetentionDays = rawRetentionDays < 0 ? 30 : rawRetentionDays;
      contentSelector = contentSelector == null ? "" : contentSelector;
      removeSelectors =
          removeSelectors == null
              ? List.of("script", "style", "nav", "footer", "aside")
              : List.copyOf(removeSelectors);
      chunkSize = chunkSize < 200 ? 2000 : chunkSize;
      chunkOverlap = Math.max(0, Math.min(chunkOverlap, chunkSize / 2));
      logicalIndex = logicalIndex == null || logicalIndex.isBlank() ? "default" : logicalIndex;
    }

    public static Output defaults() {
      return new Output(
          30, "", List.of("script", "style", "nav", "footer", "aside"), 2000, 200, "default");
    }
  }

  public enum AuthMethod {
    NONE,
    FORM,
    OAUTH2
  }

  public record LoginConfiguration(
      String loginPageUrl,
      String username,
      String password,
      String usernameField,
      String passwordField,
      String submitSelector,
      SuccessDetection successDetection,
      boolean directLogin,
      String authServerUrl,
      String clientId,
      String clientSecret,
      String realm,
      AuthMethod authMethod) {

    public LoginConfiguration {
      usernameField =
          usernameField == null || usernameField.isBlank() ? "username" : usernameField;
      passwordField =
          passwordField == null || passwordField.isBlank() ? "password" : passwordField;
      submitSelector =
          submitSelector == null || submitSelector.isBlank()
              ? "button[type='submit']"
              : submitSelector;
      successDetection =
          successDetection == null ? new SuccessDetection(null, null) : successDetection;
      authMethod = authMethod == null ? AuthMethod.NONE : authMethod;
    }

    public static LoginConfiguration defaults() {
      return new LoginConfiguration(
          null,
          null,
          null,
          "username",
          "password",
          "button[type='submit']",
          new SuccessDetection(null, null),
          false,
          null,
          null,
          null,
          null,
          AuthMethod.NONE);
    }

    @JsonIgnore
    public boolean isConfigured() {
      if (authMethod == null || authMethod == AuthMethod.NONE) return false;
      if (authMethod == AuthMethod.OAUTH2) {
        return authServerUrl != null && !authServerUrl.isBlank() &&
               clientId != null && !clientId.isBlank() &&
               clientSecret != null && !clientSecret.isBlank() &&
               realm != null && !realm.isBlank();
      } else {
        return loginPageUrl != null && !loginPageUrl.isBlank() &&
               username != null && !username.isBlank() &&
               password != null && !password.isBlank();
      }
    }

    public LoginConfiguration withoutSecrets() {
      return new LoginConfiguration(
          loginPageUrl,
          username,
          null,
          usernameField,
          passwordField,
          submitSelector,
          successDetection,
          directLogin,
          authServerUrl,
          clientId,
          null,
          realm,
          authMethod);
    }
  }

  public record SuccessDetection(
      String urlPattern,
      String contentPattern) {
  }

  public enum RenderMode {
    HTTP_ONLY,
    BROWSER_ONLY,
    AUTO
  }
}
