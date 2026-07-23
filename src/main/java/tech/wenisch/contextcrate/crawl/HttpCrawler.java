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
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.crawl.CrawlerAuthenticationService.CrawlerResponse;
import tech.wenisch.contextcrate.domain.CrawlConfiguration;
import tech.wenisch.contextcrate.domain.CrawlConfiguration.AuthMethod;
import tech.wenisch.contextcrate.domain.CrawlRun;
import tech.wenisch.contextcrate.domain.FetchRecord;
import tech.wenisch.contextcrate.domain.FrontierEntry;
import tech.wenisch.contextcrate.queue.PipelineMessage;
import tech.wenisch.contextcrate.queue.PipelineQueue;
import tech.wenisch.contextcrate.repository.CrawlRunRepository;
import tech.wenisch.contextcrate.repository.FetchRecordRepository;
import tech.wenisch.contextcrate.repository.FrontierEntryRepository;
import tech.wenisch.contextcrate.service.ConfigurationCodec;
import tech.wenisch.contextcrate.service.JobService;
import tech.wenisch.contextcrate.service.PipelinePayload;
import tech.wenisch.contextcrate.storage.ArtifactStore;

@Service
public class HttpCrawler {
  private final FrontierEntryRepository frontier;
  private final CrawlRunRepository runs;
  private final ConfigurationCodec codec;
  private final UrlPolicy urls;
  private final RobotsService robots;
  private final HostPoliteness politeness;
  private final ArtifactStore artifacts;
  private final FetchRecordRepository fetches;
  private final PipelineQueue queue;
  private final CrawlerAuthenticationService authentication;
  private final Map<UUID, String> browserStorage = new ConcurrentHashMap<>();

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
      CrawlerAuthenticationService authentication) {
    this.frontier = frontier;
    this.runs = runs;
    this.codec = codec;
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
    FrontierEntry entry = frontier.findById(payload.entityId()).orElseThrow();
    CrawlRun run = runs.findById(payload.runId()).orElseThrow();
    requireCrate(payload, run.getCrateId(), entry.getCrateId());
    if (run.getStatus() != RunStatus.RUNNING) {
      authentication.clear(run.getId());
      browserStorage.remove(run.getId());
      return;
    }
    CrawlConfiguration config = codec.read(run.getConfigurationJson());
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
    record.assignCrate(run.getCrateId());
    long started = System.nanoTime();
    try {
      CrawlerResponse response = authentication.get(run.getId(), entry.getUrl(), config);
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
                JobService.payload(run.getCrateId(), run.getId(), fetchId),
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

  private void fetchBrowser(FrontierEntry entry, CrawlRun run, CrawlConfiguration config)
      throws Exception {
    urls.assertSafe(entry.getUrl());
    UUID fetchId = UUID.randomUUID();
    FetchRecord record = new FetchRecord(fetchId, run.getId(), entry.getId(), entry.getUrl());
    record.assignCrate(run.getCrateId());
    long started = System.nanoTime();
    try (Playwright playwright = Playwright.create()) {
      Browser browser =
          playwright
              .chromium()
              .launch(new BrowserType.LaunchOptions().setHeadless(true));
      try {
        Browser.NewContextOptions options = new Browser.NewContextOptions();
        String stored = browserStorage.get(run.getId());
        if (stored != null) options.setStorageState(stored);
        if (config.loginConfiguration().authMethod() == AuthMethod.OAUTH2) {
          options.setExtraHTTPHeaders(
              Map.of(
                  "Authorization",
                  "Bearer " + authentication.bearerToken(run.getId(), config)));
        }

        BrowserContext context = browser.newContext(options);
        Page page = context.newPage();
        com.microsoft.playwright.Response response = navigate(page, entry.getUrl(), config);
        if (config.loginConfiguration().authMethod() == AuthMethod.FORM
            && (stored == null
                || loginUrl(page.url(), config.loginConfiguration().loginPageUrl()))) {
          performBrowserLogin(page, config);
          browserStorage.put(run.getId(), context.storageState());
          response = navigate(page, entry.getUrl(), config);
          if (loginUrl(page.url(), config.loginConfiguration().loginPageUrl())) {
            throw new IllegalStateException("Browser authentication did not leave the login page");
          }
        }
        if (config.loginConfiguration().authMethod() == AuthMethod.OAUTH2
            && response != null
            && response.status() == 401) {
          context.close();
          authentication.clear(run.getId());
          options =
              new Browser.NewContextOptions()
                  .setExtraHTTPHeaders(
                      Map.of(
                          "Authorization",
                          "Bearer " + authentication.bearerToken(run.getId(), config)));
          context = browser.newContext(options);
          page = context.newPage();
          response = navigate(page, entry.getUrl(), config);
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
                  JobService.payload(run.getCrateId(), run.getId(), fetchId),
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

  private static com.microsoft.playwright.Response navigate(
      Page page, String url, CrawlConfiguration config) {
    return page.navigate(
        url,
        new Page.NavigateOptions()
            .setTimeout((double) config.politeness().timeoutMillis())
            .setWaitUntil(WaitUntilState.NETWORKIDLE));
  }

  private void performBrowserLogin(Page page, CrawlConfiguration config) throws Exception {
    var login = config.loginConfiguration();
    urls.assertSafe(login.loginPageUrl());
    if (!loginUrl(page.url(), login.loginPageUrl())) navigate(page, login.loginPageUrl(), config);
    page.fill(nameSelector(login.usernameField()), login.username());
    page.fill(nameSelector(login.passwordField()), login.password());
    page.click(login.submitSelector());
    page.waitForLoadState(
        LoadState.NETWORKIDLE,
        new Page.WaitForLoadStateOptions()
            .setTimeout((double) config.politeness().timeoutMillis()));

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
    } else if (loginUrl(page.url(), login.loginPageUrl())
        || page.locator(nameSelector(login.passwordField())).count() > 0) {
      throw new IllegalStateException("Browser login form remained visible");
    }
  }

  private static String nameSelector(String name) {
    return "[name=\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
  }

  private static boolean loginUrl(String actual, String configured) {
    if (actual == null || configured == null) return false;
    try {
      java.net.URI left = java.net.URI.create(actual);
      java.net.URI right = java.net.URI.create(configured);
      return left.getScheme().equalsIgnoreCase(right.getScheme())
          && left.getHost().equalsIgnoreCase(right.getHost())
          && normalize(left.getPath()).equals(normalize(right.getPath()));
    } catch (RuntimeException e) {
      return false;
    }
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

  private static String safeMessage(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
    return message.length() > 1000 ? message.substring(0, 1000) : message;
  }
}
