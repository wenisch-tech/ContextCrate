package tech.wenisch.contextcrate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.crawl.UrlPolicy;
import tech.wenisch.contextcrate.domain.CrawlConfiguration.LoginConfiguration;
import tech.wenisch.contextcrate.domain.CrawlConfiguration.AuthMethod;

class KeycloakTokenServicePasswordGrantTest {
  private HttpServer server;
  private String baseUrl;
  private KeycloakTokenService tokenService;
  private final AtomicInteger tokenRequests = new AtomicInteger();

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    UrlPolicy urls = new UrlPolicy(true);
    tokenService = new KeycloakTokenService(new ObjectMapper(), urls);
    server.createContext("/realms/test/protocol/openid-connect/token", this::token);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void passwordGrantWorksWithUsernamePassword() throws Exception {
    LoginConfiguration config = new LoginConfiguration(
        null,
        "testuser",
        "testpassword",
        null,
        null,
        null,
        null,
        false,
        baseUrl,
        "test-client",
        null, // clientSecret not needed for password grant
        "test",
        AuthMethod.OAUTH2);

    KeycloakTokenService.AccessToken token = tokenService.request(config);

    assertThat(token.value()).isEqualTo("test-token");
    assertThat(tokenRequests).hasValue(1);
  }

  @Test
  void clientCredentialsGrantStillWorks() throws Exception {
    LoginConfiguration config = new LoginConfiguration(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        baseUrl,
        "test-client",
        "client-secret",
        "test",
        AuthMethod.OAUTH2);

    KeycloakTokenService.AccessToken token = tokenService.request(config);

    assertThat(token.value()).isEqualTo("test-token");
    assertThat(tokenRequests).hasValue(1);
  }

  @Test
  void passwordGrantRequiresBothUsernameAndPassword() {
    LoginConfiguration config = new LoginConfiguration(
        null,
        "testuser",
        null, // missing password
        null,
        null,
        null,
        null,
        false,
        baseUrl,
        "test-client",
        null,
        "test",
        AuthMethod.OAUTH2);

    assertThatThrownBy(() -> tokenService.request(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OAuth2 password grant requires both username and password");
  }

  @Test
  void clientCredentialsGrantRequiresClientSecret() {
    LoginConfiguration config = new LoginConfiguration(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        baseUrl,
        "test-client",
        null, // missing client secret
        "test",
        AuthMethod.OAUTH2);

    assertThatThrownBy(() -> tokenService.request(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OAuth2 client credentials grant requires client secret");
  }

  private void token(HttpExchange exchange) throws IOException {
    tokenRequests.incrementAndGet();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

    // Handle both client_credentials and password grants
    if (body.contains("grant_type=client_credentials")) {
      if (!body.contains("client_id=test-client") || !body.contains("client_secret=client-secret")) {
        respond(exchange, 400, "{\"error\":\"invalid_client\"}");
        return;
      }
    } else if (body.contains("grant_type=password")) {
      if (!body.contains("client_id=test-client") ||
          !body.contains("username=testuser") ||
          !body.contains("password=testpassword")) {
        respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
        return;
      }
    } else {
      respond(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
      return;
    }

    respond(exchange, 200, "{\"access_token\":\"test-token\",\"expires_in\":300}");
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
