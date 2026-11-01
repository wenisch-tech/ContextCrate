package tech.wenisch.contextcrate.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the internal {@code git+<repository>@<commit>/<path>} source identifier of Git documents
 * into the web URL where a person can view that file at that commit on GitHub, GitLab, Forgejo,
 * Gitea, Codeberg or Bitbucket. The host decides the URL layout; unknown self-hosted hosts are
 * treated as Forgejo/Gitea, which is the most common remaining forge.
 */
public final class GitWebLinks {
  private static final Pattern GIT_URI =
      Pattern.compile("^git\\+(.+)@([0-9a-fA-F]{40})/(.+)$", Pattern.DOTALL);
  /** scp-like form: git@host:owner/repo.git */
  private static final Pattern SCP = Pattern.compile("^(?:[^@/]+@)?([^:/]+):(?!//)(.+)$");

  private GitWebLinks() {}

  /**
   * A viewable http(s) URL for a source URI, or {@code null} when none exists. Non-Git http(s) URIs
   * are returned unchanged. Credentials embedded in a repository URL are never carried over.
   */
  public static String viewUrl(String sourceUri) {
    if (sourceUri == null || sourceUri.isBlank()) return null;
    Matcher git = GIT_URI.matcher(sourceUri);
    if (!git.matches()) return httpOrNull(sourceUri);
    Repo repo = parse(git.group(1));
    if (repo == null) return null;
    String commit = git.group(2), file = git.group(3);
    String base = repo.scheme + "://" + repo.host + "/" + repo.path;
    String host = repo.host;
    String lower = host.toLowerCase(Locale.ROOT);
    if (lower.contains("gitlab")) return base + "/-/blob/" + commit + "/" + file;
    if (lower.contains("github")) return base + "/blob/" + commit + "/" + file;
    if (lower.contains("bitbucket")) return base + "/src/" + commit + "/" + file;
    return base + "/src/commit/" + commit + "/" + file; // Forgejo, Gitea, Codeberg
  }

  private record Repo(String scheme, String host, String path) {}

  private static Repo parse(String repository) {
    String value = repository.strip();
    String scheme = "https";
    String host;
    String path;
    if (value.contains("://")) {
      try {
        URI uri = new URI(value);
        if (uri.getHost() == null) return null;
        if ("http".equalsIgnoreCase(uri.getScheme())) scheme = "http";
        boolean web = uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https");
        host = web && uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        path = uri.getRawPath();
      } catch (URISyntaxException invalid) {
        return null;
      }
    } else {
      Matcher scp = SCP.matcher(value);
      if (!scp.matches()) return null;
      host = scp.group(1);
      path = scp.group(2);
    }
    path = path.replaceAll("^/+", "").replaceAll("/+$", "");
    if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
    return path.isEmpty() ? null : new Repo(scheme, host, path);
  }

  private static String httpOrNull(String value) {
    try {
      String scheme = new URI(value).getScheme();
      return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) ? value : null;
    } catch (URISyntaxException invalid) {
      return null;
    }
  }
}
