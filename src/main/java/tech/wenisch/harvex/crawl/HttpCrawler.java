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
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.harvex.domain.*;
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
    this.frontier = frontier;
    this.runs = runs;
    this.codec = codec;
    this.urls = urls;
    this.robots = robots;
    this.politeness = politeness;
    this.artifacts = artifacts;
    this.fetches = fetches;
    this.queue = queue;
  }

  @Transactional
  public void fetch(PipelinePayload payload, boolean browser) throws Exception {
    FrontierEntry entry = frontier.findById(payload.entityId()).orElseThrow();
    CrawlRun run = runs.findById(payload.runId()).orElseThrow();
    if (run.getStatus() != RunStatus.RUNNING) return;
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
    long started = System.nanoTime();
    try {
      Response response = request(run.getId(), entry.getUrl(), config, 0);
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
      entry.status(
          response.status >= 200 && response.status < 400
              ? FrontierStatus.FETCHED
              : FrontierStatus.FAILED);
      frontier.save(entry);
      if (response.status >= 200
          && response.status < 300
          && type.toLowerCase(Locale.ROOT).contains("html"))
        queue.publish(
            PipelineMessage.create(
                WorkStage.PARSE,
                JobService.payload(run.getId(), fetchId),
                run.getId(),
                "parse:" + fetchId,
                50));
    } catch (java.io.IOException e) {
      record.failure(
          e.getMessage() != null && e.getMessage().contains("maximum")
              ? FetchOutcome.TOO_LARGE
              : FetchOutcome.FAILED,
          e.getMessage());
      fetches.save(record);
      entry.status(FrontierStatus.FAILED);
      frontier.save(entry);
      throw e;
    }
  }

  private Response request(UUID runId, String url, CrawlConfiguration config, int redirects) throws Exception {
    if (redirects > 5) throw new java.io.IOException("Too many redirects");
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
      return request(runId, next, config, redirects + 1);
    }
    return new Response(res.statusCode(), res.uri().toString(), res.headers(), res.body());
  }

  private String getCookiesForRun(UUID runId, CrawlConfiguration config) {
    try {
      String sessionPath = "session_" + runId + ".json";
      java.io.File sessionFile = new java.io.File(sessionPath);
      if (!sessionFile.exists()) return null;

      String jsonContent = Files.readString(sessionFile.toPath());
      List<String> cookieStrings = new ArrayList<>();
      Pattern pattern = Pattern.compile("\"name\":\"([^\"]+)\",\"value\":\"([^\"]+)\"");
      Matcher matcher = pattern.matcher(jsonContent);
      while (matcher.find()) {
        cookieStrings.add(matcher.group(1) + "=" + matcher.group(2));
      }
      return String.join("; ", cookieStrings);
    } catch (Exception e) {
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

      if (config.authentication().username() != null 
          && (config.authentication().loginUrlPattern().isBlank() 
              || page.url().contains(config.authentication().loginUrlPattern()))) {
        
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
      page.fill("#username", config.authentication().username());
      page.fill("#password", config.authentication().password());
      page.click("button[type='submit']");
      page.waitForURL("**");
    } catch (Exception e) {
      // Fallback for different Keycloak themes
      try {
        page.fill("input[name='username']", config.authentication().username());
        page.fill("input[name='password']", config.authentication().password());
        page.click("#kc-login");
        page.waitForURL("**");
      } catch (Exception ex) {
        throw new RuntimeException("Failed to perform login: " + ex.getMessage());
      }
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

  private record Response(int status, String url, HttpHeaders headers, InputStream body) {}
}
