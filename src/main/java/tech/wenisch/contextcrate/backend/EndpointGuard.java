package tech.wenisch.contextcrate.backend;

import java.net.URI;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.crawl.UrlPolicy;

/**
 * Endpoints that crate creators type in are called by the server, so they follow the same rule as the crawler:
 * addresses in private networks, loopback, link-local and the cloud metadata service are refused unless
 * {@code contextcrate.crawler.allow-private-networks} is on.
 */
@Component
public class EndpointGuard {
  private static final Pattern JDBC = Pattern.compile("^jdbc:postgresql://([^/?#]+)(.*)$", Pattern.CASE_INSENSITIVE);
  private final UrlPolicy policy;

  public EndpointGuard(UrlPolicy policy) { this.policy = policy; }

  /** @throws IllegalArgumentException with a message the user can act on */
  public void check(String endpoint, boolean jdbc) {
    String value = endpoint == null ? "" : endpoint.strip();
    if (value.isEmpty()) throw new IllegalArgumentException("An endpoint is required");
    String url = value;
    if (jdbc) {
      Matcher m = JDBC.matcher(value);
      if (!m.matches()) throw new IllegalArgumentException("The endpoint has to be a JDBC URL like jdbc:postgresql://host:5432/database");
      url = "http://" + m.group(1);
    } else if (!value.matches("(?i)^https?://.+")) {
      throw new IllegalArgumentException("The endpoint has to start with http:// or https://");
    }
    try {
      if (URI.create(url).getUserInfo() != null) throw new IllegalArgumentException("Put credentials into the credential fields, not into the URL");
      policy.assertSafe(url);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("The host name cannot be resolved: " + e.getMessage());
    } catch (SecurityException e) {
      throw new IllegalArgumentException(e.getMessage() + ". Private network addresses are not allowed for dedicated backends.");
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("The endpoint is not a valid URL");
    }
  }
}
