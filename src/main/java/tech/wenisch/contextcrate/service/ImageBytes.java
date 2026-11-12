package tech.wenisch.contextcrate.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.crawl.UrlPolicy;
import tech.wenisch.contextcrate.domain.DocumentImage;
import tech.wenisch.contextcrate.storage.ArtifactStore;

/**
 * Reads the bytes of an indexed image: from the artifact store when the image was kept there, otherwise by
 * downloading its URL (with the same address policy and redirect checks as crawling). Only png, jpeg, gif and
 * webp are accepted, and nothing larger than the given limit is read.
 */
@Component
public class ImageBytes {
  public static final Set<String> TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

  private final ArtifactStore artifacts;
  private final UrlPolicy urls;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
      .followRedirects(HttpClient.Redirect.NEVER).build();

  public ImageBytes(ArtifactStore artifacts, UrlPolicy urls) { this.artifacts = artifacts; this.urls = urls; }

  /** @throws IllegalStateException when the image is larger than {@code maxBytes}, not a supported type or cannot be downloaded */
  public byte[] load(DocumentImage image, int maxBytes) throws Exception {
    if (image.getArtifactKey() != null) {
      try (InputStream in = artifacts.open(image.getArtifactKey())) { return readLimited(in, maxBytes); }
    }
    String url = image.getUrl();
    if (url == null) throw new IllegalStateException("the image has no address");
    for (int hop = 0; hop < 4; hop++) {
      urls.assertSafe(url);
      HttpResponse<InputStream> response = client.send(
          HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
              .header("Accept", "image/*").header("User-Agent", "ContextCrateBot/0.1").GET().build(),
          HttpResponse.BodyHandlers.ofInputStream());
      int status = response.statusCode();
      if (status / 100 == 3 && response.headers().firstValue("Location").isPresent()) {
        response.body().close();
        url = URI.create(url).resolve(response.headers().firstValue("Location").get()).toString();
        continue;
      }
      try (InputStream in = response.body()) {
        if (status / 100 != 2) throw new IllegalStateException("Image download returned HTTP " + status);
        String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (!type.isEmpty() && !TYPES.contains(type.split(";")[0].strip()))
          throw new IllegalStateException("not a supported image type: " + type);
        return readLimited(in, maxBytes);
      }
    }
    throw new IllegalStateException("too many redirects");
  }

  static byte[] readLimited(InputStream in, int max) throws IOException {
    byte[] data = in.readNBytes(max + 1);
    if (data.length > max) throw new IllegalStateException("image larger than " + max + " bytes");
    return data;
  }

  /** The MIME type from the file signature, or {@code null} if it is not one of the supported types. */
  public static String sniff(byte[] b) {
    if (b.length > 3 && (b[0] & 0xff) == 0x89 && b[1] == 'P') return "image/png";
    if (b.length > 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8) return "image/jpeg";
    if (b.length > 3 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
    if (b.length > 11 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B') return "image/webp";
    return null;
  }

  /** Like {@link #sniff}, but falls back to png for the description request, which tolerates it. */
  public static String mimeOf(byte[] b) {
    String sniffed = sniff(b);
    return sniffed == null ? "image/png" : sniffed;
  }
}
