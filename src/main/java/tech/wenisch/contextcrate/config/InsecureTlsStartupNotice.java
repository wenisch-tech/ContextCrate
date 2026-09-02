package tech.wenisch.contextcrate.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tech.wenisch.contextcrate.util.InsecureSsl;

/**
 * Re-logs the global TLS trust-all warning once the application is fully up.
 *
 * <p>{@link InsecureTlsEnvironmentPostProcessor} installs the trust-all context and logs a
 * warning from {@link InsecureSsl#installGlobalTrustAll()}, but that runs before Spring Boot's
 * logging system is configured, so the warning is silently dropped from the real application log.
 * This listener runs after startup completes, when logging is guaranteed to be initialized.
 */
@Component
public class InsecureTlsStartupNotice {
  private static final Logger log = LoggerFactory.getLogger(InsecureTlsStartupNotice.class);

  @EventListener(ApplicationReadyEvent.class)
  void logIfEnabled() {
    if (InsecureSsl.globalTrustAll()) {
      log.warn(InsecureSsl.GLOBAL_TRUST_ALL_WARNING);
    }
  }
}
