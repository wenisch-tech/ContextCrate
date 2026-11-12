package tech.wenisch.contextcrate.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.contextcrate.answer.AnswerGenerationProvider.Image;
import tech.wenisch.contextcrate.answer.AnswerGenerationProvider.Message;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;

class OpenAiCompatibleAnswerProviderTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private HttpServer server;
  private final List<String> bodies = new ArrayList<>();
  private OpenAiCompatibleAnswerProvider provider;
  private final byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      boolean stream = bodies.getLast().contains("\"stream\":true");
      byte[] reply = (stream ? "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: [DONE]\n\n"
          : "{\"choices\":[{\"message\":{\"content\":\"Hello\"}}]}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
      exchange.sendResponseHeaders(200, reply.length);
      exchange.getResponseBody().write(reply);
      exchange.close();
    });
    server.start();
    var settings = mock(RuntimeProviderSettings.class);
    when(settings.effectiveAnswer()).thenReturn(new RuntimeProviderSettings.Answering(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
        "vision-model", null, null, 30, 0.2, 100));
    provider = new OpenAiCompatibleAnswerProvider(settings, MAPPER);
  }

  @AfterEach
  void stop() { server.stop(0); }

  @Test
  void messagesWithoutImagesStayPlainStrings() throws Exception {
    assertThat(provider.complete(List.of(new Message("system", "Be brief."), new Message("user", "Question?")))).isEqualTo("Hello");

    JsonNode messages = MAPPER.readTree(bodies.getFirst()).path("messages");
    assertThat(messages.path(0).path("content").isTextual()).isTrue();
    assertThat(messages.path(1).path("content").asText()).isEqualTo("Question?");
  }

  @Test
  void imagesBecomeBase64ImageUrlPartsInTheOpenAiFormat() throws Exception {
    var sources = new Message("user", "UNTRUSTED RETRIEVED SOURCES: ...").withImages(List.of(new Image("image/png", png, 2)));

    provider.complete(List.of(new Message("system", "Be brief."), sources, new Message("user", "What does the diagram show?")));

    JsonNode request = MAPPER.readTree(bodies.getFirst());
    assertThat(request.path("model").asText()).isEqualTo("vision-model");
    JsonNode content = request.path("messages").path(1).path("content");
    assertThat(content.isArray()).isTrue();
    assertThat(content.path(0).path("type").asText()).isEqualTo("text");
    assertThat(content.path(0).path("text").asText()).startsWith("UNTRUSTED RETRIEVED SOURCES");
    assertThat(content.path(1).path("text").asText()).isEqualTo("Image belonging to [SOURCE 2]:");
    assertThat(content.path(2).path("type").asText()).isEqualTo("image_url");
    assertThat(content.path(2).path("image_url").path("url").asText()).isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(png));
    // the other messages are untouched
    assertThat(request.path("messages").path(0).path("content").isTextual()).isTrue();
    assertThat(request.path("messages").path(2).path("content").asText()).isEqualTo("What does the diagram show?");
  }

  @Test
  void streamingRequestsCarryTheImagesToo() throws Exception {
    var text = new StringBuilder();

    provider.stream(List.of(new Message("user", "Sources").withImages(List.of(new Image("image/jpeg", png)))), text::append);

    assertThat(text).hasToString("Hello");
    JsonNode content = MAPPER.readTree(bodies.getFirst()).path("messages").path(0).path("content");
    assertThat(MAPPER.readTree(bodies.getFirst()).path("stream").asBoolean()).isTrue();
    assertThat(content.path(1).path("image_url").path("url").asText()).startsWith("data:image/jpeg;base64,");
    assertThat(content).hasSize(2); // no caption without a citation
  }

  @Test
  void anImageNeverShowsItsBytesInToString() {
    assertThat(new Image("image/png", png, 1).toString()).isEqualTo("Image[image/png, 7 bytes]");
    assertThat(new Message("user", "x").withImages(List.of(new Image("image/png", png))).toString()).doesNotContain("[B@");
  }
}
