package tech.wenisch.contextcrate.git;

import tech.wenisch.contextcrate.util.InsecureSsl;

/** Thread-scoped opt-in flag so JGit's process-wide HTTP connection factory can bypass TLS
 * validation only for the ingestion job that explicitly requested it, or for every job when the
 * global {@code contextcrate.tls.trust-all-certificates} flag is on. */
public final class GitTlsContext {
  private static final ThreadLocal<Boolean> TRUST_ALL = ThreadLocal.withInitial(() -> false);

  private GitTlsContext() {}

  public static boolean trustAll() {
    return TRUST_ALL.get() || InsecureSsl.globalTrustAll();
  }

  public static Scope use(boolean trustAllCertificates) {
    boolean previous = TRUST_ALL.get();
    TRUST_ALL.set(trustAllCertificates);
    return () -> TRUST_ALL.set(previous);
  }

  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
