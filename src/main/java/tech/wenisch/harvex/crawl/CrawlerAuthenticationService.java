package tech.wenisch.harvex.crawl;

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
import org.springframework.stereotype.Service;
import tech.wenisch.harvex.domain.CrawlConfiguration;
import tech.wenisch.harvex.domain.CrawlConfiguration.AuthMethod;
import tech.wenisch.harvex.domain.CrawlConfiguration.LoginConfiguration;
import tech.wenisch.harvex.service.KeycloakTokenService;
import tech.wenisch.harvex.service.KeycloakTokenService.AccessToken;
import tech.wenisch.harvex.service.KeycloakTokenService.AuthenticationException;

/**
 * Maintains authentication state local to a crawler worker. No session material is written to
 * disk, so each distributed worker can authenticate independently.
 */
@Service
public class CrawlerAuthenticationService {
  private static final int MAX_REDIRECTS = 8;
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
    if (!retried && isLoginUrl(response.url(), config.loginConfiguration().loginPageUrl())) {
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
        authenticate(created, config);
        formSessions.put(runId, created);
        existing = created;
      }
      return existing;
    }
  }

  private void authenticate(FormSession session, CrawlConfiguration config) throws Exception {
    LoginConfiguration login = config.loginConfiguration();
    urls.assertSafe(login.loginPageUrl());
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

    Document document = Jsoup.parse(page.body(), page.url());
    Element form = findLoginForm(document, login);
    if (form == null) throw new AuthenticationException("No compatible login form was found");
    String action = form.absUrl("action");
    if (action.isBlank()) action = page.url();
    urls.assertSafe(action);

    Map<String, String> values = new LinkedHashMap<>();
    for (Element input : form.select("input[name]")) {
      String type = input.attr("type");
      if (type.equalsIgnoreCase("hidden")) values.put(input.attr("name"), input.val());
    }
    Element submit = form.selectFirst(login.submitSelector());
    if (submit != null && submit.hasAttr("name")) values.put(submit.attr("name"), submit.val());
    values.put(login.usernameField(), login.username());
    values.put(login.passwordField(), login.password());

    HttpRequest request =
        HttpRequest.newBuilder(URI.create(action))
            .timeout(timeout(config))
            .header("User-Agent", userAgent(config))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
            .POST(HttpRequest.BodyPublishers.ofString(form(values)))
            .build();
    TextResponse result = sendForText(session.client(), request, config, 0);
    verifyLogin(result, login);
  }

  private static Element findLoginForm(Document document, LoginConfiguration login) {
    for (Element form : document.select("form")) {
      boolean username = hasNamedElement(form, login.usernameField());
      boolean password = hasNamedElement(form, login.passwordField());
      if (username && password) return form;
    }
    return null;
  }

  private static boolean hasNamedElement(Element parent, String name) {
    return parent.select("[name]").stream().anyMatch(element -> element.attr("name").equals(name));
  }

  private static void verifyLogin(TextResponse response, LoginConfiguration login)
      throws AuthenticationException {
    if (response.status() >= 400) {
      throw new AuthenticationException("Login submission returned HTTP " + response.status());
    }
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
    if (findLoginForm(result, login) != null
        || isLoginUrl(response.url(), login.loginPageUrl())) {
      throw new AuthenticationException("Login form was still present after submitting credentials");
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
      HttpRequest follow =
          HttpRequest.newBuilder(URI.create(next))
              .timeout(timeout(config))
              .header("User-Agent", userAgent(config))
              .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
              .GET()
              .build();
      return sendForText(client, follow, config, redirects + 1);
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

  private static boolean isLoginUrl(String actual, String configured) {
    if (actual == null || configured == null) return false;
    URI first = URI.create(actual);
    URI second = URI.create(configured);
    return first.getScheme().equalsIgnoreCase(second.getScheme())
        && first.getHost().equalsIgnoreCase(second.getHost())
        && normalizePath(first.getPath()).equals(normalizePath(second.getPath()));
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
  }

  private record TextResponse(int status, String url, String body) {}

  public record CrawlerResponse(
      int status, String url, HttpHeaders headers, InputStream body) {}
}
