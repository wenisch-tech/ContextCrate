package tech.wenisch.contextcrate.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.crawl.UrlPolicy;
import tech.wenisch.contextcrate.domain.CrawlConfiguration.LoginConfiguration;

/** Obtains Keycloak tokens using OAuth2 client-credentials or password grants. */
@Service
public class KeycloakTokenService {
  private final HttpClient client;
  private final ObjectMapper mapper;
  private final UrlPolicy urls;

  @Autowired
  public KeycloakTokenService(ObjectMapper mapper, UrlPolicy urls) {
    this(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
        mapper,
        urls);
  }

  KeycloakTokenService(HttpClient client, ObjectMapper mapper, UrlPolicy urls) {
    this.client = client;
    this.mapper = mapper;
    this.urls = urls;
  }

  public AccessToken request(LoginConfiguration config) throws Exception {
    validate(config);
    String base = config.authServerUrl().replaceAll("/+$", "");
    String realm = encodePathSegment(config.realm());
    String tokenUrl = base + "/realms/" + realm + "/protocol/openid-connect/token";
    urls.assertSafe(tokenUrl);

    String body;
    if (config.username() != null && !config.username().isBlank() &&
        config.password() != null && !config.password().isBlank()) {
      // Use password grant for direct access
      body = "client_id=" + form(config.clientId())
            + "&username=" + form(config.username())
            + "&password=" + form(config.password())
            + "&grant_type=password";
    } else {
      // Use client credentials grant for service accounts
      body = "client_id=" + form(config.clientId())
            + "&client_secret=" + form(config.clientSecret())
            + "&grant_type=client_credentials";
    }

    HttpRequest request =
        HttpRequest.newBuilder(URI.create(tokenUrl))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response =
        client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new AuthenticationException(
          "OAuth2 token request failed with HTTP " + response.statusCode());
    }

    JsonNode json;
    try {
      json = mapper.readTree(response.body());
    } catch (Exception e) {
      throw new AuthenticationException("OAuth2 token response was not valid JSON", e);
    }
    String value = json.path("access_token").asText("");
    long expiresIn = json.path("expires_in").asLong(60);
    if (value.isBlank()) {
      throw new AuthenticationException("OAuth2 token response did not contain an access token");
    }
    return new AccessToken(value, Instant.now().plusSeconds(Math.max(1, expiresIn)));
  }

  private static void validate(LoginConfiguration config) {
    if (config == null || blank(config.authServerUrl()) || blank(config.realm()) || blank(config.clientId())) {
      throw new IllegalArgumentException("OAuth2 configuration is incomplete");
    }

    // For password grant, username and password are required
    if (config.username() != null && !config.username().isBlank() &&
        (config.password() == null || config.password().isBlank())) {
      throw new IllegalArgumentException("OAuth2 password grant requires both username and password");
    }

    // For client credentials grant, client secret is required
    if ((config.username() == null || config.username().isBlank()) &&
        (config.clientSecret() == null || config.clientSecret().isBlank())) {
      throw new IllegalArgumentException("OAuth2 client credentials grant requires client secret");
    }
  }

  private static String form(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String encodePathSegment(String value) {
    return form(value).replace("+", "%20");
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public record AccessToken(String value, Instant expiresAt) {
    public boolean usableAt(Instant instant) {
      long remaining = Math.max(0, Duration.between(instant, expiresAt).toSeconds());
      long skew = Math.min(30, Math.max(1, remaining / 10));
      return instant.plusSeconds(skew).isBefore(expiresAt);
    }
  }

  public static class AuthenticationException extends Exception {
    public AuthenticationException(String message) {
      super(message);
    }

    public AuthenticationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}