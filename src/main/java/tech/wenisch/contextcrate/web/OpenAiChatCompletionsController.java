package tech.wenisch.contextcrate.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

  /**
   * Matches an inline citation marker, capturing any horizontal whitespace in front of it so a
   * superscript can be attached directly to the preceding word the way a footnote mark is.
   *
   * <p>The model is instructed to write {@code [n]} (AnswerService's {@code messages()}), but the
   * source delimiters it is shown in the prompt are {@code [SOURCE n] ... [END SOURCE n]}, so a
   * model that imitates that format instead is accepted too — exactly what the dashboard's own
   * citation renderer already tolerates.
   */
  private static final Pattern CITATION = Pattern.compile("([ \\t]*)\\[(?:SOURCE\\s+)?(\\d+)\\]");

  /**
   * Superscript digits 0-9, indexed by the digit itself. Note when editing that these do not come
   * from one contiguous Unicode range: 1, 2 and 3 are U+00B9/U+00B2/U+00B3 in Latin-1 Supplement for
   * historical reasons, while 0 and 4-9 are U+2070 and U+2074-U+2079 in Superscripts and Subscripts.
   * Arithmetic on a single base code point would therefore produce the wrong glyphs.
   */
  private static final char[] SUPERSCRIPT_DIGITS = {
    '⁰', '¹', '²', '³', '⁴',
    '⁵', '⁶', '⁷', '⁸', '⁹'
  };

  /** A Git source's URI is {@code git+<repository>@<40-hex-sha>/<path>}; unwrap it to the repository URL. */
  private static final Pattern GIT_SOURCE_URI = Pattern.compile("^git\\+(.+)@[0-9a-fA-F]{40}/.+$");

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
      String text = annotateCitations(generate(prepared), prepared.sources());
      return ResponseEntity.ok(completion(id, model, text, prepared));
    }

    var emitter = new SseEmitter(180_000L);
    CompletableFuture.runAsync(
        () -> {
          try {
            // The role arrives first so a client can render the assistant turn before content.
            send(emitter, chunk(id, model, new Delta("assistant", null), null));
            String text = annotateCitations(generate(prepared), prepared.sources());
            send(emitter, chunk(id, model, new Delta(null, text), null));
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
   * Turns each {@code [n]} citation marker whose number matches a known source into a Unicode
   * superscript ({@code ¹}) attached to the preceding word, and appends a reference list of the
   * cited sources after a horizontal rule.
   *
   * <p>The OpenAI schema has no field for a source list, so the citation has to travel inside the
   * message text. Superscript digits are real characters rather than {@code <sup>} markup on
   * purpose: the response is a plain string, and how it is displayed is entirely the client's
   * choice. Markup only renders where the client happens to support it, and an HTML tag that a
   * client strips or does not interpret would leave literal {@code <sup>[1]</sup>} noise in the
   * text — worse than no formatting at all. A superscript character displays as a superscript
   * everywhere, including in clients that render nothing at all.
   *
   * <p>The reference list still uses Markdown links, where the failure mode is benign: a client
   * that does not render Markdown shows the title and URL as readable text.
   *
   * <p>Links are built from ContextCrate's own known source URIs, never from anything the model
   * wrote, so a citation cannot point somewhere the model hallucinated. A marker whose number does
   * not match any source, or whose source URI is not a plain http(s) link (an unresolvable Git
   * commit reference, for instance), is left as plain {@code [n]} text and does not appear in the
   * reference list — there is nothing accurate to point it at.
   *
   * <p>Reference list entries keep the citation's own number rather than renumbering sequentially:
   * if citations 1 and 3 were used but not 2, the list reads {@code [1] ...} then {@code [3] ...}, so
   * a marker in the body always matches the same number in the list. (A Markdown ordered list would
   * silently renumber non-consecutive items, which is why this is a plain bracketed list instead.)
   */
  private static String annotateCitations(String text, List<AnswerService.Source> sources) {
    if (text == null || text.isBlank() || sources.isEmpty()) return text;
    Map<Integer, AnswerService.Source> linkable = new HashMap<>();
    for (var source : sources)
      if (sourceLink(source.sourceUri()) != null) linkable.put(source.citation(), source);
    if (linkable.isEmpty()) return text;

    Matcher matcher = CITATION.matcher(text);
    StringBuilder body = new StringBuilder();
    var used = new java.util.TreeSet<Integer>();
    while (matcher.find()) {
      int n = Integer.parseInt(matcher.group(2));
      if (!linkable.containsKey(n)) {
        matcher.appendReplacement(body, Matcher.quoteReplacement(matcher.group()));
        continue;
      }
      used.add(n);
      // A footnote mark sits against the word it belongs to, so drop the space the model wrote
      // before the marker — unless the marker opens a line, where that space is indentation.
      boolean opensLine = matcher.start() == 0 || text.charAt(matcher.start() - 1) == '\n';
      matcher.appendReplacement(
          body, Matcher.quoteReplacement((opensLine ? matcher.group(1) : "") + superscript(n)));
    }
    matcher.appendTail(body);
    if (used.isEmpty()) return body.toString();

    body.append("\n\n---\n\n");
    for (int n : used) {
      var source = linkable.get(n);
      String label = source.title() == null || source.title().isBlank()
          ? sourceLink(source.sourceUri())
          : source.title();
      body.append("[").append(n).append("] [").append(label).append("](")
          .append(sourceLink(source.sourceUri())).append(")\n");
    }
    return body.toString().stripTrailing();
  }

  /** Renders a citation number with superscript digits, so 12 becomes {@code ¹²}. */
  private static String superscript(int number) {
    StringBuilder rendered = new StringBuilder();
    for (char digit : Integer.toString(number).toCharArray())
      rendered.append(SUPERSCRIPT_DIGITS[digit - '0']);
    return rendered.toString();
  }

  /** Mirrors the dashboard's sourceLink(): unwraps a Git source URI and rejects non-http(s) links. */
  private static String sourceLink(String sourceUri) {
    if (sourceUri == null) return null;
    Matcher git = GIT_SOURCE_URI.matcher(sourceUri);
    String candidate = git.matches() ? git.group(1) : sourceUri;
    try {
      URI uri = new URI(candidate);
      String scheme = uri.getScheme();
      return "http".equals(scheme) || "https".equals(scheme) ? uri.toString() : null;
    } catch (URISyntaxException invalid) {
      return null;
    }
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
