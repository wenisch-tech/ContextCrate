package tech.wenisch.harvex.crawl;

import static tech.wenisch.harvex.domain.PipelineTypes.*;

import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.net.URLEncoder;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import tech.wenisch.harvex.domain.*;
import tech.wenisch.harvex.domain.PipelineTypes.FetchOutcome;
import tech.wenisch.harvex.domain.PipelineTypes.FrontierStatus;
import tech.wenisch.harvex.domain.PipelineTypes.RunStatus;
import tech.wenisch.harvex.domain.PipelineTypes.WorkStage;
import tech.wenisch.harvex.queue.*;
import tech.wenisch.harvex.repository.*;
import tech.wenisch.harvex.service.*;
import tech.wenisch.harvex.storage.ArtifactStore;

@Service
public class HttpCrawler {
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(15))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final FrontierEntryRepository frontier;
  private final CrawlRunRepository runs;
  private final ConfigurationCodec codec;
  private final UrlPolicy urls;
  private final RobotsService robots;
  private final HostPoliteness politeness;
  private final ArtifactStore artifacts;
  private final FetchRecordRepository fetches;
  private final PipelineQueue queue;
  private final RunLogger runLogger;
  private final KeycloakTokenService keycloakTokenService;
  private final OAuth2SessionCache oauth2SessionCache;
  private static final Logger log = LoggerFactory.getLogger(HttpCrawler.class);

  @Autowired
  public HttpCrawler(
      FrontierEntryRepository frontier,
      CrawlRunRepository runs,
      ConfigurationCodec codec,
      UrlPolicy urls,
      RobotsService robots,
      HostPoliteness politeness,
      ArtifactStore artifacts,
      FetchRecordRepository fetches,
      PipelineQueue queue,
      RunLogger runLogger,
      KeycloakTokenService keycloakTokenService,
      OAuth2SessionCache oauth2SessionCache) {
    this.frontier = frontier;
    this.runs = runs;
    this.codec = codec;
    this.urls = urls;
    this.robots = robots;
    this.politeness = politeness;
    this.artifacts = artifacts;
    this.fetches = fetches;
    this.queue = queue;
    this.runLogger = runLogger;
    this.keycloakTokenService = keycloakTokenService;
    this.oauth2SessionCache = oauth2SessionCache;
  }

  public HttpCrawler(
      FrontierEntryRepository frontier,
      CrawlRunRepository runs,
      ConfigurationCodec codec,
      UrlPolicy urls,
      RobotsService robots,
      HostPoliteness politeness,
      ArtifactStore artifacts,
      FetchRecordRepository fetches,
      PipelineQueue queue) {
    this(frontier, runs, codec, urls, robots, politeness, artifacts, fetches, queue, null, null, null);
  }

  @Transactional
  public void fetch(PipelinePayload payload, boolean browser) throws Exception {
    FrontierEntry entry = frontier.findById(payload.entityId()).orElseThrow();
    CrawlRun run = runs.findById(payload.runId()).orElseThrow();
    if (run.getStatus() != RunStatus.RUNNING) {
      log.debug("Skipping fetch for run {}: run status is {}", run.getId(), run.getStatus());
      if (runLogger != null) {
        runLogger.log(run.getId(), "DEBUG", "Skipping fetch: run status is " + run.getStatus());
      }
      return;
    }
    CrawlConfiguration config = codec.read(run.getConfigurationJson());
    log.info("Starting fetch for URL: {} (run: {}, entry: {})", entry.getUrl(), run.getId(), entry.getId());
    if (runLogger != null) {
        runLogger.log(run.getId(), "INFO", "Starting fetch for URL: " + entry.getUrl());
    }
    if (browser
        || config.reliability().renderMode() == CrawlConfiguration.RenderMode.BROWSER_ONLY) {
      fetchBrowser(entry, run, config);
      return;
    }
    urls.assertSafe(entry.getUrl());
    if (config.politeness().honorRobots()
        && !robots.allowed(entry.getUrl(), config.politeness().userAgent())) {
      entry.status(FrontierStatus.EXCLUDED);
      frontier.save(entry);
      return;
    }
    politeness.await(entry.getUrl(), config.politeness().minimumDelayMillis());
    UUID fetchId = UUID.randomUUID();
    FetchRecord record = new FetchRecord(fetchId, run.getId(), entry.getId(), entry.getUrl());
    long started = System.nanoTime();
    try {
      Response response = request(run.getId(), entry.getUrl(), config, 0, false);
      String type = response.headers.firstValue("Content-Type").orElse("application/octet-stream");
      String key = run.getId() + "/" + fetchId + extension(type);
      try (InputStream body = response.body) {
        var saved = artifacts.put(key, body, config.reliability().maxBodyBytes());
        record.success(
            response.url,
            response.status,
            type,
            charset(type),
            saved.key(),
            saved.sha256(),
            saved.length(),
            Duration.ofNanos(System.nanoTime() - started).toMillis());
      }
      fetches.save(record);
      FrontierStatus newStatus = response.status >= 200 && response.status < 400
          ? FrontierStatus.FETCHED
          : FrontierStatus.FAILED;
      entry.status(newStatus);
      frontier.save(entry);
      log.info("Fetch completed for URL: {} (run: {}, status: {}, time: {}ms)",
          entry.getUrl(), run.getId(), response.status(), Duration.ofNanos(System.nanoTime() - started).toMillis());
      if (runLogger != null) {
        runLogger.log(run.getId(), "INFO", "Fetch completed for URL: " + entry.getUrl() +
                     " (status: " + response.status() + ", time: " +
                     Duration.ofNanos(System.nanoTime() - started).toMillis() + "ms)");
      }

      if (response.status >= 200
          && response.status < 300
          && type.toLowerCase(Locale.ROOT).contains("html")) {
        log.debug("Queuing parse job for fetched URL: {}", entry.getUrl());
        queue.publish(
            PipelineMessage.create(
                WorkStage.PARSE,
                JobService.payload(run.getId(), fetchId),
                run.getId(),
                "parse:" + fetchId,
                50));
      }
    } catch (java.io.IOException e) {
      FetchOutcome outcome = e.getMessage() != null && e.getMessage().contains("maximum")
          ? FetchOutcome.TOO_LARGE
          : FetchOutcome.FAILED;
      record.failure(outcome, e.getMessage());
      fetches.save(record);
      entry.status(FrontierStatus.FAILED);
      frontier.save(entry);
      log.error("Fetch failed for URL: {} (run: {}, error: {})",
          entry.getUrl(), run.getId(), e.getMessage());
      if (runLogger != null) {
        runLogger.log(run.getId(), "ERROR", "Fetch failed for URL: " + entry.getUrl() +
                     " (error: " + e.getMessage() + ")");
      }
      throw e;
    }
  }

  private boolean hasValidSession(UUID runId, CrawlConfiguration config) {
    if (config.loginConfiguration() == null || !config.loginConfiguration().isConfigured()) {
      log.debug("No login configuration for run {}", runId);
      return true;
    }

    log.debug("Checking session for run {} with auth method: {}", runId, config.loginConfiguration().authMethod());

    // For OAuth2 authentication, check if we have a valid OAuth2 token
    if (config.loginConfiguration().authMethod() == CrawlConfiguration.AuthMethod.OAUTH2) {
      String oauthToken = getOAuth2TokenForRun(runId);
      log.debug("OAuth2 token check for run {}: token={}", runId, oauthToken != null ? "PRESENT" : "MISSING");
      if (runLogger != null) {
        runLogger.log(runId, "DEBUG", "OAuth2 token check: " + (oauthToken != null ? "PRESENT" : "MISSING"));
      }

      if (oauthToken != null && !oauthToken.isBlank()) {
        log.debug("Valid OAuth2 token found for run {}", runId);
        if (runLogger != null) {
          runLogger.log(runId, "DEBUG", "Valid OAuth2 token found");
        }
        return true;
      } else {
        log.debug("No OAuth2 token found for run {}", runId);
        if (runLogger != null) {
          runLogger.log(runId, "DEBUG", "No OAuth2 token found");
        }
        return false;
      }
    }

    // For form-based authentication, check for session cookies
    String cookiesForRun = getCookiesForRun(runId, config);
    log.debug("Cookies check for run {}: cookies={}", runId, cookiesForRun != null ? "PRESENT" : "MISSING");

    if (cookiesForRun == null || cookiesForRun.isBlank()) {
      log.debug("No cookies found for run {}", runId);
      if (runLogger != null) {
        runLogger.log(runId, "DEBUG", "No cookies found");
      }
      return false;
    }

    // Check if any of the cookies contain session information
    boolean hasSession = cookiesForRun.contains("session") ||
           cookiesForRun.contains("Session") ||
           cookiesForRun.contains("JSESSIONID");
    log.debug("Session cookie check for run {}: hasSession={}", runId, hasSession);
    return hasSession;
  }

  private boolean shouldPerformDirectLogin(CrawlConfiguration config) {
    return config.loginConfiguration() != null &&
           config.loginConfiguration().isConfigured() &&
           config.loginConfiguration().directLogin();
  }

    private Response request(UUID runId, String url, CrawlConfiguration config, int redirects, boolean skipAuthentication) throws Exception {
    if (redirects > 5) throw new java.io.IOException("Too many redirects");

    // Only perform login if we're not already in a login process and authentication is not skipped
    if (!skipAuthentication) {
      // Check if we need to perform direct login (pre-authentication)
      if (shouldPerformDirectLogin(config)) {
        performHttpLogin(runId, config);
      }
      // Check if we need to perform login during normal request flow
      else if (config.loginConfiguration() != null && config.loginConfiguration().isConfigured() && !hasValidSession(runId, config)) {
        performHttpLogin(runId, config);
      }
    }

    urls.assertSafe(url);
    var reqBuilder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(config.politeness().timeoutMillis()))
            .header(
                "User-Agent",
                config.politeness().userAgent()
                    + (config.politeness().contact().isBlank()
                        ? ""
                        : " (" + config.politeness().contact() + ")"))
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1");

    // Add OAuth2 authorization header if available
    String oauthToken = getOAuth2TokenForRun(runId);
    if (oauthToken != null && !oauthToken.isBlank()) {
      reqBuilder.header("Authorization", "Bearer " + oauthToken);
    }

    String cookies = getCookiesForRun(runId, config);
    if (cookies != null && !cookies.isBlank()) {
      reqBuilder.header("Cookie", cookies);
    }

    var req = reqBuilder.GET().build();
    var res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
    if (res.statusCode() >= 300
        && res.statusCode() < 400
        && res.headers().firstValue("Location").isPresent()) {
      res.body().close();
      String next = URI.create(url).resolve(res.headers().firstValue("Location").get()).toString();
      return request(runId, next, config, redirects + 1, skipAuthentication);
    }
    return new Response(res.statusCode(), res.uri().toString(), res.headers(), res.body());
  }

    private void performHttpLogin(UUID runId, CrawlConfiguration config) throws Exception {
        if (config.loginConfiguration() == null || !config.loginConfiguration().isConfigured()) {
            return;
        }

        // Handle OAuth2 authentication (Keycloak direct access grants)
        if (config.loginConfiguration().authMethod() == CrawlConfiguration.AuthMethod.OAUTH2) {
            handleOAuth2Login(runId, config);
            return;
        }

        String username = config.loginConfiguration().username();
        String password = config.loginConfiguration().password();
        String loginPageUrl = config.loginConfiguration().loginPageUrl();

        if (username == null || password == null || loginPageUrl == null) {
            log.info("Missing login credentials for run {}", runId);
            if (runLogger != null) {
                runLogger.log(runId, "INFO", "Missing login credentials");
            }
            return;
        }

        log.info("Performing HTTP login for run {}...", runId);
        if (runLogger != null) {
            runLogger.log(runId, "INFO", "Performing HTTP login");
        }

        // Check if the login page URL is valid
        try {
            URI loginUri = URI.create(loginPageUrl);
            if (loginUri.getHost() == null) {
                log.info("Invalid login page URL: {}", loginPageUrl);
                if (runLogger != null) {
                    runLogger.log(runId, "INFO", "Invalid login page URL: " + loginPageUrl);
                }
                return;
            }
        } catch (Exception e) {
            log.info("Invalid login page URL: {} - {}", loginPageUrl, e.getMessage());
            if (runLogger != null) {
                runLogger.log(runId, "INFO", "Invalid login page URL: " + loginPageUrl + " - " + e.getMessage());
            }
            return;
        }

        log.debug("Using login page: {}", loginPageUrl);
        if (runLogger != null) {
            runLogger.log(runId, "DEBUG", "Using login page: " + loginPageUrl);
        }

        // Fetch login page content
        Response loginPageResponse = request(runId, loginPageUrl, config, 0, true);
        String loginPageContent = new String(loginPageResponse.body().readAllBytes(), charset(loginPageResponse.headers().firstValue("Content-Type").orElse("UTF-8")));

        // Check if this is a Keycloak authentication flow
        if (isKeycloakLoginPage(loginPageContent)) {
            log.debug("Detected Keycloak authentication flow");
            handleKeycloakLogin(runId, loginPageUrl, loginPageContent, username, password, config);
        }
        // Check if this is a multi-step authentication flow (like older Keycloak or other SSO)
        else if (loginPageContent.contains("execution=") && loginPageContent.contains("client_id=")) {
            log.debug("Detected multi-step authentication flow");
            handleMultiStepLogin(runId, loginPageUrl, loginPageContent, username, password, config);
        } else {
            // Single-step authentication (traditional login form)
            log.debug("Detected single-step authentication flow");
            handleSingleStepLogin(runId, loginPageUrl, loginPageContent, username, password, config);
        }
    }

    private void handleOAuth2Login(UUID runId, CrawlConfiguration config) throws Exception {
        CrawlConfiguration.LoginConfiguration loginConfig = config.loginConfiguration();

        log.info("Performing OAuth2 login for run {}...", runId);
        if (runLogger != null) {
            runLogger.log(runId, "INFO", "Performing OAuth2 login");
        }

        try {
            // Get OAuth2 access token using client credentials flow
            String accessToken = keycloakTokenService.getAccessToken(
                loginConfig.authServerUrl(),
                loginConfig.realm(),
                loginConfig.clientId(),
                loginConfig.clientSecret()
            );

            log.debug("Successfully obtained OAuth2 access token for run {}", runId);
            if (runLogger != null) {
                runLogger.log(runId, "DEBUG", "Successfully obtained OAuth2 access token");
            }

            // Store the token for use in subsequent requests
            storeOAuth2Token(runId, accessToken);

        } catch (Exception e) {
            log.error("OAuth2 login failed for run {}: {}", runId, e.getMessage());
            if (runLogger != null) {
                runLogger.log(runId, "ERROR", "OAuth2 login failed: " + e.getMessage());
            }
            throw new Exception("OAuth2 login failed: " + e.getMessage(), e);
        }
    }

    private void storeOAuth2Token(UUID runId, String accessToken) {
        if (oauth2SessionCache != null) {
            oauth2SessionCache.storeToken(runId, accessToken);
        } else {
            log.warn("OAuth2SessionCache is not available, falling back to file storage");
            // Fallback to file storage for backward compatibility
            try {
                String tokenPath = "oauth2_token_" + runId + ".txt";
                java.io.File tokenFile = new java.io.File(tokenPath);
                Files.writeString(tokenFile.toPath(), accessToken);
                log.debug("Stored OAuth2 token for run {} (file fallback)", runId);
            } catch (Exception e) {
                log.warn("Failed to store OAuth2 token for run {}: {}", runId, e.getMessage());
            }
        }
    }

    private String getOAuth2TokenForRun(UUID runId) {
        if (oauth2SessionCache != null) {
            return oauth2SessionCache.getToken(runId);
        } else {
            log.warn("OAuth2SessionCache is not available, falling back to file storage");
            // Fallback to file storage for backward compatibility
            try {
                String tokenPath = "oauth2_token_" + runId + ".txt";
                java.io.File tokenFile = new java.io.File(tokenPath);
                if (!tokenFile.exists()) {
                    log.debug("No OAuth2 token file found for run {}", runId);
                    return null;
                }

                String accessToken = Files.readString(tokenFile.toPath());
                log.debug("Found OAuth2 token for run {} (file fallback)", runId);
                return accessToken;
            } catch (Exception e) {
                log.warn("Error reading OAuth2 token for run {}: {}", runId, e.getMessage());
                return null;
            }
        }
    }

  private String findLoginPage(UUID runId, String url, CrawlConfiguration config, String loginUrlPattern) throws Exception {
    // First try the current URL if it matches the login pattern
    if (url.contains(loginUrlPattern)) {
      return url;
    }

    // If not, try to find a login page by following links
    Response response = request(runId, url, config, 0, true);
    String content = new String(response.body().readAllBytes(), charset(response.headers().firstValue("Content-Type").orElse("UTF-8")));

    // Look for login links
    Pattern pattern = Pattern.compile("<a[^>]+href=\"([^\"]*" + loginUrlPattern + "[^\"]*)\"[^>]*>");
    Matcher matcher = pattern.matcher(content);

    if (matcher.find()) {
      String loginUrl = matcher.group(1);
      loginUrl = decodeHtmlEntities(loginUrl);
      if (loginUrl.startsWith("http")) {
        return loginUrl;
      } else {
        return URI.create(url).resolve(loginUrl).toString();
      }
    }

    // Try to find forms that might be login forms
    Pattern formPattern = Pattern.compile("<form[^>]+action=\"([^\"]+)\"[^>]*>");
    Matcher formMatcher = formPattern.matcher(content);
    while (formMatcher.find()) {
      String formAction = formMatcher.group(1);
      formAction = decodeHtmlEntities(formAction);
      if (formAction.contains(loginUrlPattern)) {
        if (formAction.startsWith("http")) {
          return formAction;
        } else {
          return URI.create(url).resolve(formAction).toString();
        }
      }
    }

    return null;
  }

  private Response submitForm(UUID runId, String url, Map<String, String> formData, CrawlConfiguration config, boolean skipAuthentication) throws Exception {
    // Get cookies for this run
    String cookieHeader = skipAuthentication ? null : getCookiesForRun(runId, config);

    // Build form data
    StringBuilder formBody = new StringBuilder();
    for (Map.Entry<String, String> entry : formData.entrySet()) {
      if (formBody.length() > 0) {
        formBody.append("&");
      }
      formBody.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
              .append("=")
              .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
    }

    // Build request
    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("User-Agent", config.politeness().userAgent())
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.5")
        .timeout(Duration.ofMillis(config.politeness().timeoutMillis()))
        .POST(HttpRequest.BodyPublishers.ofString(formBody.toString()));

    if (cookieHeader != null && !cookieHeader.isBlank()) {
      requestBuilder.header("Cookie", cookieHeader);
    }

    // Send request
    HttpResponse<InputStream> response =
        client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

    return new Response(response.statusCode(), response.uri().toString(), response.headers(), response.body());
  }

  private String getCookiesForRun(UUID runId, CrawlConfiguration config) {
    try {
      String sessionPath = "session_" + runId + ".json";
      java.io.File sessionFile = new java.io.File(sessionPath);
      if (!sessionFile.exists()) {
        log.debug("No session file found for run {}", runId);
        return null;
      }

      String jsonContent = Files.readString(sessionFile.toPath());
      List<String> cookieStrings = new ArrayList<>();
      Pattern pattern = Pattern.compile("\"name\":\"([^\"]+)\",\"value\":\"([^\"]+)\"");
      Matcher matcher = pattern.matcher(jsonContent);
      while (matcher.find()) {
        cookieStrings.add(matcher.group(1) + "=" + matcher.group(2));
      }

      String cookies = String.join("; ", cookieStrings);
      log.debug("Found cookies for run {}: {}", runId, cookies);
      return cookies;
    } catch (Exception e) {
      log.warn("Error reading cookies for run {}: {}", runId, e.getMessage());
      return null;
    }
  }

  private void fetchBrowser(FrontierEntry entry, CrawlRun run, CrawlConfiguration config)
      throws Exception {
    urls.assertSafe(entry.getUrl());
    UUID fetchId = UUID.randomUUID();
    long started = System.nanoTime();
    try (var playwright = com.microsoft.playwright.Playwright.create()) {
      var browser =
          playwright
              .chromium()
              .launch(new com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(true));

      String sessionPath = "session_" + run.getId() + ".json";
      var contextOptions = new com.microsoft.playwright.Browser.NewContextOptions();
      java.io.File sessionFile = new java.io.File(sessionPath);
      if (sessionFile.exists()) {
        contextOptions.setStorageStatePath(Paths.get(sessionPath));
      }

      var context = browser.newContext(contextOptions);
      var page = context.newPage();

      var response =
          page.navigate(
              entry.getUrl(),
              new com.microsoft.playwright.Page.NavigateOptions()
                  .setTimeout((double) config.politeness().timeoutMillis())
                  .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.NETWORKIDLE));

      if (config.loginConfiguration() != null && config.loginConfiguration().isConfigured()
          && (config.loginConfiguration().loginPageUrl() == null || config.loginConfiguration().loginPageUrl().isBlank()
              || page.url().contains(config.loginConfiguration().loginPageUrl()))) {

        handleLogin(page, config);

        response = page.navigate(
            entry.getUrl(),
            new com.microsoft.playwright.Page.NavigateOptions()
                .setTimeout((double) config.politeness().timeoutMillis())
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.NETWORKIDLE));

        context.storageState(new com.microsoft.playwright.BrowserContext.StorageStateOptions()
            .setPath(Paths.get(sessionPath)));
      }
      byte[] bytes = page.content().getBytes(StandardCharsets.UTF_8);
      String key = run.getId() + "/" + fetchId + ".rendered.html";
      var saved =
          artifacts.put(
              key, new java.io.ByteArrayInputStream(bytes), config.reliability().maxBodyBytes());
      var record = new FetchRecord(fetchId, run.getId(), entry.getId(), entry.getUrl());
      record.success(
          page.url(),
          response == null ? 200 : response.status(),
          "text/html; charset=UTF-8",
          "UTF-8",
          saved.key(),
          saved.sha256(),
          saved.length(),
          Duration.ofNanos(System.nanoTime() - started).toMillis());
      fetches.save(record);
      entry.status(FrontierStatus.FETCHED);
      frontier.save(entry);
      queue.publish(
          PipelineMessage.create(
              WorkStage.PARSE,
              JobService.payload(run.getId(), fetchId),
              run.getId(),
              "parse:" + fetchId,
              50));
      browser.close();
    }
  }

    private void handleLogin(com.microsoft.playwright.Page page, CrawlConfiguration config) {
      try {
        // Try to detect and handle Keycloak login specifically
        if (isKeycloakLoginPage(page.content())) {
          handleKeycloakLoginInBrowser(page, config);
          return;
        }

        // Try standard login patterns
        try {
          page.fill("#username", config.loginConfiguration().username());
          page.fill("#password", config.loginConfiguration().password());
          page.click("button[type='submit']");
          page.waitForURL("**");
        } catch (Exception e) {
          // Fallback for different login form patterns
          String usernameField = detectUsernameField(page.content());
          page.fill("input[name='" + usernameField + "']", config.loginConfiguration().username());
          page.fill("input[name='password']", config.loginConfiguration().password());

          // Try different button selectors
          try {
            page.click("#kc-login");
          } catch (Exception ex) {
            try {
              page.click("button[type='submit']");
            } catch (Exception exc) {
              page.click("input[type='submit']");
            }
          }
          page.waitForURL("**");
        }
      } catch (Exception e) {
        throw new RuntimeException("Failed to perform login: " + e.getMessage());
      }
    }

    private void handleKeycloakLoginInBrowser(com.microsoft.playwright.Page page, CrawlConfiguration config) {
      try {
        String usernameField = detectUsernameField(page.content());
        String passwordField = "password";

        // Fill in credentials
        page.fill("input[name='" + usernameField + "']", config.loginConfiguration().username());
        page.fill("input[name='" + passwordField + "']", config.loginConfiguration().password());

        // Try different Keycloak button selectors
        try {
          page.click("#kc-login");
        } catch (Exception e) {
          try {
            page.click("input[name='login']");
          } catch (Exception ex) {
            try {
              page.click("button[type='submit']");
            } catch (Exception exc) {
              // Try to find any clickable element that might submit the form
              page.click("input[type='submit'], button[type='submit'], [role='button']");
            }
          }
        }

        // Wait for navigation to complete
        page.waitForURL("**");
      } catch (Exception e) {
        throw new RuntimeException("Failed to perform Keycloak login: " + e.getMessage());
      }
    }

  private static String charset(String type) {
    for (String part : type.split(";"))
      if (part.trim().toLowerCase(Locale.ROOT).startsWith("charset="))
        return part.substring(part.indexOf('=') + 1).trim();
    return "UTF-8";
  }

  private static String extension(String type) {
    return type.toLowerCase(Locale.ROOT).contains("html") ? ".html" : ".bin";
  }

private static String decodeHtmlEntities(String input) {
    if (input == null) {
        return null;
    }

    return input
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&");
}
    private boolean isKeycloakLoginPage(String htmlContent) {
        // Check for Keycloak-specific patterns (case-insensitive)
        String lowerContent = htmlContent.toLowerCase();

        // Check for execution and client_id fields (handling both single and double quotes)
        boolean hasExecution = lowerContent.contains("name=\"execution\"") ||
                              lowerContent.contains("name='execution'");
        boolean hasClientId = lowerContent.contains("name=\"client_id\"") ||
                             lowerContent.contains("name='client_id'");
        boolean hasLoginActionsAndClientId = lowerContent.contains("login-actions") && hasClientId;
        boolean hasLoginActionsTextAndClientId = lowerContent.contains("login-actions") && lowerContent.contains("client_id");

        log.debug("Keycloak detection - content: {}, hasExecution: {}, hasClientId: {}, login-actions+client_id: {}, login-actions-text+client_id: {}, execution+client_id: {}",
                 lowerContent, hasExecution, hasClientId, hasLoginActionsAndClientId, hasLoginActionsTextAndClientId, (hasExecution && hasClientId));

        return lowerContent.contains("keycloak") ||
               lowerContent.contains("kc-form-login") ||
               lowerContent.contains("realms/") ||
               hasLoginActionsAndClientId ||
               hasLoginActionsTextAndClientId ||
               (hasExecution && hasClientId) ||
               lowerContent.contains("kc-login");
    }

    private void handleKeycloakLogin(UUID runId, String loginPageUrl, String loginPageContent,
                                   String username, String password, CrawlConfiguration config) throws Exception {
        // Extract form action for Keycloak login
        String formAction = extractFormAction(loginPageContent, loginPageUrl);
        log.debug("Extracted Keycloak form action: {}", formAction);

        // Prepare form data for Keycloak
        Map<String, String> formData = extractKeycloakFormFields(loginPageContent);

        // Detect username field name - Keycloak often uses specific field names
        String usernameField = detectUsernameField(loginPageContent);

        // Add credentials
        formData.put(usernameField, username);
        formData.put("password", password);

        // Keycloak-specific fields
        formData.put("credentialId", "");

        // Add CSRF token if present (required for Keycloak security)
        if (loginPageContent.contains("name=\"X-XSRF-TOKEN\"") || loginPageContent.contains("name='X-XSRF-TOKEN'")) {
            // Extract CSRF token from the page
            Pattern csrfPattern = Pattern.compile("<input[^>]+name=[\"']X-XSRF-TOKEN[\"'][^>]*value=[\"']([^\"']*)[\"'][^>]*>");
            Matcher csrfMatcher = csrfPattern.matcher(loginPageContent);
            if (csrfMatcher.find()) {
                formData.put("X-XSRF-TOKEN", csrfMatcher.group(1));
                log.debug("Extracted CSRF token for Keycloak login");
            }
        }

        // Submit form - skip authentication since we're already in the login process
        log.info("Submitting Keycloak login form to {} for run {}", formAction, runId);
        if (runLogger != null) {
            runLogger.log(runId, "INFO", "Submitting Keycloak login form to: " + formAction);
        }

        Response response = submitForm(runId, formAction, formData, config, true);

        // Check if login was successful
        if (response.status() >= 400) {
            log.info("Keycloak login failed: HTTP {} for URL: {}", response.status(), formAction);
            if (runLogger != null) {
                runLogger.log(runId, "INFO", "Keycloak login failed: HTTP " + response.status() + " for URL: " + formAction);
            }

            // If login failed, check if we need to handle multi-step authentication
            // Some Keycloak instances require username first, then password
            if (response.status() == 400 && loginPageContent.contains("execution=") && loginPageContent.contains("client_id=")) {
                log.debug("Keycloak login failed, trying multi-step authentication for run {}", runId);
                if (runLogger != null) {
                    runLogger.log(runId, "DEBUG", "Trying multi-step authentication after Keycloak failure");
                }
                handleMultiStepLogin(runId, loginPageUrl, loginPageContent, username, password, config);
            }
            return;
        }

        // If login was successful, extract and save cookies
        if (response.status() >= 200 && response.status() < 400) {
            // Extract cookies from the response headers
            String setCookieHeader = response.headers().firstValue("Set-Cookie").orElse(null);
            if (setCookieHeader != null) {
                log.debug("Keycloak login successful, cookies received for run {}", runId);
                if (runLogger != null) {
                    runLogger.log(runId, "DEBUG", "Keycloak login successful, cookies received");
                }
            }
        }

        log.debug("Keycloak login successful for run {}", runId);
        if (runLogger != null) {
            runLogger.log(runId, "DEBUG", "Keycloak login successful");
        }
    }

    private Map<String, String> extractKeycloakFormFields(String htmlContent) {
        Map<String, String> formFields = new HashMap<>();

        // Extract all hidden fields
        Pattern hiddenFieldPattern = Pattern.compile("<input[^>]+type=[\"']hidden[\"'][^>]+name=[\"']([^\"']+)[\"'][^>]*value=[\"']([^\"']*)[\"'][^>]*>");
        Matcher hiddenMatcher = hiddenFieldPattern.matcher(htmlContent);

        while (hiddenMatcher.find()) {
            String fieldName = hiddenMatcher.group(1);
            String fieldValue = hiddenMatcher.group(2);
            formFields.put(fieldName, fieldValue);
            log.debug("Extracted Keycloak hidden field: {} = {}", fieldName, fieldValue);
        }

        // Extract execution and client_id fields specifically for Keycloak (more flexible pattern)
        Pattern keycloakFieldPattern = Pattern.compile("<input[^>]+name=[\"'](execution|client_id|tab_id|session_code)[\"'][^>]*value=[\"']([^\"']*)[\"'][^>]*>");
        Matcher keycloakMatcher = keycloakFieldPattern.matcher(htmlContent);

        while (keycloakMatcher.find()) {
            String fieldName = keycloakMatcher.group(1);
            String fieldValue = keycloakMatcher.group(2);
            formFields.put(fieldName, fieldValue);
            log.debug("Extracted Keycloak field: {} = {}", fieldName, fieldValue);
        }

        return formFields;
    }

    private void handleMultiStepLogin(UUID runId, String loginPageUrl, String loginPageContent,
                                    String username, String password, CrawlConfiguration config) throws Exception {
        // Step 1: Submit username
        String usernameFormAction = extractFormAction(loginPageContent, loginPageUrl);
        log.debug("Extracted form action: {}", usernameFormAction);

        // Prepare username form data - extract all form fields from the HTML
        Map<String, String> usernameFormData = extractFormFields(loginPageContent);

        // Add username field based on detected field name
        String usernameField = detectUsernameField(loginPageContent);
        usernameFormData.put(usernameField, username);
        usernameFormData.put("credentialId", "");

        // Submit username form - skip authentication since we're already in the login process
        Response usernameResponse = submitForm(runId, usernameFormAction, usernameFormData, config, true);
        String usernameResponseContent = new String(usernameResponse.body().readAllBytes(), charset(usernameResponse.headers().firstValue("Content-Type").orElse("UTF-8")));

        // Check if username submission was successful
        if (usernameResponse.status() >= 400) {
            log.info("Username submission failed: HTTP {} for URL: {}", usernameResponse.status(), usernameFormAction);
            if (runLogger != null) {
                runLogger.log(runId, "INFO", "Username submission failed: HTTP " + usernameResponse.status() + " for URL: " + usernameFormAction);
            }
            // Return for all errors in multi-step flows
            return;
        }

        // Step 2: Submit password on the returned page
        String passwordFormAction = extractFormAction(usernameResponseContent, usernameResponse.url());
        log.debug("Extracted password form action: {}", passwordFormAction);

        // Prepare password form data
        Map<String, String> passwordFormData = extractFormFields(usernameResponseContent);

        // Add credentials
        boolean includeUsername = usernameResponseContent.contains("username") ||
                                 usernameResponseContent.contains("email") ||
                                 usernameResponseContent.contains("username-email");

        if (includeUsername) {
            usernameField = detectUsernameField(usernameResponseContent);
            passwordFormData.put(usernameField, username);
        }

        passwordFormData.put("password", password);

        // Submit password form - skip authentication since we're already in the login process
        Response passwordResponse = submitForm(runId, passwordFormAction, passwordFormData, config, true);

        // Check if login was successful
        if (passwordResponse.status() >= 400) {
            log.debug("Password submission failed: HTTP {}", passwordResponse.status());
            if (runLogger != null) {
                runLogger.log(runId, "DEBUG", "Password submission failed: HTTP " + passwordResponse.status());
            }
            return;
        }

        log.debug("Multi-step login successful for run {}", runId);
        if (runLogger != null) {
            runLogger.log(runId, "DEBUG", "Multi-step login successful");
        }
    }

    private void handleSingleStepLogin(UUID runId, String loginPageUrl, String loginPageContent,
                                     String username, String password, CrawlConfiguration config) throws Exception {
        String formAction = extractFormAction(loginPageContent, loginPageUrl);
        log.debug("Extracted form action: {}", formAction);

        // Prepare form data
        Map<String, String> formData = extractFormFields(loginPageContent);

        // Add credentials
        String usernameField = detectUsernameField(loginPageContent);
        formData.put(usernameField, username);
        formData.put("password", password);

        // Submit form - skip authentication since we're already in the login process
        log.info("Submitting login form to {} for run {}", formAction, runId);
        if (runLogger != null) {
            runLogger.log(runId, "INFO", "Submitting login form to: " + formAction);
        }

        Response response = submitForm(runId, formAction, formData, config, true);

        // Check if login was successful
        if (response.status() >= 400) {
            log.info("Login failed: HTTP {} for URL: {}", response.status(), formAction);
            if (runLogger != null) {
                runLogger.log(runId, "INFO", "Login failed: HTTP " + response.status() + " for URL: " + formAction);
            }
            return;
        }

        log.debug("Single-step login successful for run {}", runId);
        if (runLogger != null) {
            runLogger.log(runId, "DEBUG", "Single-step login successful");
        }
    }

    private String detectUsernameField(String htmlContent) {
        // Detect username field name based on various patterns
        if (htmlContent.contains("username-email")) {
            return "username-email";
        } else if (htmlContent.contains("email") && !htmlContent.contains("gravatar")) {
            // Look for input with name="email"
            Pattern emailPattern = Pattern.compile("<input[^>]+name=[\"']email[\"'][^>]*>");
            Matcher emailMatcher = emailPattern.matcher(htmlContent);
            if (emailMatcher.find()) {
                return "email";
            }
        } else if (htmlContent.contains("username")) {
            // Look for input with name="username"
            Pattern usernamePattern = Pattern.compile("<input[^>]+name=[\"']username[\"'][^>]*>");
            Matcher usernameMatcher = usernamePattern.matcher(htmlContent);
            if (usernameMatcher.find()) {
                return "username";
            }
        } else if (htmlContent.contains("user_name")) {
            return "user_name";
        } else if (htmlContent.contains("login")) {
            // Look for input with name containing "login"
            Pattern loginPattern = Pattern.compile("<input[^>]+name=[\"']([^\"']*login[^\"']*)[\"'][^>]*>");
            Matcher loginMatcher = loginPattern.matcher(htmlContent);
            if (loginMatcher.find()) {
                return loginMatcher.group(1);
            }
        }
        return "username"; // default
    }

    private String extractFormAction(String htmlContent, String baseUrl) {
        // Try to find form action for login forms
        Pattern pattern = Pattern.compile("<form[^>]+action=\"([^\"]+)\"[^>]*>");
        Matcher matcher = pattern.matcher(htmlContent);

        if (matcher.find()) {
            String actionUrl = matcher.group(1);
            // Decode HTML entities in the URL
            actionUrl = decodeHtmlEntities(actionUrl);
            if (actionUrl.startsWith("http")) {
                return actionUrl;
            } else if (actionUrl.startsWith("?")) {
                // Handle query string only form actions
                return baseUrl + actionUrl;
            } else if (actionUrl.startsWith("/")) {
                // Handle absolute path form actions
                try {
                    URI baseUri = URI.create(baseUrl);
                    return baseUri.getScheme() + "://" + baseUri.getHost() + (baseUri.getPort() != -1 ? ":" + baseUri.getPort() : "") + actionUrl;
                } catch (Exception e) {
                    return baseUrl + actionUrl;
                }
            } else {
                // Relative URL, resolve against base URL
                return URI.create(baseUrl).resolve(actionUrl).toString();
            }
        }

        // Fallback to base URL if no form action found
        return baseUrl;
    }

    private Map<String, String> extractFormFields(String htmlContent) {
        Map<String, String> formFields = new HashMap<>();

        // Extract hidden input fields
        Pattern hiddenFieldPattern = Pattern.compile("<input[^>]+type=\"hidden\"[^>]+name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"[^>]*>");
        Matcher hiddenMatcher = hiddenFieldPattern.matcher(htmlContent);

        while (hiddenMatcher.find()) {
            String fieldName = hiddenMatcher.group(1);
            String fieldValue = hiddenMatcher.group(2);
            formFields.put(fieldName, fieldValue);
            log.debug("Extracted hidden field: {} = {}", fieldName, fieldValue);
        }

        // Extract other input fields that might be needed for the form
        Pattern inputFieldPattern = Pattern.compile("<input[^>]+name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"[^>]*>");
        Matcher inputMatcher = inputFieldPattern.matcher(htmlContent);

        while (inputMatcher.find()) {
            String fieldName = inputMatcher.group(1);
            String fieldValue = inputMatcher.group(2);
            // Only add fields that aren't username/password (we'll add those separately)
            if (!fieldName.equals("username") && !fieldName.equals("email") &&
                !fieldName.equals("username-email") && !fieldName.equals("password") &&
                !fieldName.equals("user_name") && !fieldName.toLowerCase().contains("login")) {
                formFields.put(fieldName, fieldValue);
                log.debug("Extracted input field: {} = {}", fieldName, fieldValue);
            }
        }

        return formFields;
    }

  private record Response(int status, String url, HttpHeaders headers, InputStream body) {}
}