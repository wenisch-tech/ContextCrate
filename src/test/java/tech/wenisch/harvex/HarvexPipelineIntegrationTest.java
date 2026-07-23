package tech.wenisch.harvex;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.wenisch.harvex.backup.PortableBackupService;
import tech.wenisch.harvex.domain.CrawlConfiguration;
import tech.wenisch.harvex.domain.NormalizedDocument;
import tech.wenisch.harvex.index.SearchIndex;
import tech.wenisch.harvex.repository.NormalizedDocumentRepository;
import tech.wenisch.harvex.service.JobService;

@SpringBootTest(
    properties = {
      "harvex.crawler.allow-private-networks=true",
      "harvex.worker.poll-delay-ms=50",
      "harvex.artifacts.path=target/it-data/artifacts",
      "harvex.index.path=target/it-data/index",
      "harvex.embeddings.enabled=false"
    })
@ActiveProfiles("test")
class HarvexPipelineIntegrationTest {
  @Autowired JobService jobs;
  @Autowired NormalizedDocumentRepository documents;
  @Autowired PortableBackupService backups;
  @Autowired SearchIndex index;
  private HttpServer server;

  @BeforeEach
  void server() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/robots.txt", x -> reply(x, 200, "User-agent: *\nAllow: /\n"));
    server.createContext(
        "/",
        x ->
            reply(
                x,
                200,
                "<html lang='en'><head><title>Home</title><meta name='description'"
                    + " content='Fixture'></head><body><main><h1>Home</h1><p>This is enough fixture"
                    + " text to exercise the complete Harvex parser and indexing pipeline in a"
                    + " deterministic integration test.</p><a"
                    + " href='/page-2'>Second</a></main></body></html>"));
    server.createContext(
        "/page-2",
        x ->
            reply(
                x,
                200,
                "<html><head><title>Second</title></head><body><main><h1>Second page</h1><p>This"
                    + " second page contains enough normalized content for indexing and confirms"
                    + " that discovered links return to the durable"
                    + " frontier.</p></main></body></html>"));
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void crawlsParsesIndexesAndBacksUp() throws Exception {
    int port = server.getAddress().getPort();
    String seed = "http://127.0.0.1:" + port + "/";
    var config =
        new CrawlConfiguration(
            new CrawlConfiguration.Scope(
                seed, Set.of("127.0.0.1"), List.of(), List.of(), 2, 10, false, false),
            new CrawlConfiguration.Politeness("HarvexTest", "", true, 1, 0, 5000),
            new CrawlConfiguration.Reliability(
                2, 10, 1_000_000, true, CrawlConfiguration.RenderMode.HTTP_ONLY),
            new CrawlConfiguration.Output(1, "main", List.of("script", "style"), 500, 50, "test"),
            CrawlConfiguration.LoginConfiguration.defaults());
    var job = jobs.create("Fixture", config);
    jobs.start(job.getId());
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      var found = documents.findAll();
      if (found.size() == 2 && found.stream().allMatch(NormalizedDocument::isIndexed)) break;
      Thread.sleep(100);
    }
    assertThat(documents.count()).isEqualTo(2);
    assertThat(documents.findAll()).allMatch(d -> d.isIndexed() && d.getBody().length() > 50);
    var hits = index.search(new SearchIndex.SearchRequest("deterministic", 10, null, "chunk"));
    assertThat(hits.hits()).isNotEmpty();
    assertThat(hits.hits()).allMatch(h -> h.kind().equals("chunk") && h.snippet().contains("deterministic"));
    var bundle = Files.createTempFile("harvex-it-", ".zip");
    try {
      backups.create(bundle, true);
      var manifest = backups.validate(bundle);
      assertThat(manifest.counts().get("data/documents.jsonl")).isEqualTo(2);
    } finally {
      Files.deleteIfExists(bundle);
    }
  }

  private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
