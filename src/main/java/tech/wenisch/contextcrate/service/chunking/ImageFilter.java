package tech.wenisch.contextcrate.service.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which images are worth indexing, before anything is downloaded. Icons, logos, badges,
 * avatars, tracking pixels and other decoration are dropped, with a reason that is shown on the
 * document's Images tab so the rules can be tuned.
 */
public final class ImageFilter {
  /** {@code minSize} is the smallest declared width or height in pixels. */
  public record Options(boolean enabled, int minSize, List<String> extraPatterns, int maxPerPage) {
    public static Options disabled() {
      return new Options(false, 64, List.of(), 20);
    }

    public static Options of(boolean enabled, int minSize, String extraPatterns) {
      List<String> extra = new ArrayList<>();
      if (extraPatterns != null)
        for (String part : extraPatterns.split("[,\\n]"))
          if (!part.isBlank()) extra.add(part.strip().toLowerCase(Locale.ROOT));
      return new Options(enabled, minSize, extra, 20);
    }
  }

  public record Skipped(ImageRef image, String reason) {}

  public record Outcome(List<Block> blocks, List<ImageRef> kept, List<Skipped> skipped) {}

  private static final List<String> NAME_HINTS = List.of("icon", "logo", "avatar", "emoji",
      "badge", "sprite", "spinner", "loader", "favicon", "social", "share", "button", "arrow",
      "pixel", "tracking", "gravatar", "banner-ad", "advert");
  private static final List<String> BAD_HOSTS = List.of("img.shields.io", "badgen.net",
      "badge.fury.io", "travis-ci.", "circleci.com/gh", "codecov.io", "coveralls.io",
      "gravatar.com", "doubleclick.net", "google-analytics.com", "googletagmanager.com",
      "facebook.com/tr", "pixel.", "bat.bing.com", "scorecardresearch.com");
  private static final Set<String> RASTER_OR_SVG =
      Set.of("png", "jpg", "jpeg", "gif", "webp", "svg", "avif", "bmp");

  private ImageFilter() {}

  /** The reason an image is rejected, or null if it should be kept. */
  public static String reject(ImageRef image, Options options) {
    if (image.decorative()) return "marked decorative";
    String url = image.lowerUrl();
    if (image.url() == null) {
      boolean text = notBlank(image.alt()) || notBlank(image.title()) || notBlank(image.caption());
      return text ? null : "no text";
    }
    if (url.startsWith("data:") && url.length() < 2048) return "tiny inline image";
    if (url.contains("#") && url.endsWith(url.substring(url.indexOf('#')))
        && url.substring(0, url.indexOf('#')).endsWith(".svg")) return "svg sprite";
    String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
    if (path.endsWith(".ico")) return "icon file";
    if (image.alt() != null && image.alt().isEmpty() && image.caption() == null
        && !notBlank(image.title())) return "empty alt text";
    if (image.width() != null && image.width() < options.minSize()) return "smaller than " + options.minSize() + "px";
    if (image.height() != null && image.height() < options.minSize()) return "smaller than " + options.minSize() + "px";
    for (String host : BAD_HOSTS) if (url.contains(host)) return "badge or tracker";
    String haystack = (url + " " + image.hints() + " " + nullToEmpty(image.alt())).toLowerCase(Locale.ROOT);
    // A real caption or a descriptive alt text beats a name hint such as "logo" in a file name.
    boolean described = notBlank(image.caption())
        || (notBlank(image.alt()) && image.alt().strip().split("\\s+").length >= 4);
    if (!described) {
      Set<String> tokens = new java.util.HashSet<>(List.of(haystack.split("[^a-z0-9]+")));
      for (String hint : NAME_HINTS)
        if (tokens.contains(hint) || tokens.contains(hint + "s"))
          return "looks like decoration (" + hint + ")";
    }
    for (String pattern : options.extraPatterns()) if (haystack.contains(pattern)) return "skip pattern '" + pattern + "'";
    return null;
  }

  /**
   * Resolves and filters every image block of a page: relative URLs are made absolute with
   * {@code resolver}, rejected images are removed from the text, duplicates and images beyond the
   * per-page cap are dropped.
   */
  public static Outcome apply(List<Block> blocks, Options options,
      java.util.function.UnaryOperator<String> resolver) {
    List<Block> out = new ArrayList<>();
    List<ImageRef> kept = new ArrayList<>();
    List<Skipped> skipped = new ArrayList<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (Block block : blocks) {
      if (block.kind() != Block.Kind.IMAGE) {
        out.add(block);
        continue;
      }
      if (!options.enabled()) continue;
      ImageRef image = block.image();
      if (image.url() != null && resolver != null) {
        String resolved = resolver.apply(image.url());
        if (resolved == null) {
          skipped.add(new Skipped(image, "unresolvable url"));
          continue;
        }
        image = image.withUrl(resolved);
      }
      String reason = reject(image, options);
      if (reason == null && image.url() != null && !seen.add(image.url())) reason = "duplicate on page";
      if (reason == null && kept.size() >= options.maxPerPage()) reason = "per-page limit";
      if (reason != null) {
        skipped.add(new Skipped(image, reason));
        continue;
      }
      kept.add(image);
      out.add(Block.image(block.headingPath(), image));
    }
    return new Outcome(out, kept, skipped);
  }

  public static boolean isImagePath(String path) {
    if (path == null) return false;
    String lower = path.toLowerCase(Locale.ROOT);
    int dot = lower.lastIndexOf('.');
    return dot >= 0 && RASTER_OR_SVG.contains(lower.substring(dot + 1));
  }

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  private static String nullToEmpty(String v) {
    return v == null ? "" : v;
  }
}
