package tech.wenisch.contextcrate.crawl;

import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.wenisch.contextcrate.domain.CrawlConfiguration;
import tech.wenisch.contextcrate.domain.CrawlConfiguration.AuthMethod;
import tech.wenisch.contextcrate.domain.CrawlConfiguration.LoginConfiguration;
import tech.wenisch.contextcrate.service.KeycloakTokenService;
import tech.wenisch.contextcrate.service.KeycloakTokenService.AccessToken;
import tech.wenisch.contextcrate.service.KeycloakTokenService.AuthenticationException;

/**
 * Maintains authentication state local to a crawler worker. No session material is written to
 * disk, so each distributed worker can authenticate independently.
 */
@Service
public class CrawlerAuthenticationService {
  private static final int MAX_REDIRECTS = 8;
  private static final int MAX_LOGIN_STEPS = 3;
  private static final Logger log = LoggerFactory.getLogger(CrawlerAuthenticationService.class);
  private final UrlPolicy urls;
  private final KeycloakTokenService tokens;
  private final HttpClient anonymousClient;
  private final Map<UUID, FormSession> formSessions = new ConcurrentHashMap<>();
  private final Map<UUID, AccessToken> accessTokens = new ConcurrentHashMap<>();

  public CrawlerAuthenticationService(UrlPolicy urls, KeycloakTokenService tokens) {
    this.urls = urls;
    this.tokens = tokens;
    this.anonymousClient = newClient(null);
  }

  public CrawlerResponse get(UUID runId, String url, CrawlConfiguration config) throws Exception {
    LoginConfiguration login = config.loginConfiguration();
    AuthMethod method = login == null ? AuthMethod.NONE : login.authMethod();
    return switch (method) {
      case NONE -> sendGet(anonymousClient, url, config, null, 0);
      case FORM -> formGet(runId, url, config, false);
      case OAUTH2 -> oauthGet(runId, url, config, false);
    };
  }

  public String bearerToken(UUID runId, CrawlConfiguration config) throws Exception {
    if (config.loginConfiguration().authMethod() != AuthMethod.OAUTH2) return null;
    if (accessTokens.size() > 1000) {
      Instant now = Instant.now();
      accessTokens.entrySet().removeIf(entry -> !entry.getValue().usableAt(now));
    }
    AccessToken existing = accessTokens.get(runId);
    if (existing != null && existing.usableAt(Instant.now())) return existing.value();
    synchronized (accessTokens) {
      existing = accessTokens.get(runId);
      if (existing == null || !existing.usableAt(Instant.now())) {
        existing = tokens.request(config.loginConfiguration());
        accessTokens.put(runId, existing);
      }
      return existing.value();
    }
  }

  public void clear(UUID runId) {
    formSessions.remove(runId);
    accessTokens.remove(runId);
  }

  private CrawlerResponse formGet(
      UUID runId, String url, CrawlConfiguration config, boolean retried) throws Exception {
    FormSession session = formSession(runId, config);
    session.touch();
    CrawlerResponse response = sendGet(session.client(), url, config, null, 0);
    if (!retried && session.isAuthenticationUrl(response.url())) {
      log.info(
          "Form session expired while fetching {}; authentication endpoint {} was reached again; retrying once",
          diagnosticUrl(url),
          diagnosticUrl(response.url()));
      response.body().close();
      formSessions.remove(runId);
      return formGet(runId, url, config, true);
    }
    return response;
  }

  private CrawlerResponse oauthGet(
      UUID runId, String url, CrawlConfiguration config, boolean retried) throws Exception {
    String token = bearerToken(runId, config);
    CrawlerResponse response = sendGet(anonymousClient, url, config, token, 0);
    if (!retried && response.status() == 401) {
      response.body().close();
      accessTokens.remove(runId);
      return oauthGet(runId, url, config, true);
    }
    return response;
  }

  private FormSession formSession(UUID runId, CrawlConfiguration config) throws Exception {
    if (formSessions.size() > 1000) {
      Instant cutoff = Instant.now().minus(Duration.ofHours(2));
      formSessions.entrySet().removeIf(entry -> entry.getValue().lastUsed().isBefore(cutoff));
    }
    FormSession existing = formSessions.get(runId);
    if (existing != null) return existing;
    synchronized (formSessions) {
      existing = formSessions.get(runId);
      if (existing == null) {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        FormSession created = new FormSession(newClient(cookies));
        created.authenticationUrl(authenticate(created, config));
        formSessions.put(runId, created);
        existing = created;
      }
      return existing;
    }
  }

  private String authenticate(FormSession session, CrawlConfiguration config) throws Exception {
    LoginConfiguration login = config.loginConfiguration();
    urls.assertSafe(login.loginPageUrl());
    log.info("Starting form authentication at {}", diagnosticUrl(login.loginPageUrl()));
    TextResponse page =
        sendForText(
            session.client(),
            HttpRequest.newBuilder(URI.create(login.loginPageUrl()))
                .timeout(timeout(config))
                .header("User-Agent", userAgent(config))
                .GET()
                .build(),
            config,
            0);
    if (page.status() >= 400) {
      throw new AuthenticationException("Login page returned HTTP " + page.status());
    }
    log.info(
        "Authentication entry resolved to {} with HTTP {}",
        diagnosticUrl(page.url()),
        page.status());

    String authenticationUrl = page.url();
    boolean submittedUsername = false;
    boolean submittedPassword = false;
    for (int step = 0; step < MAX_LOGIN_STEPS; step++) {
      Document document = Jsoup.parse(page.body(), page.url());
      Element form = findAuthenticationForm(document, login);
      if (form == null) {
        throw new AuthenticationException(
            "No compatible login form was found at " + diagnosticUrl(page.url()));
      }

      boolean hasUsername = hasActiveNamedElement(form, login.usernameField());
      boolean hasPassword = hasActiveNamedElement(form, login.passwordField());
      String stepDescription = credentialStepDescription(hasUsername, hasPassword);
      log.info(
          "Authentication step {}/{} at {}: {} field(s) detected",
          step + 1,
          MAX_LOGIN_STEPS,
          diagnosticUrl(page.url()),
          stepDescription);
      if ((hasUsername && submittedUsername && !hasPassword)
          || (hasPassword && submittedPassword)) {
        throw formStillPresent(page.url(), stepDescription, document);
      }

      String action = form.absUrl("action");
      if (action.isBlank()) action = page.url();
      urls.assertSafe(action);
      Map<String, String> values = formValues(form, login);
      if (hasUsername && !submittedUsername) {
        values.put(login.usernameField(), login.username());
        submittedUsername = true;
      } else if (hasUsername) {
        Element username = firstActiveNamedElement(form, login.usernameField());
        if (username != null && username.hasAttr("value")) {
          values.put(login.usernameField(), username.val());
        }
      }
      if (hasPassword) {
        values.put(login.passwordField(), login.password());
        submittedPassword = true;
      }
      log.info(
          "Submitting {} form to {} with {} non-secret field(s)",
          stepDescription,
          diagnosticUrl(action),
          values.size() - (hasUsername ? 1 : 0) - (hasPassword ? 1 : 0));

      TextResponse result =
          sendForText(
              session.client(),
              loginRequest(action, page.url(), values, config),
              config,
              0);
      if (result.status() >= 400) {
        throw new AuthenticationException("Login submission returned HTTP " + result.status());
      }
      Document resultDocument = Jsoup.parse(result.body(), result.url());
      String rejection = loginRejection(resultDocument);
      if (rejection != null) {
        log.warn(
            "Authentication was rejected after submitting {} at {}: {}",
            stepDescription,
            diagnosticUrl(result.url()),
            rejection);
        throw new AuthenticationException(
            "Login was rejected after submitting " + stepDescription + ": " + rejection);
      }
      Element nextForm = findAuthenticationForm(resultDocument, login);
      log.info(
          "Authentication step {}/{} completed with HTTP {} at {}; next credential form present={}",
          step + 1,
          MAX_LOGIN_STEPS,
          result.status(),
          diagnosticUrl(result.url()),
          nextForm != null);
      if (nextForm == null) {
        verifyLogin(result, login);
        log.info("Form authentication completed at {}", diagnosticUrl(result.url()));
        return authenticationUrl;
      }
      page = result;
    }
    throw new AuthenticationException("Login did not complete within " + MAX_LOGIN_STEPS + " steps");
  }

  private static Element findAuthenticationForm(Document document, LoginConfiguration login) {
    for (Element form : document.select("form")) {
      boolean username = hasActiveNamedElement(form, login.usernameField());
      boolean password = hasActiveNamedElement(form, login.passwordField());
      if (username && password) return form;
      if (username || password) return form;
    }
    return null;
  }

  private static boolean hasActiveNamedElement(Element parent, String name) {
    return firstActiveNamedElement(parent, name) != null;
  }

  private static Element firstActiveNamedElement(Element parent, String name) {
    return parent.select("[name]").stream()
        .filter(
            element ->
                element.attr("name").equals(name)
                    && !element.attr("type").equalsIgnoreCase("hidden")
                    && !element.hasAttr("disabled")
                    && !element.hasAttr("readonly"))
        .findFirst()
        .orElse(null);
  }

  private static Map<String, String> formValues(Element form, LoginConfiguration login) {
    Map<String, String> values = new LinkedHashMap<>();
    for (Element input : form.select("input[name]")) {
      if (input.attr("type").equalsIgnoreCase("hidden")) values.put(input.attr("name"), input.val());
    }
    Element submit = form.selectFirst(login.submitSelector());
    if (submit == null) submit = form.selectFirst("button[type='submit'], input[type='submit']");
    if (submit != null && submit.hasAttr("name")) values.put(submit.attr("name"), submit.val());
    return values;
  }

  private HttpRequest loginRequest(
      String action, String referer, Map<String, String> values, CrawlConfiguration config) {
    return HttpRequest.newBuilder(URI.create(action))
        .timeout(timeout(config))
        .header("User-Agent", userAgent(config))
        .header("Referer", referer)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
        .POST(HttpRequest.BodyPublishers.ofString(form(values)))
        .build();
  }

  private static void verifyLogin(TextResponse response, LoginConfiguration login)
      throws AuthenticationException {
    var success = login.successDetection();
    if (success.urlPattern() != null && !success.urlPattern().isBlank()) {
      if (!Pattern.compile(success.urlPattern()).matcher(response.url()).find()) {
        throw new AuthenticationException("Login success URL did not match");
      }
      return;
    }
    if (success.contentPattern() != null && !success.contentPattern().isBlank()) {
      if (!Pattern.compile(success.contentPattern(), Pattern.DOTALL)
          .matcher(response.body())
          .find()) {
        throw new AuthenticationException("Login success content did not match");
      }
      return;
    }
    Document result = Jsoup.parse(response.body(), response.url());
    String rejection = loginRejection(result);
    if (rejection != null) {
      throw new AuthenticationException("Login was rejected: " + rejection);
    }
    if (findAuthenticationForm(result, login) != null) {
      throw new AuthenticationException("Login form was still present after submitting credentials");
    }
  }

  /**
   * Keycloak can render a terminal {@code login-error} page without a credential form. Treating
   * that page as success created a broken session and hid the useful error message from users.
   */
  private static String loginRejection(Document document) {
    for (String selector :
        new String[] {
          "#kc-error-message", ".kc-feedback-text", ".alert-error",
          ".pf-c-alert.pf-m-danger"
        }) {
      Element message = document.selectFirst(selector);
      if (message != null && !message.text().isBlank()) return concise(message.text());
    }
    if ("login-error".equalsIgnoreCase(document.body().attr("data-page-id"))) {
      return "identity provider returned its login error page";
    }
    return null;
  }

  private static AuthenticationException formStillPresent(
      String url, String stepDescription, Document document) {
    String message =
        "Login form was still present after submitting "
            + stepDescription
            + " at "
            + diagnosticUrl(url);
    String rejection = loginRejection(document);
    if (rejection != null) message += ": " + rejection;
    return new AuthenticationException(message);
  }

  private static String credentialStepDescription(boolean hasUsername, boolean hasPassword) {
    if (hasUsername && hasPassword) return "username and password";
    return hasUsername ? "username" : "password";
  }

  private static String concise(String text) {
    String normalized = text.replaceAll("\\s+", " ").trim();
    return normalized.length() <= 300 ? normalized : normalized.substring(0, 297) + "...";
  }

  /** Do not put OIDC state, authorization codes, or session codes into application logs. */
  private static String diagnosticUrl(String raw) {
    try {
      URI uri = URI.create(raw);
      return uri.getScheme() + "://" + uri.getAuthority() + normalizePath(uri.getPath());
    } catch (RuntimeException exception) {
      return "[unparseable URL]";
    }
  }

  private CrawlerResponse sendGet(
      HttpClient client,
      String url,
      CrawlConfiguration config,
      String bearerToken,
      int redirects)
      throws Exception {
    if (redirects > MAX_REDIRECTS) throw new java.io.IOException("Too many redirects");
    urls.assertSafe(url);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout(config))
            .header("User-Agent", userAgent(config))
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
            .GET();
    if (bearerToken != null) builder.header("Authorization", "Bearer " + bearerToken);
    HttpResponse<InputStream> response =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    if (redirect(response.statusCode()) && response.headers().firstValue("Location").isPresent()) {
      response.body().close();
      String next =
          URI.create(url).resolve(response.headers().firstValue("Location").orElseThrow()).toString();
      return sendGet(
          client, next, config, sameOrigin(url, next) ? bearerToken : null, redirects + 1);
    }
    return new CrawlerResponse(
        response.statusCode(), response.uri().toString(), response.headers(), response.body());
  }

  private TextResponse sendForText(
      HttpClient client, HttpRequest request, CrawlConfiguration config, int redirects)
      throws Exception {
    if (redirects > MAX_REDIRECTS) throw new java.io.IOException("Too many redirects");
    urls.assertSafe(request.uri().toString());
    HttpResponse<String> response =
        client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (redirect(response.statusCode()) && response.headers().firstValue("Location").isPresent()) {
      String next =
          request
              .uri()
              .resolve(response.headers().firstValue("Location").orElseThrow())
              .toString();
      log.info(
          "Authentication redirect {} {} -> {}",
          response.statusCode(),
          diagnosticUrl(request.uri().toString()),
          diagnosticUrl(next));
      HttpRequest.Builder follow =
          HttpRequest.newBuilder(URI.create(next))
              .timeout(timeout(config))
              .header("User-Agent", userAgent(config))
              .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1");
      if (preservesMethod(response.statusCode())) {
        request
            .headers()
            .firstValue("Content-Type")
            .ifPresent(value -> follow.header("Content-Type", value));
        request
            .headers()
            .firstValue("Referer")
            .ifPresent(value -> follow.header("Referer", value));
        follow.method(
            request.method(),
            request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
      } else {
        follow.GET();
      }
      return sendForText(client, follow.build(), config, redirects + 1);
    }
    return new TextResponse(response.statusCode(), response.uri().toString(), response.body());
  }

  private static HttpClient newClient(CookieManager cookies) {
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER);
    if (cookies != null) builder.cookieHandler(cookies);
    return builder.build();
  }

  private static boolean redirect(int status) {
    return status >= 300 && status < 400;
  }

  private static boolean preservesMethod(int status) {
    return status == 307 || status == 308;
  }

  private static boolean sameOrigin(String first, String second) {
    URI left = URI.create(first);
    URI right = URI.create(second);
    return left.getScheme().equalsIgnoreCase(right.getScheme())
        && left.getHost().equalsIgnoreCase(right.getHost())
        && effectivePort(left) == effectivePort(right);
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) return uri.getPort();
    return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
  }

  private static boolean sameEndpoint(String actual, String expected) {
    if (actual == null || expected == null) return false;
    try {
      URI first = URI.create(actual);
      URI second = URI.create(expected);
      return first.getScheme().equalsIgnoreCase(second.getScheme())
          && first.getHost().equalsIgnoreCase(second.getHost())
          && effectivePort(first) == effectivePort(second)
          && normalizePath(first.getPath()).equals(normalizePath(second.getPath()));
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private static String normalizePath(String path) {
    if (path == null || path.isBlank()) return "/";
    return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
  }

  private static String form(Map<String, String> values) {
    return values.entrySet().stream()
        .map(
            entry ->
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  private static Duration timeout(CrawlConfiguration config) {
    return Duration.ofMillis(config.politeness().timeoutMillis());
  }

  private static String userAgent(CrawlConfiguration config) {
    String contact = config.politeness().contact();
    return config.politeness().userAgent()
        + (contact == null || contact.isBlank() ? "" : " (" + contact + ")");
  }

  private static final class FormSession {
    private final HttpClient client;
    private volatile Instant lastUsed = Instant.now();
    private volatile String authenticationUrl;

    private FormSession(HttpClient client) {
      this.client = client;
    }

    private HttpClient client() {
      return client;
    }

    private Instant lastUsed() {
      return lastUsed;
    }

    private void touch() {
      lastUsed = Instant.now();
    }

    private boolean isAuthenticationUrl(String url) {
      return sameEndpoint(url, authenticationUrl);
    }

    private void authenticationUrl(String authenticationUrl) {
      this.authenticationUrl = authenticationUrl;
    }
  }

  private record TextResponse(int status, String url, String body) {}

  public record CrawlerResponse(
      int status, String url, HttpHeaders headers, InputStream body) {}
}
