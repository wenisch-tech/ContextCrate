package tech.wenisch.contextcrate.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tech.wenisch.contextcrate.answer.AnswerService;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.domain.CrateMember;
import tech.wenisch.contextcrate.service.CrateAccessService;

/**
 * Exposes a crate as an OpenAI-compatible chat model, so any OpenAI client (Open WebUI, LiteLLM,
 * the OpenAI SDK) can retrieve from it with only a base URL and an API key.
 *
 * <p>The endpoint is deliberately per-crate rather than global. A crate carries its own answer
 * provider (enabled flag, base URL, model, key) through {@link RuntimeProviderSettings}, its own
 * RAG policy, and its own index, so the crate — not the {@code model} field — is the unit of
 * configuration. Clients point their base URL at {@code /api/v1/crates/{crateId}/v1} and the
 * request's {@code model} keeps its ordinary meaning: it is accepted, ignored, and the crate's
 * configured model is reported back in the response.
 *
 * <p>Sitting under {@code /api/v1/**} means the JSON 401/403 handling and CSRF exemption already
 * configured for the API apply unchanged.
 */
@RestController
@RequestMapping("/api/v1/crates/{crateId}/v1")
public class OpenAiChatCompletionsController {
  /** Mirrors the strict-grounding short-circuit in {@link AnswerApiController}. */
  private static final String NO_ANSWER = "No answer was found in the knowledge base.";

  private final AnswerService answers;
  private final CrateAccessService access;
  private final RuntimeProviderSettings settings;

  public OpenAiChatCompletionsController(
      AnswerService answers, CrateAccessService access, RuntimeProviderSettings settings) {
    this.answers = answers;
    this.access = access;
    this.settings = settings;
  }

  @PostMapping(path = "/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> chatCompletions(
      @PathVariable UUID crateId, @RequestBody ChatCompletionRequest request) throws Exception {
    access.require(crateId, CrateMember.Role.VIEWER);
    if (!answers.available(crateId))
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(error("Answer generation is not configured for this crate.", "server_error"));

    reject(request);
    var prepared = answers.prepare(toAnswerRequest(crateId, request));
    String id = "chatcmpl-" + UUID.randomUUID();
    String model = configuredModel(crateId);

    if (!Boolean.TRUE.equals(request.stream())) {
      String text = generate(prepared);
      return ResponseEntity.ok(completion(id, model, text, prepared));
    }

    var emitter = new SseEmitter(180_000L);
    CompletableFuture.runAsync(
        () -> {
          try {
            // The role arrives first so a client can render the assistant turn before content.
            send(emitter, chunk(id, model, new Delta("assistant", null), null));
            send(emitter, chunk(id, model, new Delta(null, generate(prepared)), null));
            send(emitter, chunk(id, model, new Delta(null, null), "stop"));
            send(emitter, "[DONE]");
            emitter.complete();
          } catch (Exception e) {
            send(emitter, error("Answer generation failed", "server_error"));
            emitter.completeWithError(e);
          }
        });
    return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
  }

  /** The crate's single configured answer model, as an OpenAI model list. */
  @GetMapping("/models")
  public ModelList models(@PathVariable UUID crateId) {
    access.require(crateId, CrateMember.Role.VIEWER);
    String model = configuredModel(crateId);
    return new ModelList(
        "list",
        model == null || model.isBlank()
            ? List.of()
            : List.of(new Model(model, "model", Instant.now().getEpochSecond(), "contextcrate")));
  }

  /**
   * ContextCrate answers are generated in full before they can be returned — answer verification
   * needs the complete text — so a streamed response carries the whole answer in one content chunk
   * rather than token by token. This is the same shape the native answers API already delivers.
   */
  private String generate(AnswerService.Prepared prepared) throws Exception {
    if (prepared.strictGrounding() && prepared.sources().isEmpty()) return NO_ANSWER;
    return answers.generate(prepared).text();
  }

  private String configuredModel(UUID crateId) {
    return settings.effectiveAnswer(crateId).model();
  }

  /**
   * Refuses only what would mislead a client if silently accepted. Sampling parameters
   * ({@code temperature}, {@code max_tokens}, {@code top_p}) are ignored instead: they are crate
   * and deployment level provider configuration here, not per-request knobs.
   */
  private static void reject(ChatCompletionRequest request) {
    if (request.n() != null && request.n() != 1)
      throw new IllegalArgumentException("n must be 1; ContextCrate returns a single answer");
    if (request.tools() != null && !request.tools().isEmpty())
      throw new IllegalArgumentException("tools are not supported");
    if (request.functions() != null && !request.functions().isEmpty())
      throw new IllegalArgumentException("functions are not supported");
    String format =
        request.responseFormat() == null ? null : String.valueOf(request.responseFormat().get("type"));
    if (format != null && !"text".equals(format) && !"null".equals(format))
      throw new IllegalArgumentException("response_format " + format + " is not supported");
  }

  /**
   * Maps an OpenAI conversation onto a ContextCrate question plus history.
   *
   * <p>Client system messages are dropped rather than rejected. ContextCrate builds its own system
   * prompt and treats retrieved sources and history as untrusted data that must never be read as
   * instructions; honouring a client system message would undo that. Dropping keeps the many
   * clients working that attach a boilerplate "You are a helpful assistant" by default.
   *
   * <p>Retrieval mode, source count and the rest are left null so the crate's own RAG settings
   * apply, exactly as when they are omitted on the native answers API.
   */
  private static AnswerService.Request toAnswerRequest(UUID crateId, ChatCompletionRequest request) {
    List<ChatMessage> conversation =
        (request.messages() == null ? List.<ChatMessage>of() : request.messages())
            .stream().filter(m -> "user".equals(m.role()) || "assistant".equals(m.role())).toList();
    int last = lastUserIndex(conversation);
    if (last < 0) throw new IllegalArgumentException("messages must contain a user message");

    List<AnswerService.HistoryMessage> history = new ArrayList<>();
    for (int i = 0; i < last; i++)
      history.add(
          new AnswerService.HistoryMessage(
              conversation.get(i).role(), conversation.get(i).text()));
    return new AnswerService.Request(
        crateId, conversation.get(last).text(), null, null, null, null, history);
  }

  private static int lastUserIndex(List<ChatMessage> conversation) {
    for (int i = conversation.size() - 1; i >= 0; i--)
      if ("user".equals(conversation.get(i).role())) return i;
    return -1;
  }

  private static ChatCompletion completion(
      String id, String model, String text, AnswerService.Prepared prepared) {
    int prompt = estimateTokens(prepared);
    int completion = tokens(text);
    return new ChatCompletion(
        id,
        "chat.completion",
        Instant.now().getEpochSecond(),
        model,
        List.of(new Choice(0, new ChatMessage("assistant", text), "stop")),
        new Usage(prompt, completion, prompt + completion));
  }

  private static ChatCompletionChunk chunk(
      String id, String model, Delta delta, String finishReason) {
    return new ChatCompletionChunk(
        id,
        "chat.completion.chunk",
        Instant.now().getEpochSecond(),
        model,
        List.of(new ChunkChoice(0, delta, finishReason)));
  }

  /** Usage is reported as a rough estimate rather than omitted; clients read it unguarded. */
  private static int estimateTokens(AnswerService.Prepared prepared) {
    int characters = prepared.question().length();
    for (var message : prepared.history()) characters += message.content().length();
    for (var source : prepared.sources()) characters += source.content().length();
    return characters / 4;
  }

  private static int tokens(String text) {
    return text == null ? 0 : text.length() / 4;
  }

  /** OpenAI streams bare {@code data:} frames with no event name, unlike the native answers API. */
  private static void send(SseEmitter emitter, Object payload) {
    try {
      emitter.send(SseEmitter.event().data(payload));
    } catch (Exception ignored) {
      // The client hung up; the emitter is already finished.
    }
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest()
        .body(error(e.getMessage() == null ? "Invalid request" : e.getMessage(), "invalid_request_error"));
  }

  private static ErrorResponse error(String message, String type) {
    return new ErrorResponse(new ErrorBody(message, type, null, null));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChatCompletionRequest(
      String model,
      List<ChatMessage> messages,
      Boolean stream,
      Integer n,
      List<Object> tools,
      List<Object> functions,
      @JsonProperty("response_format") Map<String, Object> responseFormat) {}

  /**
   * {@code content} is typed loosely because the OpenAI schema allows either a string or an array
   * of typed parts, and clients send both. {@link #text()} flattens either into plain text.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChatMessage(String role, Object content) {
    public String text() {
      if (content instanceof String value) return value;
      if (content instanceof List<?> parts) {
        var text = new StringBuilder();
        for (Object part : parts)
          if (part instanceof Map<?, ?> map && "text".equals(map.get("type")))
            text.append(map.get("text"));
        return text.toString();
      }
      return content == null ? "" : String.valueOf(content);
    }
  }

  public record ChatCompletion(
      String id, String object, long created, String model, List<Choice> choices, Usage usage) {}

  public record Choice(
      int index, ChatMessage message, @JsonProperty("finish_reason") String finishReason) {}

  public record Usage(
      @JsonProperty("prompt_tokens") int promptTokens,
      @JsonProperty("completion_tokens") int completionTokens,
      @JsonProperty("total_tokens") int totalTokens) {}

  public record ChatCompletionChunk(
      String id, String object, long created, String model, List<ChunkChoice> choices) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ChunkChoice(
      int index, Delta delta, @JsonProperty("finish_reason") String finishReason) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Delta(String role, String content) {}

  public record ModelList(String object, List<Model> data) {}

  public record Model(
      String id, String object, long created, @JsonProperty("owned_by") String ownedBy) {}

  public record ErrorResponse(ErrorBody error) {}

  public record ErrorBody(String message, String type, String param, String code) {}
}
