package tech.wenisch.contextcrate.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolAdapterTest {
  @Test
  void aSuccessfulResultKeepsItsTextAndStructuredPayload() {
    var payload = McpProtocol.toolResult("two passages", Map.of("hits", List.of("a", "b")));

    McpSchema.CallToolResult result = McpToolAdapter.result(payload);

    assertThat(result.isError()).isFalse();
    assertThat(result.content()).singleElement()
        .isInstanceOfSatisfying(McpSchema.TextContent.class,
            text -> assertThat(text.text()).isEqualTo("two passages"));
    assertThat(result.structuredContent()).isEqualTo(Map.of("hits", List.of("a", "b")));
  }

  @Test
  void aBusinessFailureBecomesAnErrorResultRatherThanAProtocolFault() {
    // The model must see the explanation and be able to correct itself, which only happens when the
    // failure travels as a result with isError rather than as a JSON-RPC error.
    McpSchema.CallToolResult result =
        McpToolAdapter.result(McpProtocol.toolFailure("The \"query\" argument is required."));

    assertThat(result.isError()).isTrue();
    assertThat(result.content()).singleElement()
        .isInstanceOfSatisfying(McpSchema.TextContent.class,
            text -> assertThat(text.text()).contains("query"));
    assertThat(result.structuredContent()).isNull();
  }

  @Test
  void aResultWithoutStructuredContentOmitsIt() {
    McpSchema.CallToolResult result = McpToolAdapter.result(McpProtocol.toolResult("plain", null));

    assertThat(result.isError()).isFalse();
    assertThat(result.structuredContent()).isNull();
  }
}
