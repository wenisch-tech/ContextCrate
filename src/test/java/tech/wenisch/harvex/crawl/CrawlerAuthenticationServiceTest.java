package tech.wenisch.harvex.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.harvex.domain.CrawlConfiguration;
import tech.wenisch.harvex.service.KeycloakTokenService;

class CrawlerAuthenticationServiceTest {
  private HttpServer server;
  private String baseUrl;
  private CrawlerAuthenticationService authentication;
  private final AtomicInteger loginPages = new AtomicInteger();
  private final AtomicInteger loginSubmissions = new AtomicInteger();
  private final AtomicInteger tokenRequests = new AtomicInteger();
  private final AtomicInteger tokenLifetimeSeconds = new AtomicInteger(300);

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    UrlPolicy urls = new UrlPolicy(true);
    authentication =
        new CrawlerAuthenticationService(
            urls, new KeycloakTokenService(new ObjectMapper(), urls));
    server.createContext("/login", this::login);
    server.createContext("/session", this::session);
    server.createContext("/home", exchange -> respond(exchange, 200, "home"));
    server.createContext("/protected", this::protectedPage);
    server.createContext("/realms/test/protocol/openid-connect/token", this::token);
    server.createContext("/api", this::api);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void formLoginPreservesHiddenFieldsCookiesAndReusesSession() throws Exception {
    CrawlConfiguration config = formConfig("correct");
    UUID runId = UUID.randomUUID();

    try (var first = authentication.get(runId, baseUrl + "/protected", config).body();
        var second = authentication.get(runId, baseUrl + "/protected", config).body()) {
      assertThat(new String(first.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("secret");
      assertThat(new String(second.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("secret");
    }

    assertThat(loginPages).hasValue(1);
    assertThat(loginSubmissions).hasValue(1);
  }

  @Test
  void rejectedCredentialsDoNotCreateASession() {
    assertThatThrownBy(
            () ->
                authentication.get(
                    UUID.randomUUID(), baseUrl + "/protected", formConfig("wrong")))
        .hasMessageContaining("Login form was still present");
  }

  @Test
  void oauthRetriesOnceWithFreshBearerAfterUnauthorized() throws Exception {
    CrawlConfiguration config = oauthConfig();
    try (var response =
        authentication.get(UUID.randomUUID(), baseUrl + "/api", config).body()) {
      assertThat(new String(response.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("api");
    }
    assertThat(tokenRequests).hasValue(2);
  }

  @Test
  void oauthRefreshesTokenAtExpiryBoundary() throws Exception {
    tokenLifetimeSeconds.set(1);
    UUID runId = UUID.randomUUID();

    String first = authentication.bearerToken(runId, oauthConfig());
    String second = authentication.bearerToken(runId, oauthConfig());

    assertThat(first).isEqualTo("token-1");
    assertThat(second).isEqualTo("token-2");
    assertThat(tokenRequests).hasValue(2);
  }

  private void login(HttpExchange exchange) throws IOException {
    loginPages.incrementAndGet();
    exchange.getResponseHeaders().add("Set-Cookie", "login_session=abc; Path=/");
    respond(
        exchange,
        200,
        """
        <form action="/session" method="post">
          <input type="hidden" name="nonce" value="123">
          <input name="username">
          <input type="password" name="password">
          <button type="submit">Sign in</button>
        </form>
        """);
  }

  private void session(HttpExchange exchange) throws IOException {
    loginSubmissions.incrementAndGet();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String cookie = exchange.getRequestHeaders().getFirst("Cookie");
    if (body.contains("username=alice")
        && body.contains("password=correct")
        && body.contains("nonce=123")
        && cookie != null
        && cookie.contains("login_session=abc")) {
      exchange.getResponseHeaders().add("Set-Cookie", "authenticated=yes; Path=/");
      exchange.getResponseHeaders().add("Location", "/home");
      respond(exchange, 302, "");
    } else {
      login(exchange);
    }
  }

  private void protectedPage(HttpExchange exchange) throws IOException {
    String cookie = exchange.getRequestHeaders().getFirst("Cookie");
    if (cookie != null && cookie.contains("authenticated=yes")) {
      respond(exchange, 200, "secret");
    } else {
      exchange.getResponseHeaders().add("Location", "/login");
      respond(exchange, 302, "");
    }
  }

  private void token(HttpExchange exchange) throws IOException {
    int request = tokenRequests.incrementAndGet();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(body)
        .contains("grant_type=client_credentials")
        .contains("client_id=crawler")
        .contains("client_secret=secret");
    respond(
        exchange,
        200,
        "{\"access_token\":\"token-"
            + request
            + "\",\"expires_in\":"
            + tokenLifetimeSeconds.get()
            + "}");
  }

  private void api(HttpExchange exchange) throws IOException {
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    if ("Bearer token-2".equals(authorization)) respond(exchange, 200, "api");
    else respond(exchange, 401, "");
  }

  private CrawlConfiguration formConfig(String password) {
    return config(
        new CrawlConfiguration.LoginConfiguration(
            baseUrl + "/login",
            "alice",
            password,
            "username",
            "password",
            "button[type='submit']",
            new CrawlConfiguration.SuccessDetection(null, null),
            false,
            null,
            null,
            null,
            null,
            CrawlConfiguration.AuthMethod.FORM));
  }

  private CrawlConfiguration oauthConfig() {
    return config(
        new CrawlConfiguration.LoginConfiguration(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            baseUrl,
            "crawler",
            "secret",
            "test",
            CrawlConfiguration.AuthMethod.OAUTH2));
  }

  private static CrawlConfiguration config(CrawlConfiguration.LoginConfiguration login) {
    return new CrawlConfiguration(
        null,
        new CrawlConfiguration.Politeness("Test", "", false, 1, 0, 5000),
        null,
        null,
        login);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
