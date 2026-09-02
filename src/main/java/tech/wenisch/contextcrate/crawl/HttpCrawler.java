package tech.wenisch.contextcrate.crawl;

import static tech.wenisch.contextcrate.domain.PipelineTypes.FetchOutcome;
import static tech.wenisch.contextcrate.domain.PipelineTypes.FrontierStatus;
import static tech.wenisch.contextcrate.domain.PipelineTypes.RunStatus;
import static tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.crawl.CrawlerAuthenticationService.CrawlerResponse;
import tech.wenisch.contextcrate.domain.CrawlConfiguration;
import tech.wenisch.contextcrate.domain.CrawlConfiguration.AuthMethod;
import tech.wenisch.contextcrate.domain.IngestionRun;
import tech.wenisch.contextcrate.domain.AcquisitionRecord;
import tech.wenisch.contextcrate.domain.SourceItem;
import tech.wenisch.contextcrate.queue.PipelineMessage;
import tech.wenisch.contextcrate.queue.PipelineQueue;
import tech.wenisch.contextcrate.repository.IngestionRunRepository;
import tech.wenisch.contextcrate.repository.AcquisitionRecordRepository;
import tech.wenisch.contextcrate.repository.SourceItemRepository;
import tech.wenisch.contextcrate.service.IngestionService;
import tech.wenisch.contextcrate.service.PipelinePayload;
import tech.wenisch.contextcrate.storage.ArtifactStore;
import tech.wenisch.contextcrate.util.InsecureSsl;

@Service
public class HttpCrawler {

  private static final int MAX_LOGIN_STEPS = 3;
  private static final Logger log = LoggerFactory.getLogger(HttpCrawler.class);
  private final SourceItemRepository frontier;
  private final IngestionRunRepository runs;
  private final IngestionService ingestion;
  private final UrlPolicy urls;
  private final RobotsService robots;
  private final HostPoliteness politeness;
  private final ArtifactStore artifacts;
  private final AcquisitionRecordRepository fetches;
  private final PipelineQueue queue;
  private final CrawlerAuthenticationService authentication;
  private final Map<UUID, String> browserStorage = new ConcurrentHashMap<>();

  public HttpCrawler(
      SourceItemRepository frontier,
      IngestionRunRepository runs,
      IngestionService ingestion,
      UrlPolicy urls,
      RobotsService robots,
      HostPoliteness politeness,
      ArtifactStore artifacts,
      AcquisitionRecordRepository fetches,
      PipelineQueue queue,
      CrawlerAuthenticationService authentication) {
    this.frontier = frontier;
    this.runs = runs;
    this.ingestion = ingestion;
    this.urls = urls;
    this.robots = robots;
    this.politeness = politeness;
    this.artifacts = artifacts;
    this.fetches = fetches;
    this.queue = queue;
    this.authentication = authentication;
  }

  @Transactional
  public void fetch(PipelinePayload payload, boolean browser) throws Exception {
    SourceItem entry = frontier.findById(payload.entityId()).orElseThrow();
    IngestionRun run = runs.findById(payload.runId()).orElseThrow();
    requireCrate(payload, run.getCrateId(), entry.getCrateId());
    if (run.getStatus() != RunStatus.RUNNING) {
      authentication.clear(run.getId());
      browserStorage.remove(run.getId());
      return;
    }
    CrawlConfiguration config = ingestion.effectiveWeb(run);
    if (browser
        || config.reliability().renderMode() == CrawlConfiguration.RenderMode.BROWSER_ONLY) {
      fetchBrowser(entry, run, config);
      return;
    }

    urls.assertSafe(entry.getLocator());
    if (config.politeness().honorRobots()
        && !robots.allowed(entry.getLocator(), config.politeness().userAgent())) {
      entry.status(FrontierStatus.EXCLUDED);
      frontier.save(entry);
      return;
    }
    politeness.await(entry.getLocator(), config.politeness().minimumDelayMillis());

    UUID fetchId = UUID.randomUUID();
    AcquisitionRecord record =
        new AcquisitionRecord(fetchId, run.getId(), entry.getId(), entry.getLocator());
    record.assignCrate(run.getCrateId());
    long started = System.nanoTime();
    try {
      CrawlerResponse response = authentication.get(run.getId(), entry.getLocator(), config);
      String type = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
      String key = "crates/" + run.getCrateId() + "/runs/" + run.getId() + "/" + fetchId + extension(type);
      try (InputStream body = response.body()) {
        var saved = artifacts.put(key, body, config.reliability().maxBodyBytes());
        record.success(
            response.url(),
            response.status(),
            type,
            charset(type),
            saved.key(),
            saved.sha256(),
            saved.length(),
            elapsedMillis(started));
      }
      fetches.save(record);
      entry.status(
          response.status() >= 200 && response.status() < 400
              ? FrontierStatus.FETCHED
              : FrontierStatus.FAILED);
      frontier.save(entry);
      if (response.status() >= 200
          && response.status() < 300
          && type.toLowerCase(Locale.ROOT).contains("html")) {
        queue.publish(
            PipelineMessage.create(
                run.getCrateId(),
                WorkStage.PARSE,
                IngestionService.payload(run.getCrateId(), run.getId(), fetchId),
                run.getId(),
                run.getCrateId() + ":parse:" + fetchId,
                50));
      }
    } catch (Exception e) {
      record.failure(
          e instanceof java.io.IOException
                  && e.getMessage() != null
                  && e.getMessage().contains("maximum")
              ? FetchOutcome.TOO_LARGE
              : FetchOutcome.FAILED,
          safeMessage(e));
      fetches.save(record);
      entry.status(FrontierStatus.FAILED);
      frontier.save(entry);
      throw e;
    }
  }

  private void fetchBrowser(SourceItem entry, IngestionRun run, CrawlConfiguration config)
      throws Exception {
    urls.assertSafe(entry.getLocator());
    UUID fetchId = UUID.randomUUID();
    AcquisitionRecord record =
        new AcquisitionRecord(fetchId, run.getId(), entry.getId(), entry.getLocator());
    record.assignCrate(run.getCrateId());
    long started = System.nanoTime();
    try (Playwright playwright = Playwright.create()) {
      Browser browser =
          playwright
              .chromium()
              .launch(new BrowserType.LaunchOptions().setHeadless(true));
      try {
        Browser.NewContextOptions options = new Browser.NewContextOptions();
        options.setIgnoreHTTPSErrors(ignoreHttpsErrors(config));
        String stored = browserStorage.get(run.getId());
        if (stored != null) options.setStorageState(stored);
        if (config.loginConfiguration().authMethod() == AuthMethod.OAUTH2) {
          options.setExtraHTTPHeaders(
              Map.of(
                  "Authorization",
                  "Bearer " + authentication.bearerToken(run.getId(), config)));
        }

        BrowserContext context = browser.newContext(options);
        AtomicReference<Exception> blockedNavigation = guardNavigations(context);
        Page page = context.newPage();
        com.microsoft.playwright.Response response =
            navigate(page, entry.getLocator(), config, blockedNavigation);
        if (config.loginConfiguration().authMethod() == AuthMethod.FORM) {
          var login = config.loginConfiguration();
          if (stored == null || hasAuthenticationForm(page, login)) {
            log.info(
                "Browser form authentication is required for {} (stored session present={})",
                diagnosticUrl(entry.getLocator()),
                stored != null);
            String authenticationUrl =
                performBrowserLogin(page, config, blockedNavigation);
            response = navigate(page, entry.getLocator(), config, blockedNavigation);
            if (hasAuthenticationForm(page, login)
                || sameEndpoint(page.url(), authenticationUrl)) {
              throw new IllegalStateException(
                  "Browser authentication returned to the login page");
            }
            browserStorage.put(run.getId(), context.storageState());
            log.info("Browser form authentication completed for {}", diagnosticUrl(entry.getLocator()));
          }
        }
        if (config.loginConfiguration().authMethod() == AuthMethod.OAUTH2
            && response != null
            && response.status() == 401) {
          context.close();
          authentication.clear(run.getId());
          options =
              new Browser.NewContextOptions()
                  .setIgnoreHTTPSErrors(ignoreHttpsErrors(config))
                  .setExtraHTTPHeaders(
                      Map.of(
                          "Authorization",
                          "Bearer " + authentication.bearerToken(run.getId(), config)));
          context = browser.newContext(options);
          blockedNavigation = guardNavigations(context);
          page = context.newPage();
          response = navigate(page, entry.getLocator(), config, blockedNavigation);
        }

        byte[] bytes = page.content().getBytes(StandardCharsets.UTF_8);
        String key = "crates/" + run.getCrateId() + "/runs/" + run.getId() + "/" + fetchId + ".rendered.html";
        var saved =
            artifacts.put(
                key, new ByteArrayInputStream(bytes), config.reliability().maxBodyBytes());
        int status = response == null ? 200 : response.status();
        record.success(
            page.url(),
            status,
            "text/html; charset=UTF-8",
            "UTF-8",
            saved.key(),
            saved.sha256(),
            saved.length(),
            elapsedMillis(started));
        fetches.save(record);
        entry.status(
            status >= 200 && status < 400 ? FrontierStatus.FETCHED : FrontierStatus.FAILED);
        frontier.save(entry);
        if (status >= 200 && status < 300) {
          queue.publish(
              PipelineMessage.create(
                  run.getCrateId(),
                  WorkStage.PARSE,
                  IngestionService.payload(run.getCrateId(), run.getId(), fetchId),
                  run.getId(),
                  run.getCrateId() + ":parse:" + fetchId,
                  50));
        }
        context.close();
      } finally {
        browser.close();
      }
    } catch (Exception e) {
      record.failure(FetchOutcome.FAILED, safeMessage(e));
      fetches.save(record);
      entry.status(FrontierStatus.FAILED);
      frontier.save(entry);
      browserStorage.remove(run.getId());
      throw e;
    }
  }

  private static void requireCrate(PipelinePayload payload, UUID... entityCrates) {
    UUID expected = payload.crateId();
    for (UUID actual : entityCrates)
      if (expected != null && !expected.equals(actual))
        throw new IllegalArgumentException("Pipeline message crosses crate boundary");
  }

  private com.microsoft.playwright.Response navigate(
      Page page,
      String url,
      CrawlConfiguration config,
      AtomicReference<Exception> blockedNavigation) {
    try {
      com.microsoft.playwright.Response response =
          page.navigate(
              url,
              new Page.NavigateOptions()
                  .setTimeout((double) config.politeness().timeoutMillis())
                  .setWaitUntil(WaitUntilState.NETWORKIDLE));
      throwIfNavigationBlocked(blockedNavigation);
      return response;
    } catch (RuntimeException exception) {
      throwIfNavigationBlocked(blockedNavigation);
      throw exception;
    }
  }

  private String performBrowserLogin(
      Page page,
      CrawlConfiguration config,
      AtomicReference<Exception> blockedNavigation)
      throws Exception {
    var login = config.loginConfiguration();
    urls.assertSafe(login.loginPageUrl());
    if (!hasAuthenticationForm(page, login)) {
      navigate(page, login.loginPageUrl(), config, blockedNavigation);
    }
    if (!hasAuthenticationForm(page, login)) {
      throw new IllegalStateException("No compatible browser login form was found");
    }
    String authenticationUrl = page.url();
    log.info("Browser authentication entry resolved to {}", diagnosticUrl(authenticationUrl));
    boolean submittedUsername = false;
    boolean submittedPassword = false;
    for (int step = 0; step < MAX_LOGIN_STEPS; step++) {
      boolean hasUsername = hasActiveField(page, login.usernameField());
      boolean hasPassword = hasActiveField(page, login.passwordField());
      String stepDescription = credentialStepDescription(hasUsername, hasPassword);
      log.info(
          "Browser authentication step {}/{} at {}: {} field(s) detected",
          step + 1,
          MAX_LOGIN_STEPS,
          diagnosticUrl(page.url()),
          stepDescription);
      if ((hasUsername && submittedUsername && !hasPassword)
          || (hasPassword && submittedPassword)) {
        throw new IllegalStateException(
            "Browser login form was still present after submitting "
                + stepDescription
                + " at "
                + diagnosticUrl(page.url()));
      }
      if (hasUsername && !submittedUsername) {
        page.fill(nameSelector(login.usernameField()), login.username());
        submittedUsername = true;
      }
      if (hasPassword) {
        page.fill(nameSelector(login.passwordField()), login.password());
        submittedPassword = true;
      }
      try {
        log.info("Submitting browser {} form at {}", stepDescription, diagnosticUrl(page.url()));
        page.click(submitSelector(page, login));
        page.waitForLoadState(
            LoadState.NETWORKIDLE,
            new Page.WaitForLoadStateOptions()
                .setTimeout((double) config.politeness().timeoutMillis()));
        throwIfNavigationBlocked(blockedNavigation);
      } catch (RuntimeException exception) {
        throwIfNavigationBlocked(blockedNavigation);
        throw exception;
      }
      String rejection = browserLoginRejection(page);
      if (rejection != null) {
        log.warn(
            "Browser authentication was rejected after submitting {} at {}: {}",
            stepDescription,
            diagnosticUrl(page.url()),
            rejection);
        throw new IllegalStateException(
            "Browser login was rejected after submitting " + stepDescription + ": " + rejection);
      }
      log.info(
          "Browser authentication step {}/{} completed at {}; next credential form present={}",
          step + 1,
          MAX_LOGIN_STEPS,
          diagnosticUrl(page.url()),
          hasAuthenticationForm(page, login));
      if (!hasAuthenticationForm(page, login)) {
        verifyBrowserLogin(page, login, authenticationUrl);
        return authenticationUrl;
      }
    }
    throw new IllegalStateException("Browser login did not complete within " + MAX_LOGIN_STEPS + " steps");
  }

  private AtomicReference<Exception> guardNavigations(BrowserContext context) {
    AtomicReference<Exception> blocked = new AtomicReference<>();
    context.route(
        "**/*",
        route -> {
          if (!route.request().isNavigationRequest()) {
            route.resume();
            return;
          }
          try {
            urls.assertSafe(route.request().url());
            route.resume();
          } catch (Exception exception) {
            blocked.compareAndSet(null, exception);
            route.abort();
          }
        });
    return blocked;
  }

  private static void throwIfNavigationBlocked(AtomicReference<Exception> blocked) {
    Exception cause = blocked.getAndSet(null);
    if (cause != null) {
      throw new SecurityException(
          "Browser navigation was blocked by the URL safety policy: " + cause.getMessage(),
          cause);
    }
  }

  private static boolean hasAuthenticationForm(
      Page page, CrawlConfiguration.LoginConfiguration login) {
    return hasActiveField(page, login.usernameField()) || hasActiveField(page, login.passwordField());
  }

  private static boolean hasActiveField(Page page, String name) {
    return page
            .locator(nameSelector(name) + ":not([type='hidden']):not([disabled]):not([readonly])")
            .count()
        > 0;
  }

  private static void verifyBrowserLogin(
      Page page, CrawlConfiguration.LoginConfiguration login, String authenticationUrl) {
    String rejection = browserLoginRejection(page);
    if (rejection != null) throw new IllegalStateException("Browser login was rejected: " + rejection);
    var success = login.successDetection();
    if (success.urlPattern() != null && !success.urlPattern().isBlank()) {
      if (!Pattern.compile(success.urlPattern()).matcher(page.url()).find()) {
        throw new IllegalStateException("Browser login success URL did not match");
      }
    } else if (success.contentPattern() != null && !success.contentPattern().isBlank()) {
      if (!Pattern.compile(success.contentPattern(), Pattern.DOTALL)
          .matcher(page.content())
          .find()) {
        throw new IllegalStateException("Browser login success content did not match");
      }
    } else if (sameEndpoint(page.url(), authenticationUrl)
        || hasAuthenticationForm(page, login)) {
      throw new IllegalStateException("Browser login form remained visible");
    }
  }

  private static String submitSelector(
      Page page, CrawlConfiguration.LoginConfiguration login) {
    if (page.locator(login.submitSelector()).count() > 0) return login.submitSelector();
    return "button[type='submit'], input[type='submit']";
  }

  private static String nameSelector(String name) {
    return "[name=\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
  }

  private static String browserLoginRejection(Page page) {
    for (String selector :
        new String[] {
          "#kc-error-message", ".kc-feedback-text", ".alert-error",
          ".pf-c-alert.pf-m-danger"
        }) {
      var locator = page.locator(selector);
      if (locator.count() > 0) {
        String text = locator.first().textContent();
        if (text != null && !text.isBlank()) return concise(text);
      }
    }
    String pageId = page.locator("body").getAttribute("data-page-id");
    return "login-error".equalsIgnoreCase(pageId)
        ? "identity provider returned its login error page"
        : null;
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
      java.net.URI uri = java.net.URI.create(raw);
      return uri.getScheme() + "://" + uri.getAuthority() + normalize(uri.getPath());
    } catch (RuntimeException exception) {
      return "[unparseable URL]";
    }
  }

  private static boolean sameEndpoint(String actual, String expected) {
    if (actual == null || expected == null) return false;
    try {
      java.net.URI left = java.net.URI.create(actual);
      java.net.URI right = java.net.URI.create(expected);
      return left.getScheme().equalsIgnoreCase(right.getScheme())
          && left.getHost().equalsIgnoreCase(right.getHost())
          && effectivePort(left) == effectivePort(right)
          && normalize(left.getPath()).equals(normalize(right.getPath()));
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static int effectivePort(java.net.URI uri) {
    if (uri.getPort() >= 0) return uri.getPort();
    return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
  }

  private static String normalize(String path) {
    if (path == null || path.isBlank()) return "/";
    return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
  }

  private static String charset(String type) {
    for (String part : type.split(";")) {
      if (part.trim().toLowerCase(Locale.ROOT).startsWith("charset=")) {
        return part.substring(part.indexOf('=') + 1).trim();
      }
    }
    return "UTF-8";
  }

  private static String extension(String type) {
    return type.toLowerCase(Locale.ROOT).contains("html") ? ".html" : ".bin";
  }

  private static long elapsedMillis(long started) {
    return Duration.ofNanos(System.nanoTime() - started).toMillis();
  }

  /** Per-job flag OR'd with the global {@code contextcrate.tls.trust-all-certificates} flag. */
  private static boolean ignoreHttpsErrors(CrawlConfiguration config) {
    return config.reliability().trustAllCertificates() || InsecureSsl.globalTrustAll();
  }

  private static String safeMessage(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
    return message.length() > 1000 ? message.substring(0, 1000) : message;
  }
}
