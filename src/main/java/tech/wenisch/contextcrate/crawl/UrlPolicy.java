package tech.wenisch.contextcrate.crawl;

import java.net.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.domain.CrawlConfiguration;

@Component
public class UrlPolicy {
  private final boolean allowPrivate;

  public UrlPolicy(@Value("${contextcrate.crawler.allow-private-networks:false}") boolean allowPrivate) {
    this.allowPrivate = allowPrivate;
  }

  public String canonicalize(String raw) {
    try {
      URI u = URI.create(raw.trim()).normalize();
      String scheme = lower(u.getScheme());
      if (!Set.of("http", "https").contains(scheme))
        throw new IllegalArgumentException("Only HTTP(S) URLs are supported");
      String host = lower(IDN.toASCII(Objects.requireNonNull(u.getHost(), "URL host")));
      int port = u.getPort();
      if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443))
        port = -1;
      String path = u.getRawPath() == null || u.getRawPath().isBlank() ? "/" : u.getRawPath();
      return new URI(scheme, null, host, port, path, u.getRawQuery(), null).toASCIIString();
    } catch (URISyntaxException | NullPointerException e) {
      throw new IllegalArgumentException("Invalid URL: " + raw, e);
    }
  }

  public boolean inScope(String raw, CrawlConfiguration.Scope scope) {
    try {
      String canonical = canonicalize(raw);
      URI uri = URI.create(canonical);
      String host = lower(uri.getHost());
      Set<String> allowed =
          scope.allowedHosts().isEmpty()
              ? Set.of(lower(URI.create(scope.seedUrl()).getHost()))
              : scope.allowedHosts().stream()
                  .map(UrlPolicy::lower)
                  .collect(java.util.stream.Collectors.toSet());
      boolean hostAllowed =
          allowed.stream()
              .anyMatch(a -> host.equals(a) || (scope.allowSubdomains() && host.endsWith("." + a)));
      if (!hostAllowed) return false;
      String path = canonical;
      boolean included =
          scope.includePatterns().isEmpty()
              || scope.includePatterns().stream().anyMatch(p -> matches(p, path));
      boolean excluded = scope.excludePatterns().stream().anyMatch(p -> matches(p, path));
      return included && !excluded;
    } catch (RuntimeException e) {
      return false;
    }
  }

  public void assertSafe(String raw) throws UnknownHostException {
    if (allowPrivate) return;
    URI uri = URI.create(canonicalize(raw));
    for (InetAddress address : InetAddress.getAllByName(uri.getHost()))
      if (address.isAnyLocalAddress()
          || address.isLoopbackAddress()
          || address.isLinkLocalAddress()
          || address.isSiteLocalAddress()
          || address.isMulticastAddress()
          || isCloudMetadata(address))
        throw new SecurityException(
            "Target resolves to a blocked network address: " + address.getHostAddress());
  }

  private static boolean isCloudMetadata(InetAddress a) {
    String ip = a.getHostAddress();
    return ip.equals("169.254.169.254")
        || ip.equals("100.100.100.200")
        || ip.equals("fd00:ec2::254");
  }

  private static boolean matches(String glob, String value) {
    String regex = "\\Q" + glob.replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q") + "\\E";
    return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(value).matches();
  }

  private static String lower(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }
}
