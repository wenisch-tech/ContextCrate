package tech.wenisch.contextcrate.util;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Shared trust-all SSL context builder for opt-in, per-feature TLS validation bypass. */
public final class InsecureSsl {
  private InsecureSsl() {}

  /** Opt-in only: callers explicitly accept the MITM risk for self-signed/internal CAs. */
  public static SSLContext trustAllContext() {
    try {
      TrustManager trustAll =
          new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }
          };
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new TrustManager[] {trustAll}, new SecureRandom());
      return context;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to build a trust-all SSL context", e);
    }
  }
}
