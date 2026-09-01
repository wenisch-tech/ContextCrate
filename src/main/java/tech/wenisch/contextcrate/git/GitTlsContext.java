package tech.wenisch.contextcrate.git;

/** Thread-scoped opt-in flag so JGit's process-wide HTTP connection factory can bypass TLS
 * validation only for the ingestion job that explicitly requested it. */
public final class GitTlsContext {
  private static final ThreadLocal<Boolean> TRUST_ALL = ThreadLocal.withInitial(() -> false);

  private GitTlsContext() {}

  public static boolean trustAll() {
    return TRUST_ALL.get();
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
