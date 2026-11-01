package tech.wenisch.contextcrate.service.chunking;

import java.util.Locale;

/**
 * An image found in a document. {@code hints} holds the lower-cased class, id, role and similar
 * attributes of the element, which the relevance filter inspects. {@code url} is null for inline
 * SVG, which only contributes its title and description.
 */
public record ImageRef(String url, String alt, String title, String caption, Integer width,
    Integer height, String hints, boolean decorative) {

  /** The short label used in the indexed text: alt, else title, else the file name. */
  public String label() {
    for (String candidate : new String[] {alt, title}) if (candidate != null && !candidate.isBlank()) return candidate.strip();
    if (url == null) return "";
    String path = url;
    int cut = path.indexOf('?');
    if (cut >= 0) path = path.substring(0, cut);
    path = path.substring(path.lastIndexOf('/') + 1);
    int dot = path.lastIndexOf('.');
    return (dot > 0 ? path.substring(0, dot) : path).replace('-', ' ').replace('_', ' ').strip();
  }

  /** The searchable one-line form that is packed into chunks next to the surrounding text. */
  public String line() {
    StringBuilder out = new StringBuilder("[Image: ").append(label());
    if (caption != null && !caption.isBlank() && !caption.strip().equalsIgnoreCase(label()))
      out.append(" — ").append(caption.strip());
    out.append(']');
    if (url != null) out.append(" (").append(url).append(')');
    return out.toString();
  }

  public ImageRef withUrl(String resolved) {
    return new ImageRef(resolved, alt, title, caption, width, height, hints, decorative);
  }

  public String lowerUrl() {
    return url == null ? "" : url.toLowerCase(Locale.ROOT);
  }
}
