package tech.wenisch.contextcrate.util;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared trust-all SSL context builder for opt-in, per-feature TLS validation bypass. */
public final class InsecureSsl {
  private static final Logger log = LoggerFactory.getLogger(InsecureSsl.class);

  /**
   * Shared with {@code InsecureTlsStartupNotice}, which re-logs this once application logging is
   * configured — {@link #installGlobalTrustAll()} typically runs during {@code
   * EnvironmentPostProcessor} bootstrap, before Spring Boot's logging system exists, so its own
   * log call here is not guaranteed to reach the application log.
   */
  public static final String GLOBAL_TRUST_ALL_WARNING =
      "contextcrate.tls.trust-all-certificates is enabled: ALL TLS certificate and hostname "
          + "validation is disabled process-wide. Every outbound connection is exposed to "
          + "man-in-the-middle attacks. Use only with internal CAs you already trust.";

  /** Set once at startup by {@code InsecureTlsEnvironmentPostProcessor}. */
  private static volatile boolean globalTrustAll;

  private InsecureSsl() {}

  /** Opt-in only: callers explicitly accept the MITM risk for self-signed/internal CAs. */
  public static SSLContext trustAllContext() {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trustAllManagers(), new SecureRandom());
      return context;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to build a trust-all SSL context", e);
    }
  }

  /** Opt-in only: callers explicitly accept the MITM risk for self-signed/internal CAs. */
  public static TrustManager[] trustAllManagers() {
    return new TrustManager[] {
      new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
          return new X509Certificate[0];
        }
      }
    };
  }

  /** True when the operator asked for process-wide TLS validation bypass. */
  public static boolean globalTrustAll() {
    return globalTrustAll;
  }

  /**
   * Installs a trust-all context as the JVM default so every outbound TLS consumer that resolves
   * through {@link SSLContext#getDefault()} or {@link HttpsURLConnection} defaults — the JDK HTTP
   * client used by the model, embedding, reranking, robots and Keycloak clients, JGit's HTTP
   * transport and the AWS S3 url-connection client — stops validating certificates.
   *
   * <p>A trust-all {@link X509TrustManager} does not disable hostname verification, so that is
   * turned off separately. The {@code java.net.http} switch is read from a static initializer, so
   * this must run before the first {@code HttpClient} is created.
   *
   * <p>Idempotent.
   */
  public static synchronized void installGlobalTrustAll() {
    if (globalTrustAll) return;
    System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    System.setProperty("com.sun.net.ssl.checkRevocation", "false");
    SSLContext context = trustAllContext();
    SSLContext.setDefault(context);
    HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
    HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
    globalTrustAll = true;
    log.warn(GLOBAL_TRUST_ALL_WARNING);
  }
}
