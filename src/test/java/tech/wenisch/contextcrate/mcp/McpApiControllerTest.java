package tech.wenisch.contextcrate.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import tech.wenisch.contextcrate.domain.Crate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class McpApiControllerTest {
  private final McpCrateResolver crates = mock(McpCrateResolver.class);
  private final McpToolCatalog catalog = mock(McpToolCatalog.class);
  private final McpTools tools = mock(McpTools.class);
  private final McpApiController controller =
      new McpApiController(crates, catalog, tools, "", "1.2.3");
  private final JsonMapper mapper = JsonMapper.builder().build();

  private final Crate crate = new Crate(UUID.randomUUID(), "Product docs", null, UUID.randomUUID());

  private JsonNode json(String value) {
    return mapper.readTree(value);
  }

  private ResponseEntity<Object> post(String body) {
    return controller.global(json(body), null, null);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> body(ResponseEntity<Object> response) {
    return (Map<String, Object>) response.getBody();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> result(ResponseEntity<Object> response) {
    return (Map<String, Object>) body(response).get("result");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> error(ResponseEntity<Object> response) {
    return (Map<String, Object>) body(response).get("error");
  }

  @Test
  void initializeMirrorsAVersionWeSpeak() {
    when(catalog.instructions(any())).thenReturn("instructions");

    var response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
        + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}");

    assertThat(result(response)).containsEntry("protocolVersion", "2025-06-18");
    assertThat(result(response)).containsEntry("instructions", "instructions");
  }

  @Test
  void initializeFallsBackToTheNewestSupportedVersion() {
    when(catalog.instructions(any())).thenReturn("instructions");

    var response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
        + "\"params\":{\"protocolVersion\":\"1999-01-01\"}}");

    assertThat(result(response)).containsEntry("protocolVersion", McpProtocol.LATEST_VERSION);
  }

  @Test
  void initializeAdvertisesTheToolsCapability() {
    when(catalog.instructions(any())).thenReturn("instructions");

    var response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

    assertThat(result(response)).containsKey("capabilities");
    @SuppressWarnings("unchecked")
    var capabilities = (Map<String, Object>) result(response).get("capabilities");
    assertThat(capabilities).containsKey("tools");
  }

  @Test
  void anUnsupportedProtocolVersionHeaderIsRejectedWithBadRequest() {
    var response = controller.global(json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"),
        null, "1999-01-01");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void notificationsAreAcknowledgedWithAnEmptyAccepted() {
    var response = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isNull();
  }

  @Test
  void pingAnswersWithAnEmptyResult() {
    assertThat(result(post("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}"))).isEmpty();
    assertThat(body(post("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}"))).containsEntry("id", 9);
  }

  @Test
  void anUnknownMethodIsAProtocolError() {
    var response = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\"}");

    assertThat(error(response)).containsEntry("code", McpProtocol.METHOD_NOT_FOUND);
  }

  @Test
  void anUnknownToolIsAProtocolErrorRatherThanAToolFailure() {
    when(crates.resolve(any(), any())).thenReturn(crate);

    var response = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"delete_everything\",\"arguments\":{}}}");

    assertThat(error(response)).containsEntry("code", McpProtocol.METHOD_NOT_FOUND);
  }

  @Test
  void anUnresolvableCrateIsAToolFailureSoTheModelCanRecover() {
    when(crates.resolve(any(), any()))
        .thenThrow(new McpCrateResolver.UnresolvedCrateException("Call list_crates first."));

    var response = post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"search_crate\",\"arguments\":{\"query\":\"x\"}}}");

    assertThat(body(response)).doesNotContainKey("error");
    assertThat(result(response)).containsEntry("isError", true);
  }

  @Test
  void aBodyThatIsNotAJsonRpcObjectIsAParseError() {
    var response = post("[]");

    assertThat(error(response)).containsEntry("code", McpProtocol.PARSE_ERROR);
  }

  @Test
  void aMissingMethodIsAnInvalidRequest() {
    var response = post("{\"jsonrpc\":\"2.0\",\"id\":5}");

    assertThat(error(response)).containsEntry("code", McpProtocol.INVALID_REQUEST);
  }

  @Test
  void accessDenialPropagatesSoSpringSecurityCanAnswerWith403() {
    when(crates.resolve(any(), any())).thenThrow(new AccessDeniedException("nope"));

    assertThatThrownBy(() -> post("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"search_crate\",\"arguments\":{\"query\":\"x\"}}}"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void getAndDeleteAreRefusedBecauseTheServerIsStateless() {
    assertThat(controller.unsupported().getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
  }

  @Test
  void anUnknownOriginIsForbidden() {
    var response = controller.global(json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"),
        "https://evil.example.com", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void aConfiguredOriginIsAccepted() {
    var permissive = new McpApiController(crates, catalog, tools, "https://ui.example.com", "1.0");

    var response = permissive.global(json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"),
        "https://ui.example.com", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void toolsListDelegatesToTheCatalog() {
    when(catalog.tools(null)).thenReturn(List.of(Map.of("name", "search_crate")));

    var response = post("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}");

    assertThat(result(response)).containsKey("tools");
  }

  @Test
  void listCratesDoesNotRequireACrateToBeResolvedFirst() {
    when(crates.available()).thenReturn(List.of(crate));
    when(tools.listCrates(List.of(crate))).thenReturn(McpProtocol.toolResult("ok", null));

    var response = post("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"list_crates\",\"arguments\":{}}}");

    assertThat(result(response)).containsEntry("isError", false);
    verify(crates, never()).resolve(any(), any());
  }
}
