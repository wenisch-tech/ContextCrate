package tech.wenisch.contextcrate.util;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import javax.net.ssl.SSLException;

/**
 * Turns low-level network failures into messages that say what is wrong with the remote site,
 * instead of leaking JDK internals such as "Received fatal alert: internal_error".
 */
public final class FetchErrors {
  private FetchErrors() {}

  /** A readable one-line explanation, with the original technical detail appended. */
  public static String explain(Throwable error) {
    String technical = technical(error);
    String hint = hint(error, technical.toLowerCase(Locale.ROOT));
    if (hint == null) return technical;
    return hint + " (" + technical + ")";
  }

  private static String hint(Throwable error, String text) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof UnknownHostException)
        return "The host name could not be resolved. Check the URL, or the site may be gone.";
      if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException
          || t instanceof NoRouteToHostException)
        return "Could not connect to the site. It may be down or unreachable from this server.";
      if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException)
        return "The site did not answer in time. It may be down or overloaded.";
      if (t instanceof SSLException) {
        String m = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
        if (m.contains("internal_error") || m.contains("handshake_failure") || m.contains("fatal alert")
            || m.contains("connection reset") || m.contains("remote host terminated"))
          return "The site rejected the secure connection during the TLS handshake. It is most"
              + " likely down or misconfigured on its side, not an issue with ContextCrate.";
        if (m.contains("certificate") || m.contains("pkix") || m.contains("unable to find valid"))
          return "The site's TLS certificate is not trusted. Check the certificate, or enable"
              + " the insecure-TLS option for this source if that is intended.";
        return "Secure connection to the site failed.";
      }
    }
    if (error instanceof IOException && text.contains("connection reset"))
      return "The site closed the connection. It may be down or blocking requests.";
    return null;
  }

  private static String technical(Throwable e) {
    StringBuilder detail = new StringBuilder();
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      String message = cause.getMessage();
      if (message == null || message.isBlank()) continue;
      if (!detail.isEmpty()) detail.append("; caused by: ");
      detail.append(message);
      if (detail.length() >= 1000) break;
    }
    String result = detail.isEmpty() ? e.getClass().getSimpleName() : detail.toString();
    return result.length() > 1000 ? result.substring(0, 1000) : result;
  }
}
