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
  private HttpServer identityServer;
  private String baseUrl;
  private String identityBaseUrl;
  private CrawlerAuthenticationService authentication;
  private final AtomicInteger loginPages = new AtomicInteger();
  private final AtomicInteger loginSubmissions = new AtomicInteger();
  private final AtomicInteger identifierSubmissions = new AtomicInteger();
  private final AtomicInteger passwordSubmissions = new AtomicInteger();
  private final AtomicInteger keycloakErrorSubmissions = new AtomicInteger();
  private final AtomicInteger ssoLoginPages = new AtomicInteger();
  private final AtomicInteger ssoLoginSubmissions = new AtomicInteger();
  private final AtomicInteger ssoCallbacks = new AtomicInteger();
  private final AtomicInteger wpSessionGeneration = new AtomicInteger();
  private final AtomicInteger tokenRequests = new AtomicInteger();
  private final AtomicInteger tokenLifetimeSeconds = new AtomicInteger(300);

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    identityServer = HttpServer.create(new InetSocketAddress("127.0.0.2", 0), 0);
    identityBaseUrl = "http://127.0.0.2:" + identityServer.getAddress().getPort();
    UrlPolicy urls = new UrlPolicy(true);
    authentication =
        new CrawlerAuthenticationService(
            urls, new KeycloakTokenService(new ObjectMapper(), urls));
    server.createContext("/login", this::login);
    server.createContext("/session", this::session);
    server.createContext("/identifier-login", this::identifierLogin);
    server.createContext("/password-login", this::passwordLogin);
    server.createContext("/password-finish", this::passwordFinish);
    server.createContext("/keycloak-error", this::keycloakErrorLogin);
    server.createContext("/keycloak-error-submit", this::keycloakErrorSubmit);
    server.createContext("/home", exchange -> respond(exchange, 200, "home"));
    server.createContext("/protected", this::protectedPage);
    server.createContext("/sso/protected", this::ssoProtectedPage);
    server.createContext("/sso/callback", this::ssoCallback);
    server.createContext("/sso/failing-callback", exchange -> respond(exchange, 502, "bad callback"));
    server.createContext(
        "/redirect-loop",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/redirect-loop");
          respond(exchange, 302, "");
        });
    server.createContext(
        "/unsafe-login",
        exchange -> {
          exchange
              .getResponseHeaders()
              .add("Location", "http://127.0.0.2:9/private");
          respond(exchange, 302, "");
        });
    server.createContext("/login-307", exchange -> redirectLoginForm(exchange, 307));
    server.createContext("/login-308", exchange -> redirectLoginForm(exchange, 308));
    server.createContext("/form-redirect-307", exchange -> preservePostRedirect(exchange, 307));
    server.createContext("/form-redirect-308", exchange -> preservePostRedirect(exchange, 308));
    server.createContext("/session-preserved", this::preservedSession);
    server.createContext("/realms/test/protocol/openid-connect/token", this::token);
    server.createContext("/api", this::api);
    identityServer.createContext(
        "/realms/site/protocol/openid-connect/auth", this::ssoLogin);
    identityServer.createContext(
        "/realms/site/login-actions/authenticate", this::ssoAuthenticate);
    server.start();
    identityServer.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.stop(0);
    if (identityServer != null) identityServer.stop(0);
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
  void identifierFirstLoginSubmitsUsernameThenPasswordAcrossTwoForms() throws Exception {
    CrawlConfiguration config = formConfig("correct", baseUrl + "/identifier-login");

    try (var response =
        authentication.get(UUID.randomUUID(), baseUrl + "/protected", config).body()) {
      assertThat(new String(response.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("secret");
    }

    assertThat(identifierSubmissions).hasValue(1);
    assertThat(passwordSubmissions).hasValue(1);
  }

  @Test
  void keycloakLoginErrorPageIsReportedInsteadOfBeingTreatedAsSuccess() {
    CrawlConfiguration config = formConfig("wrong", baseUrl + "/keycloak-error");

    assertThatThrownBy(() -> authentication.get(UUID.randomUUID(), baseUrl + "/protected", config))
        .hasMessageContaining("Login was rejected after submitting username")
        .hasMessageContaining("Invalid username or password");

    assertThat(keycloakErrorSubmissions).hasValue(1);
  }

  @Test
  void protectedWordPressEntryCompletesCrossOriginKeycloakFlowAndReusesCookies()
      throws Exception {
    CrawlConfiguration config = formConfig("correct", baseUrl + "/sso/protected");
    UUID runId = UUID.randomUUID();

    try (var first = authentication.get(runId, baseUrl + "/sso/protected", config).body();
        var second = authentication.get(runId, baseUrl + "/sso/protected", config).body()) {
      assertThat(new String(first.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("wordpress");
      assertThat(new String(second.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("wordpress");
    }

    assertThat(ssoLoginPages).hasValue(1);
    assertThat(ssoLoginSubmissions).hasValue(1);
    assertThat(ssoCallbacks).hasValue(1);
  }

  @Test
  void expiredWordPressCookieTriggersOneFreshLoginSubmission() throws Exception {
    CrawlConfiguration config = formConfig("correct", baseUrl + "/sso/protected");
    UUID runId = UUID.randomUUID();

    try (var initial = authentication.get(runId, baseUrl + "/sso/protected", config).body()) {
      assertThat(new String(initial.readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("wordpress");
    }
    wpSessionGeneration.incrementAndGet();
    try (var renewed = authentication.get(runId, baseUrl + "/sso/protected", config).body()) {
      assertThat(new String(renewed.readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("wordpress");
    }

    assertThat(ssoLoginSubmissions).hasValue(2);
    assertThat(ssoCallbacks).hasValue(2);
  }

  @Test
  void missingLoginFormAndRedirectLimitAreReported() {
    assertThatThrownBy(
            () ->
                authentication.get(
                    UUID.randomUUID(),
                    baseUrl + "/protected",
                    formConfig("correct", baseUrl + "/home")))
        .hasMessageContaining("No compatible login form");

    assertThatThrownBy(
            () ->
                authentication.get(
                    UUID.randomUUID(),
                    baseUrl + "/protected",
                    formConfig("correct", baseUrl + "/redirect-loop")))
        .hasMessageContaining("Too many redirects");
  }

  @Test
  void unsafeAuthenticationRedirectIsRejected() {
    String blocked = "http://127.0.0.2:9/private";
    UrlPolicy policy =
        new UrlPolicy(true) {
          @Override
          public void assertSafe(String raw) {
            if (blocked.equals(raw)) throw new SecurityException("blocked authentication redirect");
          }
        };
    var service =
        new CrawlerAuthenticationService(
            policy, new KeycloakTokenService(new ObjectMapper(), policy));

    assertThatThrownBy(
            () ->
                service.get(
                    UUID.randomUUID(),
                    baseUrl + "/protected",
                    formConfig("correct", baseUrl + "/unsafe-login")))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("blocked authentication redirect");
  }

  @Test
  void callbackFailureDoesNotCacheAFormSession() {
    CrawlConfiguration config =
        formConfig("callback-failure", baseUrl + "/sso/protected");
    UUID runId = UUID.randomUUID();

    assertThatThrownBy(
            () -> authentication.get(runId, baseUrl + "/sso/protected", config))
        .hasMessageContaining("Login submission returned HTTP 502");
    assertThatThrownBy(
            () -> authentication.get(runId, baseUrl + "/sso/protected", config))
        .hasMessageContaining("Login submission returned HTTP 502");

    assertThat(ssoLoginSubmissions).hasValue(2);
  }

  @Test
  void temporaryRedirectsPreserveLoginPostMethodAndBody() throws Exception {
    for (int status : new int[] {307, 308}) {
      CrawlConfiguration config = formConfig("correct", baseUrl + "/login-" + status);
      try (var response =
          authentication.get(
              UUID.randomUUID(), baseUrl + "/protected", config).body()) {
        assertThat(new String(response.readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo("secret");
      }
    }
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

  @Test
  void oauthPasswordGrantWorksWithUsernamePassword() throws Exception {
    CrawlConfiguration config = oauthPasswordConfig();
    try (var response =
        authentication.get(UUID.randomUUID(), baseUrl + "/api", config).body()) {
      assertThat(new String(response.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("api");
    }
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

  private void identifierLogin(HttpExchange exchange) throws IOException {
    respond(
        exchange,
        200,
        """
        <form action="/password-login" method="post">
          <input type="hidden" name="flow" value="identifier-first">
          <input name="username">
          <input type="submit" name="login" value="Continue">
        </form>
        """);
  }

  private void passwordLogin(HttpExchange exchange) throws IOException {
    identifierSubmissions.incrementAndGet();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    if (!body.contains("flow=identifier-first") || !body.contains("username=alice")) {
      respond(exchange, 400, "bad identifier request");
      return;
    }
    respond(
        exchange,
        200,
        """
        <form action="/password-finish" method="post">
          <input type="hidden" name="flow" value="password-step">
          <input type="hidden" name="username" value="alice">
          <input type="password" name="password">
          <input type="submit" name="login" value="Sign in">
        </form>
        """);
  }

  private void passwordFinish(HttpExchange exchange) throws IOException {
    passwordSubmissions.incrementAndGet();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    if (body.contains("flow=password-step")
        && body.contains("username=alice")
        && body.contains("password=correct")) {
      exchange.getResponseHeaders().add("Set-Cookie", "authenticated=yes; Path=/;");
      exchange.getResponseHeaders().add("Location", "/home");
      respond(exchange, 302, "");
    } else {
      passwordLogin(exchange);
    }
  }

  private void keycloakErrorLogin(HttpExchange exchange) throws IOException {
    respond(
        exchange,
        200,
        """
        <body data-page-id="login-login-username">
          <form action="/keycloak-error-submit" method="post">
            <input name="username">
            <input type="submit" name="login" value="Continue">
          </form>
        </body>
        """);
  }

  private void keycloakErrorSubmit(HttpExchange exchange) throws IOException {
    keycloakErrorSubmissions.incrementAndGet();
    respond(
        exchange,
        200,
        """
        <body data-page-id="login-error">
          <div id="kc-error-message"><p>Invalid username or password.</p></div>
        </body>
        """);
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

  private void ssoProtectedPage(HttpExchange exchange) throws IOException {
    String cookie = exchange.getRequestHeaders().getFirst("Cookie");
    String expected = "wp_session=session-" + wpSessionGeneration.get();
    if (cookie != null && cookie.contains(expected) && wpSessionGeneration.get() > 0) {
      respond(exchange, 200, "wordpress");
      return;
    }
    exchange
        .getResponseHeaders()
        .add(
            "Location",
            identityBaseUrl
                + "/realms/site/protocol/openid-connect/auth?client_id=wordpress"
                + "&redirect_uri="
                + java.net.URLEncoder.encode(
                    baseUrl + "/sso/callback", StandardCharsets.UTF_8));
    respond(exchange, 302, "");
  }

  private void ssoLogin(HttpExchange exchange) throws IOException {
    ssoLoginPages.incrementAndGet();
    exchange.getResponseHeaders().add("Set-Cookie", "kc_flow=active; Path=/; HttpOnly");
    respond(
        exchange,
        200,
        """
        <form action="/realms/site/login-actions/authenticate?session_code=abc" method="post">
          <input type="hidden" name="execution" value="e1">
          <input name="username">
          <input type="password" name="password">
          <input type="submit" name="credentialId" value="">
        </form>
        """);
  }

  private void ssoAuthenticate(HttpExchange exchange) throws IOException {
    ssoLoginSubmissions.incrementAndGet();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String cookie = exchange.getRequestHeaders().getFirst("Cookie");
    boolean statePresent =
        body.contains("execution=e1")
            && body.contains("username=alice")
            && body.contains("credentialId=")
            && cookie != null
            && cookie.contains("kc_flow=active");
    if (statePresent && body.contains("password=correct")) {
      exchange.getResponseHeaders().add("Location", baseUrl + "/sso/callback?code=accepted");
      respond(exchange, 302, "");
    } else if (statePresent && body.contains("password=callback-failure")) {
      exchange.getResponseHeaders().add("Location", baseUrl + "/sso/failing-callback");
      respond(exchange, 302, "");
    } else {
      ssoLogin(exchange);
    }
  }

  private void ssoCallback(HttpExchange exchange) throws IOException {
    ssoCallbacks.incrementAndGet();
    int generation = wpSessionGeneration.incrementAndGet();
    exchange
        .getResponseHeaders()
        .add("Set-Cookie", "wp_session=session-" + generation + "; Path=/; HttpOnly");
    exchange.getResponseHeaders().add("Location", "/sso/protected");
    respond(exchange, 302, "");
  }

  private void redirectLoginForm(HttpExchange exchange, int status) throws IOException {
    String action = status == 307 ? "/form-redirect-307" : "/form-redirect-308";
    respond(
        exchange,
        200,
        """
        <form action="%s" method="post">
          <input type="hidden" name="nonce" value="preserved">
          <input name="username">
          <input type="password" name="password">
          <button type="submit">Sign in</button>
        </form>
        """
            .formatted(action));
  }

  private void preservePostRedirect(HttpExchange exchange, int status) throws IOException {
    assertThat(exchange.getRequestMethod()).isEqualTo("POST");
    exchange.getResponseHeaders().add("Location", "/session-preserved");
    respond(exchange, status, "");
  }

  private void preservedSession(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(exchange.getRequestMethod()).isEqualTo("POST");
    assertThat(body)
        .contains("username=alice")
        .contains("password=correct")
        .contains("nonce=preserved");
    exchange.getResponseHeaders().add("Set-Cookie", "authenticated=yes; Path=/");
    exchange.getResponseHeaders().add("Location", "/home");
    respond(exchange, 302, "");
  }

  private void token(HttpExchange exchange) throws IOException {
    int request = tokenRequests.incrementAndGet();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

    // Handle both client_credentials and password grants
    if (body.contains("grant_type=client_credentials")) {
      assertThat(body)
          .contains("client_id=crawler")
          .contains("client_secret=secret");
    } else if (body.contains("grant_type=password")) {
      assertThat(body)
          .contains("client_id=crawler")
          .contains("username=alice")
          .contains("password=password123");
    } else {
      throw new IllegalStateException("Unknown grant type in token request");
    }

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
    return formConfig(password, baseUrl + "/login");
  }

  private CrawlConfiguration formConfig(String password, String entryUrl) {
    return config(
        new CrawlConfiguration.LoginConfiguration(
            entryUrl,
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

  private CrawlConfiguration oauthPasswordConfig() {
    return config(
        new CrawlConfiguration.LoginConfiguration(
            null,
            "alice",
            "password123",
            null,
            null,
            null,
            null,
            false,
            baseUrl,
            "crawler",
            null, // clientSecret not needed for password grant
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
