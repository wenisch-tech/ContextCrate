package tech.wenisch.contextcrate;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.wenisch.contextcrate.domain.CrateIds;
import tech.wenisch.contextcrate.domain.CrawlConfiguration;
import tech.wenisch.contextcrate.domain.ConnectorType;
import tech.wenisch.contextcrate.domain.IngestionConfiguration;
import tech.wenisch.contextcrate.domain.SourceConfiguration;
import tech.wenisch.contextcrate.domain.NormalizedDocument;
import tech.wenisch.contextcrate.index.SearchIndex;
import tech.wenisch.contextcrate.repository.NormalizedDocumentRepository;
import tech.wenisch.contextcrate.service.IngestionService;
import tech.wenisch.contextcrate.service.SourceService;

@SpringBootTest(
    properties = {
      "contextcrate.crawler.allow-private-networks=true",
      "contextcrate.worker.poll-delay-ms=50",
      "contextcrate.artifacts.path=target/it-data/artifacts",
      "contextcrate.index.path=target/it-data/index",
      "contextcrate.embeddings.enabled=false"
    })
@ActiveProfiles("test")
class ContextCratePipelineIntegrationTest {
  @Autowired SourceService sources;
  @Autowired IngestionService ingestion;
  @Autowired NormalizedDocumentRepository documents;
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
                    + " text to exercise the complete ContextCrate parser and indexing pipeline in a"
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
  void crawlsParsesIndexesWithinTheLegacyCrate() throws Exception {
    int port = server.getAddress().getPort();
    String seed = "http://127.0.0.1:" + port + "/";
    var config =
        new CrawlConfiguration(
            new CrawlConfiguration.Scope(
                seed, Set.of("127.0.0.1"), List.of(), List.of(), 2, 10, false, false),
            new CrawlConfiguration.Politeness("ContextCrateTest", "", true, 1, 0, 5000),
            new CrawlConfiguration.Reliability(
                2, 10, 1_000_000, true, CrawlConfiguration.RenderMode.HTTP_ONLY),
            new CrawlConfiguration.Output(1, "main", List.of("script", "style"), 500, 50, "test"),
            CrawlConfiguration.LoginConfiguration.defaults());
    var source = sources.create(CrateIds.LEGACY, "Fixture website", null, ConnectorType.HTTPS,
        SourceConfiguration.https(seed));
    var job = ingestion.create(CrateIds.LEGACY, source.getId(), "Fixture",
        IngestionConfiguration.web(config));
    ingestion.start(CrateIds.LEGACY, source.getId(), job.getId());
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      var found = documents.findAll();
      if (found.size() == 2 && found.stream().allMatch(NormalizedDocument::isIndexed)) break;
      Thread.sleep(100);
    }
    assertThat(documents.count()).isEqualTo(2);
    assertThat(documents.findAll()).allMatch(d -> d.isIndexed() && d.getBody().length() > 50);
    var hits = index.search(new SearchIndex.SearchRequest(
        CrateIds.LEGACY, "deterministic", 10, null, "chunk"));
    assertThat(hits.hits()).isNotEmpty();
    assertThat(hits.hits()).allMatch(h -> h.kind().equals("chunk") && h.snippet().contains("deterministic"));
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
