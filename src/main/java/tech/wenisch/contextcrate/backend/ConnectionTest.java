package tech.wenisch.contextcrate.backend;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLException;

/**
 * The result of testing a storage or search backend: an ordered list of steps (reachable, authenticated,
 * writable, ...). A failed step names the cause in plain words, the steps after it are skipped.
 */
public record ConnectionTest(boolean ok, List<Step> steps) {
  public enum Status { OK, FAILED, SKIPPED }

  public record Step(String id, String label, Status status, String detail) {}

  /** Collects steps and turns the first failure into skipped steps for the rest. */
  public static final class Builder {
    private final List<Step> steps = new ArrayList<>();
    private final List<String[]> pending = new ArrayList<>();
    private boolean failed;

    /** Declares a step that is run later, so a failure can mark the remaining ones as skipped. */
    public Builder plan(String id, String label) { pending.add(new String[] {id, label}); return this; }

    public interface Action { String run() throws Exception; }

    /** Runs the next planned step unless an earlier one failed. The action returns a short detail on success. */
    public Builder run(Action action) {
      String[] next = pending.remove(0);
      if (failed) { steps.add(new Step(next[0], next[1], Status.SKIPPED, null)); return this; }
      try {
        steps.add(new Step(next[0], next[1], Status.OK, action.run()));
      } catch (Exception e) {
        failed = true;
        steps.add(new Step(next[0], next[1], Status.FAILED, describe(e)));
      }
      return this;
    }

    public ConnectionTest build() {
      for (String[] rest : pending) steps.add(new Step(rest[0], rest[1], Status.SKIPPED, null));
      return new ConnectionTest(!failed, List.copyOf(steps));
    }
  }

  /** A readable cause for the typical connection failures; the technical message is only a fallback. */
  public static String describe(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof UnknownHostException) return "The host name cannot be resolved: " + cause.getMessage();
      if (cause instanceof ConnectException) return "Connection refused or unreachable. Check host, port and firewall.";
      if (cause instanceof SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException
          || cause instanceof software.amazon.awssdk.core.exception.ApiCallTimeoutException
          || cause instanceof software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException)
        return "The server did not answer in time.";
      if (cause instanceof SSLException) return "The TLS handshake failed (" + cause.getMessage() + "). Check the certificate or use a trusted one.";
      if (cause instanceof SecurityException) return cause.getMessage();
    }
    String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    return message.length() > 400 ? message.substring(0, 400) + "..." : message;
  }
}
