package tech.wenisch.contextcrate.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tech.wenisch.contextcrate.domain.ApiKey;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.repository.ApiKeyRepository;
import tech.wenisch.contextcrate.repository.CrateRepository;
import tech.wenisch.contextcrate.storage.Hashing;

/** The feedback loop over real HTTP: a "Rate it" link at the end of an OpenAI-compatible answer, opened without signing in. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "contextcrate.index.backend=lucene",
    "spring.datasource.url=jdbc:h2:mem:feedback-flow;DB_CLOSE_DELAY=-1"
})
class FeedbackFlowTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  @LocalServerPort int port;
  @Autowired CrateRepository crates;
  @Autowired ApiKeyRepository keys;
  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).cookieHandler(new CookieManager()).build();
  private HttpServer upstream;
  private String token;
  private UUID crateId;

  @BeforeEach
  void setUp() throws Exception {
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/chat/completions", exchange -> {
      exchange.getRequestBody().readAllBytes();
      byte[] body = "data: {\"choices\":[{\"delta\":{\"content\":\"Grounded answer.\"}}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, body.length);
      try (var out = exchange.getResponseBody()) { out.write(body); }
    });
    upstream.start();
    crateId = crates.save(new Crate(UUID.randomUUID(), "Feedback crate", null, null)).getId();
    jdbc.update("""
        insert into crate_rag_settings(crate_id, strict_grounding, allow_client_history, inline_citations, structured_sources,
          grading_enabled, answer_verification_enabled, retrieval_mode, retrieval_strategy, proposition_failure_policy, source_limit, feedback_enabled)
        values (?, false, true, true, true, false, false, 'lexical', 'standard', 'fail-indexing', 8, true)
        """, crateId);
    jdbc.update("""
        insert into crate_provider_settings(crate_id, embeddings_enabled, embeddings_provider, answering_enabled, answering_base_url, answering_model)
        values (?, true, 'local', true, ?, 'm')
        """, crateId, "http://127.0.0.1:" + upstream.getAddress().getPort());
    jdbc.update("""
        insert into crate_index_generation(crate_id, generation, status, configuration_fingerprint, document_count, created_at, activated_at)
        values (?, 1, 'ACTIVE', 'initial', 0, current_timestamp, current_timestamp)
        """, crateId);
    token = "cc_" + UUID.randomUUID();
    keys.save(new ApiKey(UUID.randomUUID(), "openai", token.substring(0, 12), Hashing.sha256(token), ApiKey.KeyType.CRATE, null, crateId, CrateMember.Role.VIEWER));
  }

  @AfterEach
  void tearDown() { if (upstream != null) upstream.stop(0); }

  private HttpResponse<String> send(HttpRequest request) throws Exception { return http.send(request, HttpResponse.BodyHandlers.ofString()); }

  private String answerWithLink() throws Exception {
    var response = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/crates/" + crateId + "/v1/chat/completions"))
        .timeout(TIMEOUT).header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"What is retention?\"}]}")).build());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return response.body();
  }

  @Test
  void anAnswerEndsWithARatingLinkThatWorksWithoutSigningIn() throws Exception {
    String body = answerWithLink();
    var link = Pattern.compile("\\(http://localhost:" + port + "/feedback/(fb_[A-Za-z0-9_-]+)\\)").matcher(body);
    assertThat(link.find()).as("rating link in %s", body).isTrue();
    assertThat(body).contains("<hr>").contains("Was this answer helpful?");
    assertThat(body).as("no explanatory footer text").doesNotContain("link valid");
    String feedbackToken = link.group(1);
    String url = "http://localhost:" + port + "/feedback/" + feedbackToken;

    var page = send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build());
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("Rate an answer").contains("What is retention?");
    var csrf = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(page.body());
    assertThat(csrf.find()).isTrue();

    var submitted = send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + csrf.group(1) + "&relevance=4&quality=5&comment=perfect")).build());
    assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(200);
    assertThat(submitted.body()).contains("Thanks");

    var row = jdbc.queryForMap("select relevance, quality, comment from answer_feedback where crate_id = ?", crateId);
    assertThat(((Number) row.get("RELEVANCE")).intValue()).isEqualTo(4);
    assertThat(((Number) row.get("QUALITY")).intValue()).isEqualTo(5);
    assertThat(row.get("COMMENT")).isEqualTo("perfect");
    // Only a hash of the token is stored.
    assertThat(jdbc.queryForObject("select token_hash from answer_feedback where crate_id = ?", String.class, crateId)).isEqualTo(Hashing.sha256(feedbackToken));
  }

  @Test
  void anUnknownTokenLooksLikeAnExpiredOne() throws Exception {
    var page = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/feedback/fb_doesnotexist")).timeout(TIMEOUT).GET().build());
    assertThat(page.statusCode()).isEqualTo(404);
  }

  @Test
  void noLinkIsAddedWhileFeedbackIsOff() throws Exception {
    jdbc.update("update crate_rag_settings set feedback_enabled = false where crate_id = ?", crateId);
    assertThat(answerWithLink()).doesNotContain("/feedback/").doesNotContain("<hr>");
  }

  @Test
  void ratingsOutsideOneToFiveAreRefused() throws Exception {
    String body = answerWithLink();
    var link = Pattern.compile("/feedback/(fb_[A-Za-z0-9_-]+)\\)").matcher(body);
    assertThat(link.find()).isTrue();
    String url = "http://localhost:" + port + "/feedback/" + link.group(1);
    var page = send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build());
    var csrf = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(page.body());
    assertThat(csrf.find()).isTrue();
    var response = send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + csrf.group(1) + "&relevance=9&quality=5")).build());
    assertThat(response.statusCode()).isEqualTo(400);
  }
}
