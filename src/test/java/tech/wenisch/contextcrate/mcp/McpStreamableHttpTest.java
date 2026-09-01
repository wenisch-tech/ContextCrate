package tech.wenisch.contextcrate.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tech.wenisch.contextcrate.domain.ApiKey;
import tech.wenisch.contextcrate.domain.Crate;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.repository.ApiKeyRepository;
import tech.wenisch.contextcrate.repository.CrateRepository;
import tech.wenisch.contextcrate.storage.Hashing;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The regression test for the transport itself.
 *
 * <p>The previous, hand-written endpoint passed every unit test and still could not be used by a
 * single real client: it answered POST with JSON only, refused GET and DELETE, issued no session id
 * and rejected the 2024-11-05 revision. Each assertion below is one of those failures, driven over a
 * real socket in the order a Streamable HTTP client performs them.
 *
 * <p>Uses {@link HttpClient} rather than a blocking REST client on purpose: the server answers with
 * {@code text/event-stream} and keeps the stream open, so the response has to be read incrementally
 * — exactly as a real client does, and exactly what a whole-body reader cannot do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "contextcrate.index.backend=lucene",
    "spring.datasource.url=jdbc:h2:mem:mcp-transport;DB_CLOSE_DELAY=-1"
})
class McpStreamableHttpTest {
  private static final String ACCEPT = "application/json, text/event-stream";
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  @LocalServerPort int port;
  @Autowired CrateRepository crates;
  @Autowired ApiKeyRepository keys;

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private String token;
  private UUID crateId;

  @BeforeEach
  void setUp() {
    Crate crate = crates.save(new Crate(UUID.randomUUID(), "Transport crate", "For the handshake", null));
    crateId = crate.getId();
    token = "cc_" + UUID.randomUUID();
    keys.save(new ApiKey(UUID.randomUUID(), "mcp", token.substring(0, 12), Hashing.sha256(token),
        ApiKey.KeyType.CRATE, null, crateId, CrateMember.Role.VIEWER));
  }

  private URI endpoint() {
    return URI.create("http://localhost:" + port + "/api/v1/crates/" + crateId + "/mcp");
  }

  private HttpRequest.Builder request(String sessionId) {
    var builder = HttpRequest.newBuilder(endpoint())
        .timeout(TIMEOUT)
        .header("Authorization", "Bearer " + token)
        .header("Accept", ACCEPT)
        .header("Content-Type", "application/json");
    if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
    return builder;
  }

  /** A POST whose response is read as a stream, so an SSE body can be consumed incrementally. */
  private HttpResponse<java.io.InputStream> post(String body, String sessionId) throws Exception {
    return http.send(request(sessionId).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofInputStream());
  }

  /**
   * Reads one JSON-RPC message, whether the server chose {@code application/json} or SSE. For SSE it
   * stops at the first complete {@code data:} event and closes, which is what ends the exchange.
   */
  private JsonNode message(HttpResponse<java.io.InputStream> response) throws Exception {
    String contentType = response.headers().firstValue("Content-Type").orElse("");
    try (var stream = response.body();
        var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      if (!contentType.contains("text/event-stream"))
        return mapper.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      StringBuilder data = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("data:")) data.append(line.substring(5).trim());
        else if (line.isEmpty() && !data.isEmpty()) break;
      }
      return mapper.readTree(data.toString());
    }
  }

  private String initialize(String version) throws Exception {
    var response = post("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"%s",
        "capabilities":{},"clientInfo":{"name":"junit","version":"1"}}}""".formatted(version), null);
    assertThat(response.statusCode()).isEqualTo(200);
    String session = response.headers().firstValue("Mcp-Session-Id").orElse(null);
    message(response);
    return session;
  }

  private String handshake() throws Exception {
    String session = initialize("2025-11-25");
    var acknowledged = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session);
    assertThat(acknowledged.statusCode()).isBetween(200, 299);
    acknowledged.body().close();
    return session;
  }

  @Test
  void initializeIssuesASessionIdSoClientsCanContinue() throws Exception {
    var response = post("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
        "capabilities":{},"clientInfo":{"name":"junit","version":"1"}}}""", null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Mcp-Session-Id")).isPresent();
    JsonNode result = message(response).get("result");
    assertThat(result.get("protocolVersion").asString()).isEqualTo("2025-11-25");
    assertThat(result.get("capabilities").has("tools")).isTrue();
    assertThat(result.get("serverInfo").get("name").asString()).isEqualTo("contextcrate");
    assertThat(result.get("instructions").asString()).contains("list_documents");
  }

  @Test
  void the2024RevisionIsAcceptedInsteadOfRejected() throws Exception {
    var response = post("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05",
        "capabilities":{},"clientInfo":{"name":"junit","version":"1"}}}""", null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(message(response).get("result").get("protocolVersion").asString())
        .isEqualTo("2024-11-05");
  }

  @Test
  void theFullDiscoveryHandshakeYieldsTheToolList() throws Exception {
    String session = handshake();

    JsonNode tools = message(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", session))
        .get("result").get("tools");

    assertThat(tools).hasSize(6);
    List<String> names = tools.valueStream().map(tool -> tool.get("name").asString()).toList();
    assertThat(names).containsExactlyInAnyOrder("search_crate", "ask_crate", "fetch_document",
        "list_documents", "list_sources", "list_crates");
  }

  @Test
  void aToolRunsWithTheCallersAuthorityAndTheCrateFromThePath() throws Exception {
    String session = handshake();

    // list_documents needs both the security context and the crate id. If either failed to survive
    // the hop from the request thread to the tool handler, this comes back as an error instead.
    JsonNode result = message(post("""
        {"jsonrpc":"2.0","id":3,"method":"tools/call",
        "params":{"name":"list_documents","arguments":{}}}""", session)).get("result");

    assertThat(result.get("isError").asBoolean()).isFalse();
    assertThat(result.get("content").get(0).get("text").asString()).contains("Transport crate");
  }

  @Test
  void aCrateScopedKeyCannotReachAnotherCrate() throws Exception {
    Crate foreign = crates.save(new Crate(UUID.randomUUID(), "Someone else", null, null));
    var response = http.send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/api/v1/crates/" + foreign.getId() + "/mcp"))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .header("Accept", ACCEPT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(403);
  }

  @Test
  void getOpensTheServerStreamInsteadOfRefusingWith405() throws Exception {
    String session = handshake();

    // The old endpoint answered GET with 405 immediately, which clients read as a dead connection.
    // A correct server either streams or holds the connection open waiting for something to send;
    // both are a pass, and both are distinguishable from a prompt refusal.
    var pending = http.sendAsync(
        request(session).header("Accept", "text/event-stream").GET().build(),
        HttpResponse.BodyHandlers.ofInputStream());
    try {
      var response = pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
      response.body().close();
      assertThat(response.statusCode()).isNotEqualTo(405);
      assertThat(response.statusCode()).isBetween(200, 299);
    } catch (java.util.concurrent.TimeoutException heldOpen) {
      // No response headers within five seconds means the stream is being held open for us.
      pending.cancel(true);
    }
  }

  @Test
  void deleteEndsTheSession() throws Exception {
    String session = handshake();

    var response = http.send(request(session).DELETE().build(), HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isNotEqualTo(405);
    assertThat(response.statusCode()).isBetween(200, 299);
  }

  @Test
  void theEndpointStillRefusesAnUnauthenticatedClient() throws Exception {
    var response = http.send(HttpRequest.newBuilder(endpoint())
            .timeout(TIMEOUT)
            .header("Accept", ACCEPT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("WWW-Authenticate")).get().asString().contains("Bearer");
  }
}
