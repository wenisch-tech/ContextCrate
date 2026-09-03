package tech.wenisch.contextcrate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;

class OidcTrustConfigTest {
  private static final String REDIRECT_URI = "http://localhost/login/oauth2/code/keycloak";
  private final AtomicReference<TokenRequest> received = new AtomicReference<>();
  private final AtomicReference<TokenResponse> response = new AtomicReference<>();
  private HttpServer provider;
  private String issuer;

  @BeforeEach
  void startProvider() throws Exception {
    provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuer = "http://127.0.0.1:" + provider.getAddress().getPort();
    provider.createContext("/token", exchange -> {
      received.set(new TokenRequest(exchange.getRequestMethod(),
          exchange.getRequestHeaders().getFirst("Content-Type"),
          exchange.getRequestHeaders().getFirst("Authorization"),
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      TokenResponse reply = response.get();
      byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(reply.status(), body.length);
      try (var output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    provider.start();
  }

  @AfterEach
  void stopProvider() {
    if (provider != null) provider.stop(0);
  }

  @Test
  void exchangesAuthorizationCodeAndPreservesKeycloakTokens() {
    response.set(new TokenResponse(200, """
        {"access_token":"keycloak-access-token","token_type":"Bearer","expires_in":300,
         "refresh_token":"keycloak-refresh-token","scope":"openid profile email",
         "id_token":"keycloak-id-token","session_state":"keycloak-session"}
        """));

    var tokens = tokenClient().getTokenResponse(grantRequest());

    assertThat(tokens.getAccessToken().getTokenValue()).isEqualTo("keycloak-access-token");
    assertThat(tokens.getAccessToken().getTokenType()).isEqualTo(OAuth2AccessToken.TokenType.BEARER);
    assertThat(Duration.between(tokens.getAccessToken().getIssuedAt(),
        tokens.getAccessToken().getExpiresAt())).isEqualTo(Duration.ofSeconds(300));
    assertThat(tokens.getAccessToken().getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
    assertThat(tokens.getRefreshToken().getTokenValue()).isEqualTo("keycloak-refresh-token");
    assertThat(tokens.getAdditionalParameters()).containsEntry("id_token", "keycloak-id-token")
        .containsEntry("session_state", "keycloak-session");

    assertThat(received.get().method()).isEqualTo("POST");
    assertThat(received.get().contentType()).startsWith("application/x-www-form-urlencoded");
    assertThat(received.get().authorization()).isEqualTo("Basic " + Base64.getEncoder()
        .encodeToString("contextcrate:test-secret".getBytes(StandardCharsets.UTF_8)));
    assertThat(form(received.get().body())).containsEntry("grant_type", "authorization_code")
        .containsEntry("code", "code+with/special=characters")
        .containsEntry("redirect_uri", REDIRECT_URI);
  }

  @Test
  void preservesOAuthErrorFromRejectedAuthorizationCode() {
    response.set(new TokenResponse(400, """
        {"error":"invalid_grant","error_description":"Code not valid"}
        """));

    assertThatThrownBy(() -> tokenClient().getTokenResponse(grantRequest()))
        .isInstanceOfSatisfying(OAuth2AuthorizationException.class, exception -> {
          assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_grant");
          assertThat(exception.getError().getDescription()).isEqualTo("Code not valid");
        });
  }

  private RestClientAuthorizationCodeTokenResponseClient tokenClient() {
    var client = new RestClientAuthorizationCodeTokenResponseClient();
    client.setRestClient(new OidcTrustConfig().insecureOidcHttpClients().restClient());
    return client;
  }

  private OAuth2AuthorizationCodeGrantRequest grantRequest() {
    var registration = ClientRegistration.withRegistrationId("keycloak")
        .clientId("contextcrate").clientSecret("test-secret")
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri(REDIRECT_URI).scope("openid", "profile", "email")
        .authorizationUri(issuer + "/authorize").tokenUri(issuer + "/token").build();
    var request = OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri(issuer + "/authorize").clientId("contextcrate")
        .redirectUri(REDIRECT_URI).state("test-state").build();
    var callback = OAuth2AuthorizationResponse.success("code+with/special=characters")
        .redirectUri(REDIRECT_URI).state("test-state").build();
    return new OAuth2AuthorizationCodeGrantRequest(registration,
        new OAuth2AuthorizationExchange(request, callback));
  }

  private static Map<String, String> form(String encoded) {
    return Arrays.stream(encoded.split("&")).map(pair -> pair.split("=", 2))
        .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
            pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
  }

  private record TokenRequest(String method, String contentType, String authorization, String body) {}
  private record TokenResponse(int status, String body) {}
}
