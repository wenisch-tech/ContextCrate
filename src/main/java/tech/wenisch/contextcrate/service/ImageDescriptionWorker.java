package tech.wenisch.contextcrate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.contextcrate.answer.RagSettingsService;
import tech.wenisch.contextcrate.config.CrateContext;
import tech.wenisch.contextcrate.config.RuntimeProviderSettings;
import tech.wenisch.contextcrate.crawl.UrlPolicy;
import tech.wenisch.contextcrate.domain.*;
import tech.wenisch.contextcrate.domain.PipelineTypes.WorkStage;
import tech.wenisch.contextcrate.queue.*;
import tech.wenisch.contextcrate.repository.*;
import tech.wenisch.contextcrate.storage.*;

/**
 * Describes one image with a vision model and adds the description to the document as an
 * {@code image} chunk. It is opt-in per crate and can be forbidden by an administrator. Every
 * failure is recorded on the image and never fails the run: a missing image must not lose the
 * document it belongs to.
 */
@Service
public class ImageDescriptionWorker {
  private static final Logger log = LoggerFactory.getLogger(ImageDescriptionWorker.class);
  private static final int MIN_BYTES = 5 * 1024;
  private static final String PROMPT =
      "Describe this image so it can be found by search. State what it shows, transcribe any"
          + " visible text exactly, and for diagrams list the components and how they connect."
          + " Be factual and concise (at most 150 words). If the image is purely decorative"
          + " (icon, logo, background, photo of no informational value) answer exactly DECORATIVE.";

  private final DocumentImageRepository images;
  private final DocumentChunkRepository chunks;
  private final NormalizedDocumentRepository documents;
  private final ArtifactStore artifacts;
  private final ImageBytes imageBytes;
  private final PipelineQueue queue;
  private final RagSettingsService rag;
  private final SharedModelRepository shared;
  private final RuntimeProviderSettings providers;
  private final ObjectMapper mapper;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  private tech.wenisch.contextcrate.metrics.CrateMetrics cm = tech.wenisch.contextcrate.metrics.CrateMetrics.noop();

  public ImageDescriptionWorker(DocumentImageRepository images, DocumentChunkRepository chunks,
      NormalizedDocumentRepository documents, ArtifactStore artifacts, ImageBytes imageBytes,
      PipelineQueue queue, RagSettingsService rag, SharedModelRepository shared,
      RuntimeProviderSettings providers, ObjectMapper mapper) {
    this.images = images; this.chunks = chunks; this.documents = documents; this.artifacts = artifacts;
    this.imageBytes = imageBytes; this.queue = queue; this.rag = rag; this.shared = shared;
    this.providers = providers; this.mapper = mapper;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setMetrics(tech.wenisch.contextcrate.metrics.CrateMetrics metrics) { this.cm = metrics; }

  @Transactional
  public void describe(PipelinePayload payload) {
    DocumentImage image = images.findById(payload.entityId()).orElse(null);
    if (image == null || image.getStatus() == DocumentImage.Status.DESCRIBED) return;
    if (payload.crateId() != null && !payload.crateId().equals(image.getCrateId()))
      throw new IllegalArgumentException("Pipeline message crosses crate boundary");
    try (var ignored = CrateContext.use(image.getCrateId())) {
      RagSettings settings = rag.current(image.getCrateId());
      if (!settings.isImageIndexingEnabled() || !settings.isImageDescriptionEnabled()
          || !providers.allowVisionDescriptions()) {
        finish(image, "skipped", () -> image.skip("image descriptions are disabled"));
        return;
      }
      byte[] bytes = imageBytes.load(image, settings.getImageMaxBytes());
      String reason = check(bytes, image.getUrl());
      if (reason != null) {
        finish(image, "skipped", () -> image.skip(reason));
        return;
      }
      String hash = Hashing.sha256(bytes);
      var known = images.findFirstByCrateIdAndContentHashAndStatus(image.getCrateId(), hash,
          DocumentImage.Status.DESCRIBED);
      String description;
      String model;
      if (known.isPresent()) {
        description = known.get().getDescription();
        model = known.get().getModel();
      } else {
        var endpoint = endpoint(settings);
        description = ask(endpoint, bytes, ImageBytes.mimeOf(bytes));
        model = endpoint.model();
      }
      image.stored(keep(image, bytes, hash), hash);
      if (description == null || description.isBlank() || description.strip().toUpperCase(Locale.ROOT).startsWith("DECORATIVE")) {
        finish(image, "skipped", () -> image.skip("model: decorative"));
        return;
      }
      String finalDescription = description.strip();
      String finalModel = model;
      finish(image, "described", () -> image.described(finalDescription, finalModel));
      addChunk(image, finalDescription);
    } catch (Exception error) {
      log.warn("Image description failed for {}: {}", image.getUrl(), error.toString());
      String message = tech.wenisch.contextcrate.util.FetchErrors.explain(error);
      finish(image, "failed", () -> image.fail(message.length() > 120 ? message.substring(0, 120) : message));
    }
  }

  /**
   * Keeps the bytes of an image that has no stored copy (pictures found on web pages), so that an answer can show the
   * picture to the model without downloading it again. A failure to store is not an error: the answer path downloads.
   */
  private String keep(DocumentImage image, byte[] bytes, String hash) {
    if (image.getArtifactKey() != null) return image.getArtifactKey();
    try {
      String key = "crates/" + image.getCrateId() + "/images/" + hash + ".img";
      artifacts.put(key, new ByteArrayInputStream(bytes), bytes.length + 1L);
      return key;
    } catch (Exception e) {
      log.warn("Could not store image {}: {}", image.getUrl(), e.toString());
      return null;
    }
  }

  private void finish(DocumentImage image, String outcome, Runnable change) {
    change.run();
    images.save(image);
    cm.count("images.descriptions", image.getCrateId(), "outcome", outcome);
  }

  /** Post-download filter: too small, banner-shaped, unreadable. Returns the reason or null. */
  static String check(byte[] bytes, String url) {
    if (url != null && url.toLowerCase(Locale.ROOT).split("\\?")[0].endsWith(".svg"))
      return "svg is not described";
    if (bytes.length < MIN_BYTES) return "image smaller than 5 KB";
    try {
      var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
      if (decoded != null) {
        int shortSide = Math.min(decoded.getWidth(), decoded.getHeight());
        int longSide = Math.max(decoded.getWidth(), decoded.getHeight());
        if (shortSide < 128) return "shorter side below 128px";
        if (longSide > 8L * shortSide) return "banner-shaped image";
      }
    } catch (Exception ignored) {
      // formats ImageIO cannot decode (webp) are let through
    }
    return null;
  }

  private record Endpoint(String baseUrl, String model, String apiKey, int timeoutSeconds, String headers) {}

  private Endpoint endpoint(RagSettings settings) {
    if (settings.getImageVisionModelId() != null) {
      SharedModel m = shared.findById(settings.getImageVisionModelId()).orElseThrow(
          () -> new IllegalStateException("The selected vision model no longer exists"));
      return new Endpoint(m.getBaseUrl(), m.getModel(), m.getApiKey(),
          m.getTimeoutSeconds() == null ? 120 : m.getTimeoutSeconds(), null);
    }
    var a = providers.effectiveAnswer();
    if (a.baseUrl() == null || a.baseUrl().isBlank() || a.model() == null)
      throw new IllegalStateException("No vision model is configured");
    return new Endpoint(a.baseUrl(), a.model(), a.apiKey(), Math.max(60, a.timeoutSeconds()), a.headers());
  }

  private String ask(Endpoint e, byte[] bytes, String mime) throws Exception {
    String dataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    Map<String, Object> body = Map.of("model", e.model(), "temperature", 0, "max_tokens", 600, "stream", false,
        "messages", List.of(Map.of("role", "user", "content", List.of(
            Map.of("type", "text", "text", PROMPT),
            Map.of("type", "image_url", "image_url", Map.of("url", dataUri))))));
    var request = HttpRequest.newBuilder(URI.create(e.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
        .timeout(Duration.ofSeconds(e.timeoutSeconds())).header("Content-Type", "application/json");
    if (e.apiKey() != null && !e.apiKey().isBlank()) request.header("Authorization", "Bearer " + e.apiKey());
    if (e.headers() != null) for (String h : e.headers().split("\\n")) {
      int colon = h.indexOf(':');
      if (colon > 0) request.header(h.substring(0, colon).trim(), h.substring(colon + 1).trim());
    }
    var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() / 100 != 2)
      throw new IllegalStateException("Vision model returned HTTP " + response.statusCode());
    var content = mapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
    if (content.isMissingNode() || content.isNull()) throw new IllegalStateException("Vision model returned no content");
    return content.asText();
  }

  private void addChunk(DocumentImage image, String description) {
    NormalizedDocument document = documents.findById(image.getDocumentId()).orElse(null);
    if (document == null) return;
    var existing = chunks.findByDocumentIdOrderByOrdinal(document.getId());
    int ordinal = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getOrdinal() + 1;
    String label = image.getAlt() != null && !image.getAlt().isBlank() ? image.getAlt()
        : image.getCaption() != null ? image.getCaption() : "";
    String content = "Image" + (label.isBlank() ? "" : ": " + label) + "\n" + description;
    DocumentChunk chunk = new DocumentChunk(
        UUID.nameUUIDFromBytes(("chunk:" + document.getId() + ":" + ordinal).getBytes(StandardCharsets.UTF_8)),
        document.getId(), ordinal, image.getHeading(), content, Hashing.sha256(content));
    chunk.assignCrate(document.getCrateId());
    chunk.describe("image", null, null, null, null).media(image.getViewUrl() != null ? image.getViewUrl() : image.getUrl());
    chunks.save(chunk);
    queue.publish(PipelineMessage.create(document.getCrateId(), WorkStage.INDEX,
        IngestionService.payload(document.getCrateId(), image.getRunId(), document.getId()),
        image.getRunId(), document.getCrateId() + ":index:" + document.getId() + ":image:" + image.getId(), 20));
  }
}
